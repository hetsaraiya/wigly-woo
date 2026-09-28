import Darwin
import Foundation

public enum Datagram {
    public static func read(_ fd: Int32, max: Int = 2 << 20) -> Data? {
        var buf = [UInt8](repeating: 0, count: max)
        let n = Darwin.read(fd, &buf, buf.count)
        if n <= 0 { return nil }
        return Data(buf.prefix(n))
    }

    public static func write(_ fd: Int32, _ data: Data) {
        data.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { return }
            _ = Darwin.write(fd, base, raw.count)
        }
    }

    /// Media header: kind, flags, pts nanoseconds. Matches core/session/media.go.
    public static func media(_ data: Data) -> (kind: UInt8, flags: UInt8, pts: UInt64, payload: Data)? {
        guard data.count >= 10 else { return nil }
        let pts = data.subdata(in: 2..<10).withUnsafeBytes { $0.load(as: UInt64.self).bigEndian }
        return (data[0], data[1], pts, data.dropFirst(10))
    }
}
