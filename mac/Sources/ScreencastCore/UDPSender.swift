import Foundation

public struct UDPError: LocalizedError {
    public let errorDescription: String?
}

/// Schickt Datagramme an eine feste Adresse.
public final class UDPSender {
    private let socketHandle: Int32
    private var address: sockaddr_storage
    private let addressLength: socklen_t

    public init(host: String, port: Int) throws {
        var hints = addrinfo()
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_DGRAM
        var result: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, String(port), &hints, &result) == 0, let info = result else {
            throw UDPError(errorDescription: "Adresse \(host) lässt sich nicht auflösen.")
        }
        defer { freeaddrinfo(result) }
        var storage = sockaddr_storage()
        memcpy(&storage, info.pointee.ai_addr, Int(info.pointee.ai_addrlen))
        address = storage
        addressLength = info.pointee.ai_addrlen
        socketHandle = socket(AF_INET, SOCK_DGRAM, 0)
        guard socketHandle >= 0 else {
            throw UDPError(errorDescription: "UDP-Socket lässt sich nicht öffnen.")
        }
    }

    deinit {
        close(socketHandle)
    }

    /// Teilt die Daten in Datagramme der angegebenen Größe und verschickt sie.
    public func send(_ data: Data, datagramSize: Int) {
        data.withUnsafeBytes { (buffer: UnsafeRawBufferPointer) in
            guard let base = buffer.baseAddress else { return }
            var offset = 0
            while offset < buffer.count {
                let size = min(datagramSize, buffer.count - offset)
                withUnsafePointer(to: &address) { pointer in
                    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { target in
                        _ = sendto(socketHandle, base + offset, size, 0, target, addressLength)
                    }
                }
                offset += size
            }
        }
    }
}
