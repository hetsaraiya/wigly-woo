import CoreMedia
import Foundation
import VideoToolbox

/// Decodes one HEVC or H.264 access unit at a time and asks the display to
/// show it immediately, so a late frame is not queued.
public final class VideoDecoder {
    public var onSample: ((CMSampleBuffer) -> Void)?
    public var onEncoded: ((CMSampleBuffer) -> Void)?
    public private(set) var width: Int32 = 0
    public private(set) var height: Int32 = 0
    public private(set) var dropped = 0

    private var format: CMVideoFormatDescription?
    private var session: VTDecompressionSession?
    private var hevc = true
    private var vps: [Data] = []
    private var sps: [Data] = []
    private var pps: [Data] = []

    public init() {}

    public func push(_ packet: Data) {
        guard let media = Datagram.media(packet) else { return }
        let nals = AnnexB.split(media.payload)
        if nals.isEmpty { return }
        if media.kind == 0 || format == nil {
            absorb(nals)
        }
        guard format != nil, media.kind == 1 else { return }
        guard let sample = sample(from: nals, pts: media.pts) else { return }
        onEncoded?(sample)
        decode(sample)
    }

    public func stop() {
        if let session { VTDecompressionSessionInvalidate(session) }
        session = nil
    }

    private func absorb(_ nals: [Data]) {
        for nal in nals {
            if let kind = AnnexB.hevcType(nal), kind == 32 || kind == 33 || kind == 34 {
                hevc = true
                if kind == 32 { vps = [nal] }
                if kind == 33 { sps = [nal] }
                if kind == 34 { pps = [nal] }
            } else if let kind = AnnexB.avcType(nal), kind == 7 || kind == 8 {
                hevc = false
                if kind == 7 { sps = [nal] }
                if kind == 8 { pps = [nal] }
            }
        }
        rebuild()
    }

    private func rebuild() {
        var desc: CMVideoFormatDescription?
        if hevc, !vps.isEmpty, !sps.isEmpty, !pps.isEmpty {
            createHEVC(vps + sps + pps, &desc)
        } else if !sps.isEmpty, !pps.isEmpty {
            hevc = false
            createAVC(sps + pps, &desc)
        }
        guard let desc else { return }
        format = desc
        if let session { VTDecompressionSessionInvalidate(session) }
        session = nil
        let dims = CMVideoFormatDescriptionGetDimensions(desc)
        width = dims.width
        height = dims.height
        var callback = VTDecompressionOutputCallbackRecord(
            decompressionOutputCallback: { refCon, _, status, _, image, pts, duration in
                guard status == noErr, let image, let refCon else { return }
                let decoder = Unmanaged<VideoDecoder>.fromOpaque(refCon).takeUnretainedValue()
                decoder.emit(image: image, pts: pts, duration: duration)
            },
            decompressionOutputRefCon: Unmanaged.passUnretained(self).toOpaque())
        var made: VTDecompressionSession?
        let status = VTDecompressionSessionCreate(allocator: nil, formatDescription: desc, decoderSpecification: nil, imageBufferAttributes: nil, outputCallback: &callback, decompressionSessionOut: &made)
        if status == noErr { session = made }
    }

    private func createHEVC(_ sets: [Data], _ desc: inout CMVideoFormatDescription?) {
        sets.withUnsafeBytesNals { pointers, sizes in
            _ = CMVideoFormatDescriptionCreateFromHEVCParameterSets(
                allocator: nil, parameterSetCount: sets.count,
                parameterSetPointers: pointers, parameterSetSizes: sizes,
                nalUnitHeaderLength: 4, extensions: nil, formatDescriptionOut: &desc)
        }
    }

    private func createAVC(_ sets: [Data], _ desc: inout CMVideoFormatDescription?) {
        sets.withUnsafeBytesNals { pointers, sizes in
            _ = CMVideoFormatDescriptionCreateFromH264ParameterSets(
                allocator: nil, parameterSetCount: sets.count,
                parameterSetPointers: pointers, parameterSetSizes: sizes,
                nalUnitHeaderLength: 4, formatDescriptionOut: &desc)
        }
    }

    private func sample(from nals: [Data], pts: UInt64) -> CMSampleBuffer? {
        guard let format else { return nil }
        let payload = AnnexB.lengthPrefixed(nals.filter { nal in
            if hevc, let t = AnnexB.hevcType(nal) { return t != 32 && t != 33 && t != 34 }
            if let t = AnnexB.avcType(nal) { return t != 7 && t != 8 }
            return true
        })
        guard !payload.isEmpty else { return nil }
        var block: CMBlockBuffer?
        if CMBlockBufferCreateWithMemoryBlock(allocator: nil, memoryBlock: nil, blockLength: payload.count, blockAllocator: nil, customBlockSource: nil, offsetToData: 0, dataLength: payload.count, flags: 0, blockBufferOut: &block) != noErr { return nil }
        guard let block else { return nil }
        payload.withUnsafeBytes { raw in
            _ = CMBlockBufferReplaceDataBytes(with: raw.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: payload.count)
        }
        var sample: CMSampleBuffer?
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: CMTime(value: CMTimeValue(pts / 1000), timescale: 1_000_000), decodeTimeStamp: .invalid)
        var size = payload.count
        if CMSampleBufferCreateReady(allocator: nil, dataBuffer: block, formatDescription: format, sampleCount: 1, sampleTimingEntryCount: 1, sampleTimingArray: &timing, sampleSizeEntryCount: 1, sampleSizeArray: &size, sampleBufferOut: &sample) != noErr {
            return nil
        }
        if let sample {
            if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true) as? [NSMutableDictionary], let first = attachments.first {
                first[kCMSampleAttachmentKey_DisplayImmediately as NSString] = true
            }
        }
        return sample
    }

    private func decode(_ sample: CMSampleBuffer) {
        guard let session else { return }
        let flags = VTDecodeFrameFlags(rawValue: 0)
        var info = VTDecodeInfoFlags()
        let status = VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample, flags: flags, frameRefcon: nil, infoFlagsOut: &info)
        if status != noErr { dropped += 1 }
    }

    private func emit(image: CVImageBuffer, pts: CMTime, duration: CMTime) {
        guard let format else { return }
        var timing = CMSampleTimingInfo(duration: duration, presentationTimeStamp: pts, decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        if CMSampleBufferCreateForImageBuffer(allocator: nil, imageBuffer: image, dataReady: true, makeDataReadyCallback: nil, refcon: nil, formatDescription: format, sampleTiming: &timing, sampleBufferOut: &sample) != noErr { return }
        guard let sample else { return }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true) as? [NSMutableDictionary], let first = attachments.first {
            first[kCMSampleAttachmentKey_DisplayImmediately as NSString] = true
        }
        onSample?(sample)
    }
}

private extension Array where Element == Data {
    func withUnsafeBytesNals(_ body: (UnsafePointer<UnsafePointer<UInt8>>, UnsafePointer<Int>) -> Void) {
        let backed = map { $0 as NSData }
        let pointers: [UnsafePointer<UInt8>] = backed.map { $0.bytes.assumingMemoryBound(to: UInt8.self) }
        let sizes = map(\.count)
        pointers.withUnsafeBufferPointer { pBuf in
            sizes.withUnsafeBufferPointer { sBuf in
                if let p = pBuf.baseAddress, let s = sBuf.baseAddress { body(p, s) }
            }
        }
    }
}
