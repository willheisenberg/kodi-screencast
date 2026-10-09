import AppKit
import SwiftUI

enum SettingsKey {
    static let host = "host"
    static let user = "user"
    static let password = "password"
    static let audio = "audio"
    static let audioDelay = "audioDelayMs"
}

struct KodiScreencastApp: App {
    @StateObject private var session = CastSession()

    var body: some Scene {
        MenuBarExtra("Kodi-Screencast", systemImage: session.isActive ? "tv.fill" : "tv") {
            MenuContent(session: session)
        }
        Settings {
            SettingsView()
        }
    }
}

struct MenuContent: View {
    @ObservedObject var session: CastSession
    @Environment(\.openSettings) private var openSettings

    @AppStorage(SettingsKey.host) private var host = ""
    @AppStorage(SettingsKey.user) private var user = ""
    @AppStorage(SettingsKey.password) private var password = ""
    @AppStorage(SettingsKey.audio) private var audio = true
    @AppStorage(SettingsKey.audioDelay) private var audioDelay = 350

    var body: some View {
        Text(statusText)
        if session.isActive {
            Button("Übertragung beenden") { session.stop() }
        } else {
            Button("Übertragung starten") {
                session.start(
                    CastSettings(
                        host: host, user: user, password: password, audio: audio,
                        audioDelayMs: audioDelay))
            }
        }
        Divider()
        Button("Einstellungen …") {
            NSApp.activate(ignoringOtherApps: true)
            openSettings()
        }
        Button("Beenden") {
            session.stop()
            NSApp.terminate(nil)
        }
    }

    private var statusText: String {
        switch session.state {
        case .idle: return host.isEmpty ? "Bitte zuerst die IP-Adresse eintragen" : "Bereit"
        case .starting: return "Wird gestartet …"
        case .running: return "Überträgt an \(host)"
        case .failed(let message): return message
        }
    }
}

struct SettingsView: View {
    @AppStorage(SettingsKey.host) private var host = ""
    @AppStorage(SettingsKey.user) private var user = ""
    @AppStorage(SettingsKey.password) private var password = ""
    @AppStorage(SettingsKey.audio) private var audio = true
    @AppStorage(SettingsKey.audioDelay) private var audioDelay = 350

    var body: some View {
        Form {
            TextField("IP-Adresse von Kodi:", text: $host, prompt: Text("z. B. 192.168.178.10"))
            Section("Nur falls Kodi eine Anmeldung verlangt") {
                TextField("Benutzer:", text: $user)
                SecureField("Passwort:", text: $password)
            }
            Section("Ton") {
                Toggle("Systemton übertragen", isOn: $audio)
                TextField("Verzögerung in ms:", value: $audioDelay, format: .number)
                Text("Kommt der Ton vor dem Bild, den Wert erhöhen; kommt er danach, senken.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .frame(width: 420)
        .fixedSize(horizontal: false, vertical: true)
    }
}
