import AppKit
import CoreMedia
import Foundation
import ScreencastCore

struct CastSettings {
    static let videoPort = 5004
    static let audioPort = 5005
    static let fps = 30
    static let maxHeight = 1080
    static let bitrateKbps = 8000

    var host: String
    var user: String
    var password: String
    var audio: Bool
    var audioDelayMs: Int
}

/// Eine laufende Übertragung: Aufnahme, Kodierung, Versand und Kodi-Steuerung.
@MainActor
final class CastSession: ObservableObject {
    enum State: Equatable {
        case idle
        case starting
        case running
        case failed(String)
    }

    // Kodi spielt alles ab, was während seines Starts ankommt, und läuft
    // deshalb dauerhaft um diese Startzeit (gut 1 s) hinterher. Eine
    // Sendepause, sobald die Wiedergabe läuft, lässt Kodis Puffer leerlaufen;
    // den Zeitsprung danach rechnet Kodi heraus.
    private static let catchUpPause: Duration = .seconds(2)
    private static let catchUpAfter = 1.0
    private static let catchUpGiveUp: Duration = .seconds(20)

    @Published private(set) var state: State = .idle

    private var capture: ScreenCapture?
    private var pipeline: Pipeline?
    private var kodi: KodiClient?
    private var catchUp: Task<Void, Never>?

    var isActive: Bool { state == .starting || state == .running }

    func start(_ settings: CastSettings) {
        guard !isActive else { return }
        state = .starting
        Task {
            // Läuft auf Kodi schon etwas, erst nachfragen. Ist Kodi nicht
            // erreichbar, meldet das gleich der eigentliche Start.
            if let title = await interrupted(by: settings), !confirmInterrupting(title) {
                state = .idle
                return
            }
            do {
                try await run(settings)
                state = .running
            } catch {
                await shutDown()
                state = .failed(error.localizedDescription)
            }
        }
    }

    func stop() {
        guard isActive else { return }
        Task {
            await shutDown()
            state = .idle
        }
    }

    private func interrupted(by settings: CastSettings) async -> String? {
        let host = settings.host.trimmingCharacters(in: .whitespaces)
        guard !host.isEmpty,
            let kodi = try? KodiClient(host: host, user: settings.user, password: settings.password)
        else { return nil }
        return try? await kodi.otherPlayback(port: CastSettings.videoPort)
    }

    private func confirmInterrupting(_ title: String) -> Bool {
        let alert = NSAlert()
        alert.messageText = "Auf Kodi läuft gerade „\(title)“."
        alert.informativeText =
            "Für die Übertragung unterbrechen? Danach läuft es an derselben Stelle weiter."
        alert.addButton(withTitle: "Unterbrechen")
        alert.addButton(withTitle: "Abbrechen")
        NSApp.activate(ignoringOtherApps: true)
        return alert.runModal() == .alertFirstButtonReturn
    }

    private func run(_ settings: CastSettings) async throws {
        let host = settings.host.trimmingCharacters(in: .whitespaces)
        guard !host.isEmpty else {
            throw KodiError("In den Einstellungen fehlt die IP-Adresse von Kodi.")
        }
        let kodi = try KodiClient(host: host, user: settings.user, password: settings.password)
        // Erst ein echter Aufruf, damit Verbindungs- und Anmeldefehler sichtbar werden.
        _ = try await kodi.call("JSONRPC.Ping")
        guard await kodi.hasAddon() else {
            throw KodiError(
                "Das Addon \(KodiClient.addonID) ist in Kodi nicht installiert oder deaktiviert.")
        }
        self.kodi = kodi

        let capture = ScreenCapture()
        self.capture = capture
        let size = try await capture.start(
            maxHeight: CastSettings.maxHeight, fps: CastSettings.fps, audio: settings.audio)

        let pipeline = try Pipeline(
            host: host, width: size.width, height: size.height, capture: capture)
        self.pipeline = pipeline
        if settings.audio {
            let audioSender = try UDPSender(host: host, port: CastSettings.audioPort)
            capture.onAudio = { data in
                audioSender.send(data, datagramSize: PCM.datagramSize)
            }
        }
        capture.onStop = { [weak self] error in
            Task { @MainActor in
                guard let self, self.isActive else { return }
                await self.shutDown()
                self.state = .failed("Aufnahme beendet: \(error.localizedDescription)")
            }
        }
        pipeline.start()

        try await kodi.play(
            port: CastSettings.videoPort,
            audioPort: settings.audio ? CastSettings.audioPort : nil,
            audioDelayMs: settings.audioDelayMs)

        catchUp = Task { [weak pipeline] in
            let clock = ContinuousClock()
            let deadline = clock.now + Self.catchUpGiveUp
            while true {
                let played = try? await kodi.playbackTime(port: CastSettings.videoPort)
                if let played, played >= Self.catchUpAfter { break }
                if Task.isCancelled || clock.now > deadline { return }
                try? await Task.sleep(for: .milliseconds(250))
            }
            pipeline?.setGate(closed: true)
            try? await Task.sleep(for: Self.catchUpPause)
            pipeline?.setGate(closed: false)
        }
    }

    private func shutDown() async {
        catchUp?.cancel()
        catchUp = nil
        pipeline?.stop()
        pipeline = nil
        await capture?.stop()
        capture = nil
        try? await kodi?.stop(port: CastSettings.videoPort)
        kodi = nil
    }
}

/// Holt im festen Takt das letzte Bild, kodiert es und verschickt den Strom.
final class Pipeline {
    private let encoder: VideoEncoder
    private let sender: UDPSender
    private let capture: ScreenCapture
    private let queue = DispatchQueue(label: "screencast.pipeline")
    private var muxer = TSMuxer()
    private var timer: DispatchSourceTimer?
    private var gateClosed = false
    private var firstPTS: Double?

    init(host: String, width: Int, height: Int, capture: ScreenCapture) throws {
        self.capture = capture
        sender = try UDPSender(host: host, port: CastSettings.videoPort)
        encoder = try VideoEncoder(
            width: width, height: height, fps: CastSettings.fps,
            bitrateKbps: CastSettings.bitrateKbps)
        encoder.onFrame = { [weak self] accessUnit, pts, keyframe in
            self?.queue.async {
                guard let self else { return }
                // Die Systemuhr zählt seit dem Einschalten; der Strom beginnt bei null.
                let start = self.firstPTS ?? pts
                self.firstPTS = start
                let packets = self.muxer.mux(
                    accessUnit: accessUnit, pts: pts - start, keyframe: keyframe)
                if !self.gateClosed {
                    self.sender.send(packets, datagramSize: TSMuxer.datagramSize)
                }
            }
        }
    }

    func start() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(
            deadline: .now(), repeating: .nanoseconds(1_000_000_000 / CastSettings.fps),
            leeway: .milliseconds(2))
        timer.setEventHandler { [weak self] in
            guard let self, let frame = self.capture.latestFrame else { return }
            self.encoder.encode(frame, pts: CMClockGetTime(CMClockGetHostTimeClock()))
        }
        timer.resume()
        self.timer = timer
    }

    /// Hält den Strom zurück; nach dem Öffnen setzt er mit einem Keyframe wieder ein.
    func setGate(closed: Bool) {
        queue.async {
            self.gateClosed = closed
            if !closed {
                self.encoder.requestKeyframe()
            }
        }
    }

    func stop() {
        timer?.cancel()
        timer = nil
        encoder.invalidate()
    }
}
