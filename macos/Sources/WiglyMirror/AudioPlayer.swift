import AVFoundation
import Foundation

/// Plays phone audio. The converter is best-effort: a packet the converter
/// refuses is dropped so the jitter buffer cannot grow.
public final class AudioPlayer {
    public private(set) var playing = false
    public private(set) var available = false
    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private let output: AVAudioFormat
    private var input: AVAudioFormat?
    private var converter: AVAudioConverter?
    private var cookie = Data()

    public init() {
        output = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 2, interleaved: false)!
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: output)
    }

    public func start() {
        guard !playing else { return }
        try? engine.start()
        node.play()
        playing = true
    }

    public func stop() {
        node.stop()
        engine.stop()
        playing = false
    }

    public func push(_ packet: Data) {
        guard let media = Datagram.media(packet) else { return }
        if media.kind == 0 {
            cookie = Data(media.payload)
            rebuild()
            return
        }
        guard let converter, let compressed = compressedBuffer(Data(media.payload)) else { return }
        guard let pcm = AVAudioPCMBuffer(pcmFormat: output, frameCapacity: 4096) else { return }
        var error: NSError?
        var handed = false
        converter.convert(to: pcm, error: &error) { _, status in
            if handed {
                status.pointee = .noDataNow
                return nil
            }
            handed = true
            status.pointee = .haveData
            return compressed
        }
        guard error == nil, pcm.frameLength > 0 else { return }
        node.scheduleBuffer(pcm, completionHandler: nil)
    }

    private func rebuild() {
        var asbd = AudioStreamBasicDescription(
            mSampleRate: 48_000,
            mFormatID: kAudioFormatMPEG4AAC,
            mFormatFlags: 0,
            mBytesPerPacket: 0,
            mFramesPerPacket: 1024,
            mBytesPerFrame: 0,
            mChannelsPerFrame: 2,
            mBitsPerChannel: 0,
            mReserved: 0)
        guard let format = AVAudioFormat(streamDescription: &asbd) else { return }
        input = format
        converter = AVAudioConverter(from: format, to: output)
        if !cookie.isEmpty { converter?.magicCookie = cookie }
        available = converter != nil
    }

    private func compressedBuffer(_ packet: Data) -> AVAudioCompressedBuffer? {
        guard let input else { return nil }
        let buffer = AVAudioCompressedBuffer(format: input, packetCapacity: 1, maximumPacketSize: packet.count)
        buffer.packetCount = 1
        buffer.byteLength = UInt32(packet.count)
        packet.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { return }
            buffer.data.initializeMemory(as: UInt8.self, from: base.assumingMemoryBound(to: UInt8.self), count: packet.count)
        }
        buffer.packetDescriptions?.pointee = AudioStreamPacketDescription(mStartOffset: 0, mVariableFramesInPacket: 0, mDataByteSize: UInt32(packet.count))
        return buffer
    }
}
