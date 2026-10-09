import CoreMedia
import CoreVideo
import Foundation

/// Kodiert ein paar künstliche Bilder und schreibt den fertigen MPEG-TS-Strom
/// in eine Datei. So lassen sich Encoder und Muxer ohne Bildschirmfreigabe
/// prüfen, etwa mit ffprobe.
public enum SelfTest {
    public static func writeStream(to path: String, frames: Int = 90, fps: Int = 30) throws {
        let width = 1280
        let height = 720
        let encoder = try VideoEncoder(width: width, height: height, fps: fps, bitrateKbps: 4000)
        var muxer = TSMuxer()
        var stream = Data()
        let lock = NSLock()
        encoder.onFrame = { accessUnit, pts, keyframe in
            lock.lock()
            stream.append(muxer.mux(accessUnit: accessUnit, pts: pts, keyframe: keyframe))
            lock.unlock()
        }

        for index in 0..<frames {
            var created: CVPixelBuffer?
            let attributes = [kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary] as CFDictionary
            CVPixelBufferCreate(
                nil, width, height, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, attributes,
                &created)
            guard let buffer = created else {
                throw EncoderError(errorDescription: "Testbild lässt sich nicht anlegen.")
            }
            fill(buffer, step: index)
            if index == frames / 2 {
                encoder.requestKeyframe()
            }
            encoder.encode(buffer, pts: CMTime(value: CMTimeValue(index), timescale: CMTimeScale(fps)))
        }
        encoder.finish()
        encoder.invalidate()

        lock.lock()
        defer { lock.unlock() }
        guard !stream.isEmpty, stream.count % TSMuxer.packetSize == 0 else {
            throw EncoderError(errorDescription: "Encoder hat keinen gültigen Strom geliefert.")
        }
        try stream.write(to: URL(fileURLWithPath: path))
    }

    /// Grauverlauf mit einem wandernden hellen Balken.
    private static func fill(_ buffer: CVPixelBuffer, step: Int) {
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        for plane in 0..<CVPixelBufferGetPlaneCount(buffer) {
            guard let base = CVPixelBufferGetBaseAddressOfPlane(buffer, plane) else { continue }
            let rowBytes = CVPixelBufferGetBytesPerRowOfPlane(buffer, plane)
            let rows = CVPixelBufferGetHeightOfPlane(buffer, plane)
            let pixels = base.assumingMemoryBound(to: UInt8.self)
            for row in 0..<rows {
                for column in 0..<rowBytes {
                    var value: UInt8 = plane == 0 ? UInt8(16 + column * 200 / max(1, rowBytes)) : 128
                    if plane == 0, abs(column - step * 12) < 40 {
                        value = 235
                    }
                    pixels[row * rowBytes + column] = value
                }
            }
        }
    }
}
