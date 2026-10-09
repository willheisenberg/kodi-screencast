import Foundation

public struct KodiError: LocalizedError {
    public let errorDescription: String?

    public init(_ message: String) {
        errorDescription = message
    }
}

/// JSON-RPC-Client für Kodis Fernsteuer-Schnittstelle (HTTP).
public final class KodiClient {
    public static let addonID = "plugin.video.screencast"

    private let url: URL
    private let authorization: String?

    public init(host: String, port: Int = 8080, user: String = "", password: String = "") throws {
        guard let url = URL(string: "http://\(host):\(port)/jsonrpc") else {
            throw KodiError("\(host) ist keine gültige Adresse.")
        }
        self.url = url
        if user.isEmpty {
            authorization = nil
        } else {
            authorization = "Basic " + Data("\(user):\(password)".utf8).base64EncodedString()
        }
    }

    public static func pluginURL(port: Int, audioPort: Int? = nil, audioDelayMs: Int = 0) -> String {
        var url = "plugin://\(addonID)/?port=\(port)"
        if let audioPort {
            url += "&audio_port=\(audioPort)&audio_delay=\(audioDelayMs)"
        }
        return url
    }

    public static func isOwnStream(_ file: String, port: Int) -> Bool {
        file.hasPrefix("udp://@:\(port)?") || file.hasPrefix(pluginURL(port: port))
    }

    public func call(_ method: String, _ params: [String: Any]? = nil) async throws -> Any {
        var body: [String: Any] = ["jsonrpc": "2.0", "id": 1, "method": method]
        if let params {
            body["params"] = params
        }
        var request = URLRequest(url: url, timeoutInterval: 5)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let authorization {
            request.setValue(authorization, forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch {
            throw KodiError(
                "Kodi ist unter \(url.host ?? "") nicht erreichbar. In Kodi muss unter "
                    + "Einstellungen > Dienste > Steuerung die Fernsteuerung über HTTP erlaubt sein.")
        }
        if let http = response as? HTTPURLResponse, http.statusCode != 200 {
            if http.statusCode == 401 {
                throw KodiError("Kodi verlangt Benutzername und Passwort (siehe Einstellungen).")
            }
            throw KodiError("Kodi antwortet mit HTTP \(http.statusCode).")
        }
        guard let answer = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw KodiError("Kodi hat unverständlich geantwortet.")
        }
        if let error = answer["error"] as? [String: Any] {
            throw KodiError("\(method): \(error["message"] as? String ?? "Fehler")")
        }
        return answer["result"] ?? NSNull()
    }

    public func hasAddon() async -> Bool {
        let params: [String: Any] = ["addonid": Self.addonID, "properties": ["enabled"]]
        guard let details = try? await call("Addons.GetAddonDetails", params) as? [String: Any],
            let addon = details["addon"] as? [String: Any]
        else { return false }
        return addon["enabled"] as? Bool ?? false
    }

    public func play(port: Int, audioPort: Int?, audioDelayMs: Int) async throws {
        let file = Self.pluginURL(port: port, audioPort: audioPort, audioDelayMs: audioDelayMs)
        _ = try await call("Player.Open", ["item": ["file": file]])
    }

    /// Kennung des Players, der gerade den eigenen Stream spielt.
    private func ownPlayer(port: Int) async throws -> Int? {
        guard let players = try await call("Player.GetActivePlayers") as? [[String: Any]] else {
            return nil
        }
        for player in players {
            guard let id = player["playerid"] as? Int else { continue }
            let params: [String: Any] = ["playerid": id, "properties": ["file"]]
            let answer = try await call("Player.GetItem", params) as? [String: Any]
            let item = answer?["item"] as? [String: Any]
            if Self.isOwnStream(item?["file"] as? String ?? "", port: port) {
                return id
            }
        }
        return nil
    }

    /// Sekunden, die der eigene Stream schon spielt; nil, solange er nicht läuft.
    public func playbackTime(port: Int) async throws -> Double? {
        guard let id = try await ownPlayer(port: port) else { return nil }
        let params: [String: Any] = ["playerid": id, "properties": ["time"]]
        guard let answer = try await call("Player.GetProperties", params) as? [String: Any],
            let time = answer["time"] as? [String: Any]
        else { return nil }
        func part(_ key: String) -> Double { (time[key] as? NSNumber)?.doubleValue ?? 0 }
        return part("hours") * 3600 + part("minutes") * 60 + part("seconds")
            + part("milliseconds") / 1000
    }

    /// Stoppt die Wiedergabe nur, wenn noch der eigene Stream läuft.
    public func stop(port: Int) async throws {
        if let id = try await ownPlayer(port: port) {
            _ = try await call("Player.Stop", ["playerid": id])
        }
    }
}
