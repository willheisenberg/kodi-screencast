import Foundation

/// Der Ton-Empfänger auf dem Pi erwartet 48 kHz, Stereo, 16 Bit Little Endian.
public enum PCM {
    /// Größte Nutzlast pro Datagramm; ein Vielfaches von vier Byte (ein Stereo-Frame).
    public static let datagramSize = 1200

    /// - Parameter channels: je Kanal die Abtastwerte von -1 bis 1. Mono wird verdoppelt.
    public static func int16Stereo(channels: [[Float]]) -> Data {
        guard let left = channels.first else { return Data() }
        let right = channels.count > 1 ? channels[1] : left
        let frames = min(left.count, right.count)
        var out = [UInt8]()
        out.reserveCapacity(frames * 4)
        for frame in 0..<frames {
            for sample in [left[frame], right[frame]] {
                let value = Int16(max(-1, min(1, sample)) * 32767)
                out.append(UInt8(truncatingIfNeeded: value))
                out.append(UInt8(truncatingIfNeeded: value >> 8))
            }
        }
        return Data(out)
    }

    /// Trennt verschachtelte Abtastwerte (L R L R …) nach Kanälen auf.
    public static func deinterleave(_ samples: [Float], channelCount: Int) -> [[Float]] {
        guard channelCount > 0 else { return [] }
        var channels = [[Float]](repeating: [], count: channelCount)
        for (index, sample) in samples.enumerated() {
            channels[index % channelCount].append(sample)
        }
        return channels
    }
}
