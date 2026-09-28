import AVFoundation
import Foundation

/// Plays phone audio: raw AAC-LC frames, 48 kHz stereo, as the phone's
/// AudioCapture sends them. A packet the converter refuses is dropped so the
/// jitter buffer cannot grow.
public final class AudioPlayer {
    public private(set) var playing = false
    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private let output: AVAudioFormat
    private var input: AVAudioFormat?
    private var converter: AVAudioConverter?
    /// Buffers scheduled but not yet played. Kept to a few AAC frames (~21 ms
    /// each) so audio cannot drift behind the picture.
    private var queued = 0
    private let queueLock = NSLock()
    private static let maxQueued = 3

    public init() {
        output = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 2, interleaved: false)!
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: output)
        rebuild()
    }

    public func start() {
        guard !playing else { return }
        try? engine.start()
        node.play()
        playing = true
    }

    public func stop() {
        node.stop()
        queueLock.lock(); queued = 0; queueLock.unlock()
        engine.stop()
        playing = false
    }

    public func push(_ packet: Data) {
        // Codec config (kind 0) only repeats what the fixed format already says.
        guard let media = Datagram.media(packet), media.kind == 1, !media.payload.isEmpty else { return }
        guard let converter, let compressed = compressedBuffer(media.payload) else { return }
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
        queueLock.lock()
        let full = queued >= Self.maxQueued
        if !full { queued += 1 }
        queueLock.unlock()
        if full { return } // late audio is dropped rather than played late
        node.scheduleBuffer(pcm) { [weak self] in
            guard let self else { return }
            self.queueLock.lock(); self.queued -= 1; self.queueLock.unlock()
        }
    }

    private func rebuild() {
        var asbd = AudioStreamBasicDescription(
            mSampleRate: 48_000,
            mFormatID: kAudioFormatMPEG4AAC,
            mFormatFlags: UInt32(MPEG4ObjectID.AAC_LC.rawValue),
            mBytesPerPacket: 0,
            mFramesPerPacket: 1024,
            mBytesPerFrame: 0,
            mChannelsPerFrame: 2,
            mBitsPerChannel: 0,
            mReserved: 0)
        guard let format = AVAudioFormat(streamDescription: &asbd) else { return }
        input = format
        converter = AVAudioConverter(from: format, to: output)
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
