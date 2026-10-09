import Foundation

/// Verpackt HEVC-Bilder in einen MPEG-TS-Strom mit genau einem Videoprogramm.
///
/// Vor jedem Keyframe stehen PAT und PMT, damit Kodi an jeder Stelle
/// einsteigen kann, auch nach einer Sendepause.
public struct TSMuxer {
    public static let packetSize = 188
    /// Sieben TS-Pakete (1316 Byte) passen in ein UDP-Datagramm.
    public static let datagramSize = 7 * packetSize

    static let pmtPID: UInt16 = 0x1000
    static let videoPID: UInt16 = 0x0100
    static let hevcStreamType: UInt8 = 0x24
    /// Zeitstempel beginnen bei 1 s, damit die PCR davor nie negativ wird.
    static let ptsOffset: UInt64 = 90_000
    static let pcrLead: UInt64 = 9_000

    private var counters: [UInt16: UInt8] = [:]

    public init() {}

    public mutating func mux(accessUnit: Data, pts seconds: Double, keyframe: Bool) -> Data {
        var out = [UInt8]()
        out.reserveCapacity(accessUnit.count + accessUnit.count / 20 + 600)
        if keyframe {
            psiPacket(pid: 0, section: Self.patSection(), into: &out)
            psiPacket(pid: Self.pmtPID, section: Self.pmtSection(), into: &out)
        }

        // Zeitstempel haben 33 Bit und laufen nach gut 26 Stunden über.
        let ticks = UInt64(max(0, seconds) * 90_000) + Self.ptsOffset
        let pts = ticks & 0x1_FFFF_FFFF
        let pcr = (ticks - Self.pcrLead) & 0x1_FFFF_FFFF
        var payload = Self.pesHeader(pts: pts)
        payload.append(contentsOf: accessUnit)

        var offset = 0
        var first = true
        while offset < payload.count {
            var adaptation: [UInt8]? = nil
            if first {
                adaptation = Self.pcrField(pcr: pcr, randomAccess: keyframe)
            }
            let remaining = payload.count - offset
            var space = 184 - (adaptation.map { $0.count + 1 } ?? 0)
            if remaining < space {
                // Das letzte Paket wird über das Adaptation Field aufgefüllt.
                let padding = space - remaining
                if var field = adaptation {
                    field.append(contentsOf: [UInt8](repeating: 0xFF, count: padding))
                    adaptation = field
                } else if padding == 1 {
                    adaptation = []
                } else {
                    adaptation = [0x00] + [UInt8](repeating: 0xFF, count: padding - 2)
                }
                space = remaining
            }

            let pid = Self.videoPID
            out.append(0x47)
            out.append((first ? 0x40 : 0x00) | UInt8(pid >> 8 & 0x1F))
            out.append(UInt8(pid & 0xFF))
            out.append((adaptation == nil ? 0x10 : 0x30) | nextCounter(pid))
            if let field = adaptation {
                out.append(UInt8(field.count))
                out.append(contentsOf: field)
            }
            out.append(contentsOf: payload[offset..<offset + space])
            offset += space
            first = false
        }
        return Data(out)
    }

    private mutating func nextCounter(_ pid: UInt16) -> UInt8 {
        let value = counters[pid, default: 0]
        counters[pid] = (value + 1) & 0x0F
        return value
    }

    private mutating func psiPacket(pid: UInt16, section: [UInt8], into out: inout [UInt8]) {
        out.append(0x47)
        out.append(0x40 | UInt8(pid >> 8 & 0x1F))
        out.append(UInt8(pid & 0xFF))
        out.append(0x10 | nextCounter(pid))
        out.append(0x00)  // pointer_field
        out.append(contentsOf: section)
        out.append(contentsOf: [UInt8](repeating: 0xFF, count: 183 - section.count))
    }

    // MARK: - Tabellen und Kopfdaten

    static func patSection() -> [UInt8] {
        section(tableID: 0x00, body: [
            0x00, 0x01,  // program_number
            0xE0 | UInt8(pmtPID >> 8), UInt8(pmtPID & 0xFF),
        ])
    }

    static func pmtSection() -> [UInt8] {
        section(tableID: 0x02, body: [
            0xE0 | UInt8(videoPID >> 8), UInt8(videoPID & 0xFF),  // PCR_PID
            0xF0, 0x00,  // program_info_length
            hevcStreamType,
            0xE0 | UInt8(videoPID >> 8), UInt8(videoPID & 0xFF),
            0xF0, 0x00,  // ES_info_length
        ])
    }

    /// Gemeinsamer Rahmen von PAT und PMT: Kopf, Programmnummer 1, CRC.
    static func section(tableID: UInt8, body: [UInt8]) -> [UInt8] {
        let length = 5 + body.count + 4
        var bytes: [UInt8] = [
            tableID,
            0xB0 | UInt8(length >> 8), UInt8(length & 0xFF),
            0x00, 0x01,  // transport_stream_id bzw. program_number
            0xC1,  // version 0, current_next
            0x00, 0x00,  // section_number, last_section_number
        ]
        bytes.append(contentsOf: body)
        let crc = crc32(bytes)
        bytes.append(contentsOf: [
            UInt8(crc >> 24), UInt8(crc >> 16 & 0xFF), UInt8(crc >> 8 & 0xFF), UInt8(crc & 0xFF),
        ])
        return bytes
    }

    static func pesHeader(pts: UInt64) -> [UInt8] {
        [
            0x00, 0x00, 0x01, 0xE0,
            0x00, 0x00,  // Länge 0: bei Video unbegrenzt
            0x84,  // data_alignment_indicator
            0x80,  // nur PTS
            0x05,
            0x21 | UInt8(pts >> 29 & 0x0E),
            UInt8(pts >> 22 & 0xFF),
            0x01 | UInt8(pts >> 14 & 0xFE),
            UInt8(pts >> 7 & 0xFF),
            0x01 | UInt8(pts << 1 & 0xFE),
        ]
    }

    static func pcrField(pcr: UInt64, randomAccess: Bool) -> [UInt8] {
        [
            0x10 | (randomAccess ? 0x40 : 0x00),
            UInt8(pcr >> 25 & 0xFF),
            UInt8(pcr >> 17 & 0xFF),
            UInt8(pcr >> 9 & 0xFF),
            UInt8(pcr >> 1 & 0xFF),
            UInt8(pcr << 7 & 0x80) | 0x7E,
            0x00,
        ]
    }

    /// CRC-32/MPEG-2, wie sie PAT und PMT abschließt.
    static func crc32(_ bytes: [UInt8]) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in bytes {
            crc ^= UInt32(byte) << 24
            for _ in 0..<8 {
                crc = crc & 0x8000_0000 != 0 ? (crc << 1) ^ 0x04C1_1DB7 : crc << 1
            }
        }
        return crc
    }
}
