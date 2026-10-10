import Foundation

public struct KodiError: LocalizedError {
    public let errorDescription: String?

    public init(_ message: String) {
        errorDescription = message
    }
}

/// Was ein Start der Übertragung auf Kodi unterbräche.
public enum Interruption: Equatable {
    case playback(title: String)
    /// Die Übertragung eines anderen Geräts.
    case otherCast
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

    public static func pluginURL(
        port: Int, audioPort: Int? = nil, audioDelayMs: Int = 0, source: String? = nil
    ) -> String {
        var url = "plugin://\(addonID)/?port=\(port)"
        if let source {
            // Mit der Adresse nimmt das Addon nur die Pakete dieses Rechners an.
            url += "&source=\(source)"
        }
        if let audioPort {
            url += "&audio_port=\(audioPort)&audio_delay=\(audioDelayMs)"
        }
        return url
    }

    /// Kodi spielt einen Screencast auf diesem Port, von welchem Gerät auch immer.
    public static func isScreencast(_ file: String, port: Int) -> Bool {
        file.hasPrefix("udp://@:\(port)/?") || file.hasPrefix("udp://@:\(port)?")
            || file.hasPrefix(pluginURL(port: port))
    }

    /// Absenderadresse, auf die das Addon den Strom beschränkt hat, sonst nil.
    public static func streamSource(_ file: String) -> String? {
        let parts = file.split(separator: "?", maxSplits: 1)
        guard parts.count == 2 else { return nil }
        var params: [String: String] = [:]
        for pair in parts[1].split(separator: "&") {
            let entry = pair.split(separator: "=", maxSplits: 1)
            if entry.count == 2 {
                params[String(entry[0])] = String(entry[1])
            }
        }
        // "sources" heißt die Angabe in der Stream-Adresse, "source" in der Plugin-Adresse.
        return params["sources"] ?? params["source"]
    }

    /// Der Strom einer laufenden eigenen Übertragung. Ein Addon ohne
    /// Absenderfilter nennt keinen Absender; dann gilt jeder Screencast auf
    /// dem Port als der eigene.
    public static func isOwnStream(_ file: String, port: Int, source: String?) -> Bool {
        guard isScreencast(file, port: port) else { return false }
        guard let named = streamSource(file) else { return true }
        return named == source
    }

    /// Die eigene IP-Adresse, wie Kodi sie als Absender der Pakete sieht.
    public func localAddress() -> String? {
        guard let host = url.host else { return nil }
        var hints = addrinfo()
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_DGRAM
        var result: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, "9", &hints, &result) == 0, let info = result else { return nil }
        defer { freeaddrinfo(result) }
        let handle = socket(AF_INET, SOCK_DGRAM, 0)
        guard handle >= 0 else { return nil }
        defer { close(handle) }
        // Legt nur den Weg fest, sendet nichts.
        guard connect(handle, info.pointee.ai_addr, info.pointee.ai_addrlen) == 0 else {
            return nil
        }
        var local = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let status = withUnsafeMutablePointer(to: &local) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(handle, $0, &length)
            }
        }
        guard status == 0 else { return nil }
        var text = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard inet_ntop(AF_INET, &local.sin_addr, &text, socklen_t(INET_ADDRSTRLEN)) != nil else {
            return nil
        }
        return String(cString: text)
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

    public func play(port: Int, audioPort: Int?, audioDelayMs: Int, source: String?) async throws {
        let file = Self.pluginURL(
            port: port, audioPort: audioPort, audioDelayMs: audioDelayMs, source: source)
        _ = try await call("Player.Open", ["item": ["file": file]])
    }

    /// Kennung des Players, der gerade den eigenen Stream spielt.
    private func ownPlayer(port: Int, source: String?) async throws -> Int? {
        guard let players = try await call("Player.GetActivePlayers") as? [[String: Any]] else {
            return nil
        }
        for player in players {
            guard let id = player["playerid"] as? Int else { continue }
            let params: [String: Any] = ["playerid": id, "properties": ["file"]]
            let answer = try await call("Player.GetItem", params) as? [String: Any]
            let item = answer?["item"] as? [String: Any]
            if Self.isOwnStream(item?["file"] as? String ?? "", port: port, source: source) {
                return id
            }
        }
        return nil
    }

    /// Was ein Start unterbräche; nil, wenn auf Kodi nichts Fremdes läuft.
    public func otherPlayback(port: Int, source: String?) async throws -> Interruption? {
        guard let players = try await call("Player.GetActivePlayers") as? [[String: Any]] else {
            return nil
        }
        for player in players {
            guard let id = player["playerid"] as? Int else { continue }
            let params: [String: Any] = ["playerid": id, "properties": ["file", "title"]]
            let answer = try await call("Player.GetItem", params) as? [String: Any]
            guard let item = answer?["item"] as? [String: Any] else { continue }
            let file = item["file"] as? String ?? ""
            if !Self.isScreencast(file, port: port) {
                return .playback(title: Self.title(of: item))
            }
            // Vor dem Start ist nur ein Strom mit der eigenen Adresse der eigene
            // (ein Rest eines früheren Laufs); einer ohne Absender stammt von
            // einem älteren Sender.
            if source == nil || Self.streamSource(file) != source {
                return .otherCast
            }
        }
        return nil
    }

    /// Titel, sonst Beschriftung, sonst der Dateiname.
    static func title(of item: [String: Any]) -> String {
        for key in ["title", "label"] {
            if let text = item[key] as? String, !text.isEmpty {
                return text
            }
        }
        let file = item["file"] as? String ?? ""
        return file.split(separator: "/").last.map(String.init) ?? file
    }

    /// Sekunden, die der eigene Stream schon spielt; nil, solange er nicht läuft.
    public func playbackTime(port: Int, source: String?) async throws -> Double? {
        guard let id = try await ownPlayer(port: port, source: source) else { return nil }
        let params: [String: Any] = ["playerid": id, "properties": ["time"]]
        guard let answer = try await call("Player.GetProperties", params) as? [String: Any],
            let time = answer["time"] as? [String: Any]
        else { return nil }
        func part(_ key: String) -> Double { (time[key] as? NSNumber)?.doubleValue ?? 0 }
        return part("hours") * 3600 + part("minutes") * 60 + part("seconds")
            + part("milliseconds") / 1000
    }

    /// Stoppt die Wiedergabe nur, wenn noch der eigene Stream läuft.
    public func stop(port: Int, source: String?) async throws {
        if let id = try await ownPlayer(port: port, source: source) {
            _ = try await call("Player.Stop", ["playerid": id])
        }
    }
}
