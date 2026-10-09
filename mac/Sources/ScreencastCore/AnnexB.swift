import Foundation

/// Wandelt die Bilder aus VideoToolbox (NAL-Einheiten mit Längenfeld) in das
/// Annex-B-Format mit Startcodes um, das MPEG-TS verlangt.
public enum AnnexB {
    static let startCode: [UInt8] = [0, 0, 0, 1]
    /// Access Unit Delimiter für HEVC; markiert den Beginn jedes Bildes.
    static let hevcDelimiter: [UInt8] = [0, 0, 0, 1, 0x46, 0x01, 0x50]

    /// - Parameter parameterSets: VPS, SPS und PPS; nur bei Keyframes mitgeben.
    public static func accessUnit(
        lengthPrefixed sample: Data, nalLengthSize: Int = 4, parameterSets: [Data] = []
    ) -> Data {
        var out = Data(capacity: sample.count + 64)
        out.append(contentsOf: hevcDelimiter)
        for set in parameterSets {
            out.append(contentsOf: startCode)
            out.append(set)
        }
        let bytes = [UInt8](sample)
        var offset = 0
        while offset + nalLengthSize <= bytes.count {
            var length = 0
            for index in 0..<nalLengthSize {
                length = length << 8 | Int(bytes[offset + index])
            }
            offset += nalLengthSize
            guard length > 0, offset + length <= bytes.count else { break }
            out.append(contentsOf: startCode)
            out.append(contentsOf: bytes[offset..<offset + length])
            offset += length
        }
        return out
    }
}
