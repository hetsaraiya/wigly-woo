import CoreGraphics
import Foundation

/// Control datagrams. The layout matches core/session/control.go.
public enum ControlCodec {
    public static let touch: UInt8 = 1
    public static let scroll: UInt8 = 2
    public static let key: UInt8 = 3
    public static let text: UInt8 = 4
    public static let button: UInt8 = 5
    public static let clipboard: UInt8 = 6
    public static let displayPower: UInt8 = 8
    public static let launch: UInt8 = 9
    public static let pointer: UInt8 = 11

    public static let back: UInt8 = 1
    public static let home: UInt8 = 2
    public static let recents: UInt8 = 3
    public static let notifications: UInt8 = 4
    public static let settings: UInt8 = 5
    public static let power: UInt8 = 6
    public static let wake: UInt8 = 7
    public static let sleep: UInt8 = 8

    public static let pointerMove: UInt8 = 0
    public static let pointerDown: UInt8 = 1
    public static let pointerUp: UInt8 = 2

    public static let flagScreenOff: UInt8 = 1
    public static let flagAudioOnly: UInt8 = 2
    public static let flagHeadless: UInt8 = 4
    public static let flagAppDisplay: UInt8 = 8

    public static func touch(action: UInt8, pointer: UInt8, x: UInt16, y: UInt16, pressure: UInt16 = 65535) -> Data {
        var d = Data([Self.touch, action, pointer])
        d.append(u16(x)); d.append(u16(y)); d.append(u16(pressure))
        return d
    }

    public static func scroll(x: UInt16, y: UInt16, dx: Int16, dy: Int16) -> Data {
        var d = Data([Self.scroll])
        d.append(u16(x)); d.append(u16(y)); d.append(i16(dx)); d.append(i16(dy))
        return d
    }

    public static func key(action: UInt8, code: UInt32, meta: UInt32) -> Data {
        var d = Data([Self.key, action])
        d.append(u32(code)); d.append(u32(meta))
        return d
    }

    public static func button(_ id: UInt8) -> Data { Data([Self.button, id]) }

    public static func text(_ string: String) -> Data { Data([Self.text]) + Data(string.utf8) }

    public static func clipboard(_ string: String) -> Data { Data([Self.clipboard]) + Data(string.utf8) }

    public static func launch(_ component: String) -> Data { Data([Self.launch]) + Data(component.utf8) }

    public static func displayPower(on: Bool) -> Data { Data([Self.displayPower, on ? 1 : 0]) }


    public static func pointer(action: UInt8, buttons: UInt8, x: UInt16, y: UInt16, dx: Int16 = 0, dy: Int16 = 0) -> Data {
        var d = Data([Self.pointer, action, buttons])
        d.append(u16(x)); d.append(u16(y)); d.append(i16(dx)); d.append(i16(dy))
        return d
    }

    public static func norm(_ value: CGFloat, span: CGFloat) -> UInt16 {
        guard span > 0 else { return 0 }
        let n = min(1, max(0, value / span))
        return UInt16(n * 65535)
    }

    private static func u16(_ v: UInt16) -> Data { Data([UInt8(v >> 8), UInt8(v & 0xff)]) }
    private static func i16(_ v: Int16) -> Data { u16(UInt16(bitPattern: v)) }
    private static func u32(_ v: UInt32) -> Data {
        Data([UInt8((v >> 24) & 0xff), UInt8((v >> 16) & 0xff), UInt8((v >> 8) & 0xff), UInt8(v & 0xff)])
    }
}
