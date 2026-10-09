import CoreMedia
import Foundation
import VideoToolbox

public struct EncoderError: LocalizedError {
    public let errorDescription: String?
}

/// HEVC-Encoder über VideoToolbox, eingestellt auf geringe Verzögerung:
/// keine B-Frames und ein Keyframe pro Sekunde, damit Kodi nach
/// Paketverlust schnell wieder ein vollständiges Bild findet.
public final class VideoEncoder {
    /// Fertiges Bild im Annex-B-Format, Zeitstempel in Sekunden, Keyframe ja/nein.
    public var onFrame: ((Data, Double, Bool) -> Void)?

    private var session: VTCompressionSession?
    private let lock = NSLock()
    private var keyframeRequested = false

    public init(width: Int, height: Int, fps: Int, bitrateKbps: Int) throws {
        var created: VTCompressionSession?
        let status = VTCompressionSessionCreate(
            allocator: nil, width: Int32(width), height: Int32(height),
            codecType: kCMVideoCodecType_HEVC, encoderSpecification: nil,
            imageBufferAttributes: nil, compressedDataAllocator: nil,
            outputCallback: nil, refcon: nil, compressionSessionOut: &created)
        guard status == noErr, let session = created else {
            throw EncoderError(errorDescription: "HEVC-Encoder lässt sich nicht öffnen (\(status)).")
        }
        self.session = session

        let bitrate = bitrateKbps * 1000
        let properties: [(CFString, Any)] = [
            (kVTCompressionPropertyKey_RealTime, true),
            (kVTCompressionPropertyKey_AllowFrameReordering, false),
            (kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_HEVC_Main_AutoLevel),
            (kVTCompressionPropertyKey_MaxKeyFrameInterval, fps),
            (kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, 1),
            (kVTCompressionPropertyKey_ExpectedFrameRate, fps),
            (kVTCompressionPropertyKey_AverageBitRate, bitrate),
            // Höchstens das Anderthalbfache der Zielrate innerhalb einer Sekunde.
            (kVTCompressionPropertyKey_DataRateLimits, [bitrate / 8 * 3 / 2, 1]),
        ]
        for (key, value) in properties {
            VTSessionSetProperty(session, key: key, value: value as CFTypeRef)
        }
        VTCompressionSessionPrepareToEncodeFrames(session)
    }

    deinit {
        invalidate()
    }

    /// Das nächste Bild wird ein Keyframe.
    public func requestKeyframe() {
        lock.lock()
        keyframeRequested = true
        lock.unlock()
    }

    public func encode(_ pixelBuffer: CVPixelBuffer, pts: CMTime) {
        guard let session else { return }
        lock.lock()
        let forceKeyframe = keyframeRequested
        keyframeRequested = false
        lock.unlock()

        var frameProperties: CFDictionary?
        if forceKeyframe {
            frameProperties = [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary
        }
        VTCompressionSessionEncodeFrame(
            session, imageBuffer: pixelBuffer, presentationTimeStamp: pts, duration: .invalid,
            frameProperties: frameProperties, infoFlagsOut: nil
        ) { [weak self] status, _, sampleBuffer in
            guard status == noErr, let sampleBuffer else { return }
            self?.deliver(sampleBuffer)
        }
    }

    /// Wartet, bis alle angenommenen Bilder kodiert sind.
    public func finish() {
        if let session {
            VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
        }
    }

    public func invalidate() {
        if let session {
            VTCompressionSessionInvalidate(session)
        }
        session = nil
    }

    private func deliver(_ sampleBuffer: CMSampleBuffer) {
        guard CMSampleBufferDataIsReady(sampleBuffer),
            let block = CMSampleBufferGetDataBuffer(sampleBuffer)
        else { return }

        var sample = Data(count: CMBlockBufferGetDataLength(block))
        let copied = sample.withUnsafeMutableBytes { buffer -> OSStatus in
            guard let base = buffer.baseAddress else { return -1 }
            return CMBlockBufferCopyDataBytes(
                block, atOffset: 0, dataLength: buffer.count, destination: base)
        }
        guard copied == noErr else { return }

        let attachments =
            CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false)
            as? [[CFString: Any]]
        let notSync = attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool ?? false
        let keyframe = !notSync

        var parameterSets: [Data] = []
        var nalLengthSize: Int32 = 4
        if let format = CMSampleBufferGetFormatDescription(sampleBuffer) {
            var count = 0
            CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(
                format, parameterSetIndex: 0, parameterSetPointerOut: nil,
                parameterSetSizeOut: nil, parameterSetCountOut: &count,
                nalUnitHeaderLengthOut: &nalLengthSize)
            if keyframe {
                for index in 0..<count {
                    var pointer: UnsafePointer<UInt8>?
                    var size = 0
                    let status = CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(
                        format, parameterSetIndex: index, parameterSetPointerOut: &pointer,
                        parameterSetSizeOut: &size, parameterSetCountOut: nil,
                        nalUnitHeaderLengthOut: nil)
                    if status == noErr, let pointer {
                        parameterSets.append(Data(bytes: pointer, count: size))
                    }
                }
            }
        }

        let accessUnit = AnnexB.accessUnit(
            lengthPrefixed: sample, nalLengthSize: Int(nalLengthSize), parameterSets: parameterSets)
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer).seconds
        onFrame?(accessUnit, pts, keyframe)
    }
}
