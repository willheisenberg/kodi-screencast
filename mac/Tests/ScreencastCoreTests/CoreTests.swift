import XCTest

@testable import ScreencastCore

final class TSMuxerTests: XCTestCase {
    func testCRCMatchesTheMPEG2CheckValue() {
        XCTAssertEqual(TSMuxer.crc32(Array("123456789".utf8)), 0x0376_E6E7)
    }

    func testOutputIsWholeSyncedPackets() {
        var muxer = TSMuxer()
        for size in [1, 150, 169, 170, 171, 183, 184, 185, 5000] {
            let stream = muxer.mux(accessUnit: Data(repeating: 0xAB, count: size), pts: 1, keyframe: false)
            XCTAssertEqual(stream.count % TSMuxer.packetSize, 0, "Größe \(size)")
            for start in stride(from: 0, to: stream.count, by: TSMuxer.packetSize) {
                XCTAssertEqual(stream[start], 0x47)
            }
        }
    }

    func testKeyframesAreAnnouncedByPATAndPMT() {
        var muxer = TSMuxer()
        let stream = [UInt8](muxer.mux(accessUnit: Data([1, 2, 3]), pts: 0, keyframe: true))
        XCTAssertEqual(stream.count, 3 * TSMuxer.packetSize)
        XCTAssertEqual(pid(stream, packet: 0), 0)
        XCTAssertEqual(pid(stream, packet: 1), TSMuxer.pmtPID)
        XCTAssertEqual(pid(stream, packet: 2), TSMuxer.videoPID)
        // Keyframe: Adaptation Field mit PCR und Random-Access-Kennung.
        XCTAssertEqual(stream[2 * 188 + 5] & 0x50, 0x50)
    }

    func testPayloadSurvivesPacketisation() {
        var muxer = TSMuxer()
        let accessUnit = Data((0..<1000).map { UInt8($0 % 251) })
        let stream = [UInt8](muxer.mux(accessUnit: accessUnit, pts: 2.5, keyframe: false))
        var payload = [UInt8]()
        for start in stride(from: 0, to: stream.count, by: 188) {
            var offset = start + 4
            if stream[start + 3] & 0x20 != 0 {
                offset += 1 + Int(stream[start + 4])
            }
            payload.append(contentsOf: stream[offset..<start + 188])
        }
        XCTAssertEqual(Array(payload.prefix(4)), [0, 0, 1, 0xE0])
        XCTAssertEqual(Data(payload.dropFirst(14)), accessUnit)
    }

    func testContinuityCounterCountsPerPID() {
        var muxer = TSMuxer()
        let stream = [UInt8](muxer.mux(accessUnit: Data(repeating: 0, count: 600), pts: 0, keyframe: false))
        let counters = stride(from: 0, to: stream.count, by: 188).map { stream[$0 + 3] & 0x0F }
        XCTAssertEqual(counters, [0, 1, 2, 3])
    }

    private func pid(_ stream: [UInt8], packet: Int) -> UInt16 {
        UInt16(stream[packet * 188 + 1] & 0x1F) << 8 | UInt16(stream[packet * 188 + 2])
    }
}

final class AnnexBTests: XCTestCase {
    func testLengthFieldsBecomeStartCodesAfterTheDelimiter() {
        let sample = Data([0, 0, 0, 2, 0xAA, 0xBB, 0, 0, 0, 1, 0xCC])
        let unit = AnnexB.accessUnit(lengthPrefixed: sample, parameterSets: [Data([0x40, 0x01])])
        XCTAssertEqual(
            [UInt8](unit),
            [0, 0, 0, 1, 0x46, 0x01, 0x50]
                + [0, 0, 0, 1, 0x40, 0x01]
                + [0, 0, 0, 1, 0xAA, 0xBB]
                + [0, 0, 0, 1, 0xCC])
    }

    func testTruncatedSamplesDoNotCrash() {
        let unit = AnnexB.accessUnit(lengthPrefixed: Data([0, 0, 0, 9, 0xAA]))
        XCTAssertEqual([UInt8](unit), AnnexB.hevcDelimiter)
    }
}

final class PCMTests: XCTestCase {
    func testFloatsBecomeInterleavedLittleEndianInt16() {
        let data = PCM.int16Stereo(channels: [[0, 1], [-1, 2]])
        XCTAssertEqual([UInt8](data), [0x00, 0x00, 0x01, 0x80, 0xFF, 0x7F, 0xFF, 0x7F])
    }

    func testMonoIsDoubled() {
        XCTAssertEqual([UInt8](PCM.int16Stereo(channels: [[1]])), [0xFF, 0x7F, 0xFF, 0x7F])
    }

    func testDeinterleave() {
        XCTAssertEqual(PCM.deinterleave([1, 2, 3, 4], channelCount: 2), [[1, 3], [2, 4]])
    }
}

final class KodiClientTests: XCTestCase {
    func testPluginURLCarriesTheAudioSettings() {
        XCTAssertEqual(
            KodiClient.pluginURL(port: 5004, audioPort: 5005, audioDelayMs: 350),
            "plugin://plugin.video.screencast/?port=5004&audio_port=5005&audio_delay=350")
        XCTAssertEqual(KodiClient.pluginURL(port: 5004), "plugin://plugin.video.screencast/?port=5004")
    }

    func testOwnStreamIsRecognisedInBothForms() {
        XCTAssertTrue(KodiClient.isOwnStream("udp://@:5004?fifo_size=50000", port: 5004))
        XCTAssertTrue(
            KodiClient.isOwnStream(KodiClient.pluginURL(port: 5004, audioPort: 5005), port: 5004))
        XCTAssertFalse(KodiClient.isOwnStream("udp://@:5005?x=1", port: 5004))
        XCTAssertFalse(KodiClient.isOwnStream("/storage/film.mkv", port: 5004))
    }

    func testTitleFallsBackToLabelThenFileName() {
        XCTAssertEqual(KodiClient.title(of: ["title": "Send Help", "label": "x"]), "Send Help")
        XCTAssertEqual(KodiClient.title(of: ["title": "", "label": "Send Help"]), "Send Help")
        XCTAssertEqual(KodiClient.title(of: ["file": "/media/clip.mkv"]), "clip.mkv")
    }
}
