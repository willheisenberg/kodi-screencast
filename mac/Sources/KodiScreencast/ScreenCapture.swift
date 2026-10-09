import AppKit
import CoreMedia
import Foundation
import ScreenCaptureKit
import ScreencastCore

struct CaptureError: LocalizedError {
    let errorDescription: String?
}

/// Nimmt den Hauptbildschirm und den Systemton über ScreenCaptureKit auf.
final class ScreenCapture: NSObject, SCStreamOutput, SCStreamDelegate {
    /// Rohes PCM für den Ton-Empfänger (siehe `PCM`).
    var onAudio: ((Data) -> Void)?
    /// Die Aufnahme ist von außen beendet worden, z. B. über die Systemleiste.
    var onStop: ((Error) -> Void)?

    private var stream: SCStream?
    private let queue = DispatchQueue(label: "screencast.capture")
    private let lock = NSLock()
    private var latest: CVPixelBuffer?

    /// macOS liefert nur bei Bildänderungen neue Bilder. Der Sender holt sich
    /// deshalb im festen Takt das jeweils letzte ab.
    var latestFrame: CVPixelBuffer? {
        lock.lock()
        defer { lock.unlock() }
        return latest
    }

    /// Verkleinert auf maxHeight Zeilen, Seitenverhältnis bleibt, Maße gerade.
    static func scaledSize(width: Int, height: Int, maxHeight: Int) -> (width: Int, height: Int) {
        var scaledWidth = Double(width)
        var scaledHeight = Double(height)
        if height > maxHeight {
            scaledWidth = scaledWidth * Double(maxHeight) / scaledHeight
            scaledHeight = Double(maxHeight)
        }
        return (Int(scaledWidth) / 2 * 2, Int(scaledHeight) / 2 * 2)
    }

    func start(maxHeight: Int, fps: Int, audio: Bool) async throws -> (width: Int, height: Int) {
        let content: SCShareableContent
        do {
            content = try await SCShareableContent.excludingDesktopWindows(
                false, onScreenWindowsOnly: true)
        } catch {
            throw CaptureError(
                errorDescription: "Bildschirmaufnahme nicht erlaubt. In den Systemeinstellungen unter "
                    + "Datenschutz & Sicherheit > Bildschirmaufnahme freigeben und die App neu starten.")
        }
        let mainID = CGMainDisplayID()
        guard let display = content.displays.first(where: { $0.displayID == mainID })
            ?? content.displays.first
        else {
            throw CaptureError(errorDescription: "Kein Bildschirm gefunden.")
        }

        let scale = Int(NSScreen.main?.backingScaleFactor ?? 1)
        let size = Self.scaledSize(
            width: display.width * scale, height: display.height * scale, maxHeight: maxHeight)

        let configuration = SCStreamConfiguration()
        configuration.width = size.width
        configuration.height = size.height
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(fps))
        configuration.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        configuration.showsCursor = true
        configuration.queueDepth = 4
        configuration.capturesAudio = audio
        configuration.sampleRate = 48000
        configuration.channelCount = 2
        configuration.excludesCurrentProcessAudio = true

        let filter = SCContentFilter(display: display, excludingApplications: [], exceptingWindows: [])
        let stream = SCStream(filter: filter, configuration: configuration, delegate: self)
        try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: queue)
        if audio {
            try stream.addStreamOutput(self, type: .audio, sampleHandlerQueue: queue)
        }
        try await stream.startCapture()
        self.stream = stream
        return size
    }

    func stop() async {
        let running = stream
        stream = nil
        try? await running?.stopCapture()
        lock.lock()
        latest = nil
        lock.unlock()
    }

    // MARK: - SCStreamOutput

    func stream(
        _ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
        of type: SCStreamOutputType
    ) {
        guard sampleBuffer.isValid else { return }
        switch type {
        case .screen:
            // Leerlauf-Meldungen ohne Bildinhalt überspringen.
            guard let buffer = sampleBuffer.imageBuffer else { return }
            lock.lock()
            latest = buffer
            lock.unlock()
        case .audio:
            if let data = Self.pcm(from: sampleBuffer) {
                onAudio?(data)
            }
        default:
            break
        }
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        onStop?(error)
    }

    /// ScreenCaptureKit liefert 32-Bit-Gleitkomma, je nach System mit
    /// getrennten oder verschachtelten Kanälen.
    private static func pcm(from sampleBuffer: CMSampleBuffer) -> Data? {
        guard let format = sampleBuffer.formatDescription?.audioStreamBasicDescription,
            format.mFormatFlags & kAudioFormatFlagIsFloat != 0, format.mBitsPerChannel == 32
        else { return nil }
        let channelCount = Int(format.mChannelsPerFrame)
        let planar = format.mFormatFlags & kAudioFormatFlagIsNonInterleaved != 0

        var channels: [[Float]] = []
        try? sampleBuffer.withAudioBufferList { list, _ in
            let buffers = list.map { buffer -> [Float] in
                guard let data = buffer.mData else { return [] }
                let count = Int(buffer.mDataByteSize) / MemoryLayout<Float>.size
                return Array(UnsafeBufferPointer(start: data.assumingMemoryBound(to: Float.self), count: count))
            }
            if planar {
                channels = buffers
            } else if let samples = buffers.first {
                channels = PCM.deinterleave(samples, channelCount: channelCount)
            }
        }
        return channels.isEmpty ? nil : PCM.int16Stereo(channels: channels)
    }
}
