import Foundation

/// Splits a codec buffer into NAL units. Accepts Annex-B start codes and
/// 4-byte length prefixes, which is what MediaCodec emits.
public enum AnnexB {
    public static func split(_ data: Data) -> [Data] {
        if data.starts(with: [0, 0, 0, 1]) || data.starts(with: [0, 0, 1]) {
            return splitStartCodes(data)
        }
        return splitLengths(data)
    }

    public static func hevcType(_ nal: Data) -> Int? {
        let bytes = stripStart(nal)
        guard let b = bytes.first else { return nil }
        return Int((b >> 1) & 0x3f)
    }

    public static func avcType(_ nal: Data) -> Int? {
        let bytes = stripStart(nal)
        guard let b = bytes.first else { return nil }
        return Int(b & 0x1f)
    }

    public static func lengthPrefixed(_ nals: [Data]) -> Data {
        var out = Data()
        for nal in nals {
            let body = stripStart(nal)
            guard !body.isEmpty else { continue }
            var len = UInt32(body.count).bigEndian
            withUnsafeBytes(of: &len) { out.append(contentsOf: $0) }
            out.append(body)
        }
        return out
    }

    private static func splitStartCodes(_ data: Data) -> [Data] {
        let bytes = [UInt8](data)
        var marks: [Int] = []
        var i = 0
        while i + 3 < bytes.count {
            if bytes[i] == 0, bytes[i + 1] == 0, bytes[i + 2] == 1 {
                marks.append(i + 3); i += 3; continue
            }
            if i + 4 < bytes.count, bytes[i] == 0, bytes[i + 1] == 0, bytes[i + 2] == 0, bytes[i + 3] == 1 {
                marks.append(i + 4); i += 4; continue
            }
            i += 1
        }
        var nals: [Data] = []
        for (index, start) in marks.enumerated() {
            let end = index + 1 < marks.count ? startOfCode(bytes, before: marks[index + 1]) : bytes.count
            if end > start { nals.append(Data(bytes[start..<end])) }
        }
        return nals
    }

    private static func startOfCode(_ bytes: [UInt8], before: Int) -> Int {
        if before >= 4, bytes[before - 4] == 0, bytes[before - 3] == 0, bytes[before - 2] == 0 { return before - 4 }
        if before >= 3, bytes[before - 3] == 0, bytes[before - 2] == 0 { return before - 3 }
        return before
    }

    private static func splitLengths(_ data: Data) -> [Data] {
        let bytes = [UInt8](data) // index from 0 even when data is a slice
        var nals: [Data] = []
        var offset = 0
        while offset + 4 <= bytes.count {
            let len = Int(bytes[offset]) << 24 | Int(bytes[offset + 1]) << 16 | Int(bytes[offset + 2]) << 8 | Int(bytes[offset + 3])
            offset += 4
            guard len > 0, offset + len <= bytes.count else { break }
            nals.append(Data(bytes[offset..<(offset + len)]))
            offset += len
        }
        return nals
    }

    private static func stripStart(_ nal: Data) -> Data {
        if nal.starts(with: [0, 0, 0, 1]) { return nal.dropFirst(4) }
        if nal.starts(with: [0, 0, 1]) { return nal.dropFirst(3) }
        return nal
    }
}
