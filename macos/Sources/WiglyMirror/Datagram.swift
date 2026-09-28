import Darwin
import Foundation

/// Session sockets are streams of records: length u32be, then payload
/// (core/session/fd_unix.go). A record holds one control message, one media
/// packet, or one JSON meta message.
public enum Datagram {
    /// Writes one record, finishing partial writes.
    public static func write(_ fd: Int32, _ data: Data) {
        guard fd >= 0 else { return }
        var record = Data(count: 4)
        let n = UInt32(data.count).bigEndian
        withUnsafeBytes(of: n) { record.replaceSubrange(0..<4, with: $0) }
        record.append(data)
        record.withUnsafeBytes { raw in
            guard var base = raw.baseAddress else { return }
            var left = raw.count
            while left > 0 {
                let written = Darwin.write(fd, base, left)
                if written < 0 {
                    if errno == EINTR || errno == EAGAIN { continue }
                    return
                }
                left -= written
                base += written
            }
        }
    }

    /// Media header: kind, flags, pts nanoseconds. Matches core/session/media.go.
    /// The payload is a fresh Data, so its indices start at 0.
    public static func media(_ data: Data) -> (kind: UInt8, flags: UInt8, pts: UInt64, payload: Data)? {
        guard data.count >= 10 else { return nil }
        let bytes = [UInt8](data)
        var pts: UInt64 = 0
        for b in bytes[2..<10] { pts = pts << 8 | UInt64(b) }
        return (bytes[0], bytes[1], pts, Data(bytes[10...]))
    }
}

/// Reassembles records from a session socket. The core gives each socket a
/// short receive timeout, so `next()` returns `.idle` periodically and the
/// caller can check whether to stop.
public final class DatagramReader {
    public let fd: Int32
    private var buffer: [UInt8]
    private var length = 0
    private static let maxRecord = 8 << 20

    public init(fd: Int32, capacity: Int = 256 << 10) {
        self.fd = fd
        buffer = [UInt8](repeating: 0, count: capacity)
    }

    public enum Result { case data(Data), idle, closed }

    public func next() -> Result {
        while true {
            switch take() {
            case .some(.data(let d)): return .data(d)
            case .some(let other): return other
            case .none: break
            }
            if length == buffer.count { buffer.append(contentsOf: [UInt8](repeating: 0, count: buffer.count)) }
            let n = buffer.withUnsafeMutableBytes { raw in
                Darwin.read(fd, raw.baseAddress! + length, raw.count - length)
            }
            if n > 0 { length += n; continue }
            if n == 0 { return .closed }
            return errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR ? .idle : .closed
        }
    }

    /// A complete record if one is buffered, `.closed` for a corrupt stream.
    private func take() -> Result? {
        guard length >= 4 else { return nil }
        let size = Int(buffer[0]) << 24 | Int(buffer[1]) << 16 | Int(buffer[2]) << 8 | Int(buffer[3])
        if size > Self.maxRecord { return .closed }
        guard length >= 4 + size else {
            if buffer.count < 4 + size { buffer.append(contentsOf: [UInt8](repeating: 0, count: 4 + size - buffer.count)) }
            return nil
        }
        let record = Data(buffer[4..<(4 + size)])
        buffer.replaceSubrange(0..<(length - 4 - size), with: buffer[(4 + size)..<length])
        length -= 4 + size
        return .data(record)
    }
}
