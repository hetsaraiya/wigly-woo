import CoreMedia
import Foundation
import os
import VideoToolbox

/// Decodes one HEVC or H.264 access unit at a time and asks the display to
/// show it immediately, so a late frame is not queued.
public final class VideoDecoder {
    public var onSample: ((CMSampleBuffer) -> Void)?
    public var onEncoded: ((CMSampleBuffer) -> Void)?
    /// The current stream format, which a passthrough recorder needs as its hint.
    public private(set) var format: CMVideoFormatDescription?
    private var session: VTDecompressionSession?
    private var hevc = true
    private var vps: [Data] = []
    private let log = Logger(subsystem: "com.wiglywoo.macos", category: "mirror")
    private var packets = 0, decoded = 0, failed = 0
    private var lastLog = Date()
    private var sps: [Data] = []
    private var pps: [Data] = []

    public init() {}

    public func push(_ packet: Data) {
        packets += 1
        if Date().timeIntervalSince(lastLog) > 5 {
            log.info("video: \(self.packets) packets, \(self.decoded) decoded, \(self.failed) failed, format \(self.format != nil)")
            packets = 0; decoded = 0; failed = 0
            lastLog = Date()
        }
        guard let media = Datagram.media(packet) else { return }
        let nals = AnnexB.split(media.payload)
        if nals.isEmpty { return }
        // Keyframes repeat the parameter sets, so a lost config packet is
        // recovered at the next keyframe.
        if media.kind == 0 || format == nil || media.flags & 1 != 0 {
            absorb(nals)
        }
        guard format != nil, media.kind == 1 else { return }
        guard let sample = sample(from: nals, pts: media.pts, keyframe: media.flags & 1 != 0) else { return }
        onEncoded?(sample)
        decode(sample)
    }

    public func stop() {
        if let session { VTDecompressionSessionInvalidate(session) }
        session = nil
    }

    /// Picks up parameter sets. The codec is decided by the presence of a VPS,
    /// because some HEVC slice headers also parse as H.264 SPS/PPS. The
    /// decoder is rebuilt only when the sets change.
    private func absorb(_ nals: [Data]) {
        let isHEVC = nals.contains { AnnexB.hevcType($0) == 32 } || (format != nil && hevc)
        var nextVPS = vps, nextSPS = sps, nextPPS = pps
        for nal in nals {
            if isHEVC, let kind = AnnexB.hevcType(nal) {
                if kind == 32 { nextVPS = [nal] }
                if kind == 33 { nextSPS = [nal] }
                if kind == 34 { nextPPS = [nal] }
            } else if !isHEVC, let kind = AnnexB.avcType(nal) {
                if kind == 7 { nextSPS = [nal] }
                if kind == 8 { nextPPS = [nal] }
            }
        }
        if session != nil, hevc == isHEVC, nextVPS == vps, nextSPS == sps, nextPPS == pps { return }
        hevc = isHEVC
        vps = isHEVC ? nextVPS : []
        sps = nextSPS
        pps = nextPPS
        rebuild()
    }

    private func rebuild() {
        var desc: CMVideoFormatDescription?
        if hevc, !vps.isEmpty, !sps.isEmpty, !pps.isEmpty {
            createHEVC(vps + sps + pps, &desc)
        } else if !hevc, !sps.isEmpty, !pps.isEmpty {
            createAVC(sps + pps, &desc)
        }
        guard let desc else { return }
        format = desc
        if let session { VTDecompressionSessionInvalidate(session) }
        session = nil
        var callback = VTDecompressionOutputCallbackRecord(
            decompressionOutputCallback: { refCon, _, status, _, image, pts, duration in
                guard status == noErr, let image, let refCon else { return }
                let decoder = Unmanaged<VideoDecoder>.fromOpaque(refCon).takeUnretainedValue()
                decoder.emit(image: image, pts: pts, duration: duration)
            },
            decompressionOutputRefCon: Unmanaged.passUnretained(self).toOpaque())
        var made: VTDecompressionSession?
        let status = VTDecompressionSessionCreate(allocator: nil, formatDescription: desc, decoderSpecification: nil, imageBufferAttributes: nil, outputCallback: &callback, decompressionSessionOut: &made)
        if status == noErr, let made {
            // Output each frame as soon as it decodes; never pace or hold one.
            VTSessionSetProperty(made, key: kVTDecompressionPropertyKey_RealTime, value: kCFBooleanTrue)
            session = made
        }
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

    private func sample(from nals: [Data], pts: UInt64, keyframe: Bool) -> CMSampleBuffer? {
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
                // Lets a recorder start its file on a keyframe.
                if !keyframe { first[kCMSampleAttachmentKey_NotSync as NSString] = true }
            }
        }
        return sample
    }

    private func decode(_ sample: CMSampleBuffer) {
        guard let session else { return }
        let flags: VTDecodeFrameFlags = [._1xRealTimePlayback]
        var info = VTDecodeInfoFlags()
        let status = VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample, flags: flags, frameRefcon: nil, infoFlagsOut: &info)
        if status == noErr { decoded += 1 } else { failed += 1 }
    }

    private func emit(image: CVImageBuffer, pts: CMTime, duration: CMTime) {
        // A decoded frame needs a format describing the image, not the
        // compressed stream; the stream's format is rejected here.
        var imageFormat: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: image, formatDescriptionOut: &imageFormat) == noErr,
              let imageFormat else { return }
        var timing = CMSampleTimingInfo(duration: duration, presentationTimeStamp: pts, decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        if CMSampleBufferCreateReadyWithImageBuffer(allocator: nil, imageBuffer: image, formatDescription: imageFormat,
                                                    sampleTiming: &timing, sampleBufferOut: &sample) != noErr { return }
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
