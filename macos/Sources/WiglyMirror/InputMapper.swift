import AppKit
import Foundation

/// Turns Mac pointer and keyboard events into phone control datagrams.
public struct InputMapper {
    public var size: CGSize
    public init(size: CGSize) { self.size = size }

    public func mouse(event: NSEvent) -> Data? {
        let point = event.locationInWindow
        let x = ControlCodec.norm(point.x, span: size.width)
        let y = ControlCodec.norm(size.height - point.y, span: size.height)
        switch event.type {
        case .rightMouseDown:
            return ControlCodec.button(ControlCodec.back)
        case .otherMouseDown where event.buttonNumber == 2:
            return ControlCodec.button(ControlCodec.home)
        case .scrollWheel:
            let dy = Int16(max(-8, min(8, event.scrollingDeltaY)))
            let dx = Int16(max(-8, min(8, event.scrollingDeltaX)))
            return ControlCodec.scroll(x: x, y: y, dx: dx, dy: dy)
        case .leftMouseDown:
            return ControlCodec.touch(action: 0, pointer: 0, x: x, y: y)
        case .leftMouseDragged:
            return ControlCodec.touch(action: 1, pointer: 0, x: x, y: y)
        case .leftMouseUp:
            return ControlCodec.touch(action: 2, pointer: 0, x: x, y: y)
        default:
            return nil
        }
    }

    /// Headless pointer for universal control. Coordinates are already in phone pixels.
    public func pointer(action: UInt8, buttons: UInt8, x: CGFloat, y: CGFloat, span: CGSize) -> Data {
        ControlCodec.pointer(
            action: action, buttons: buttons,
            x: ControlCodec.norm(x, span: span.width),
            y: ControlCodec.norm(y, span: span.height))
    }

    public func key(event: NSEvent) -> [Data] {
        let flags = event.modifierFlags
        if flags.contains(.command) && event.type == .keyDown && !event.isARepeat {
            if event.charactersIgnoringModifiers == "1" { return [ControlCodec.button(ControlCodec.home)] }
            if event.charactersIgnoringModifiers == "2" { return [ControlCodec.button(ControlCodec.recents)] }
            if event.charactersIgnoringModifiers == "v" { return [] } // clipboard is applied by the session, not as a key
        }
        let down: UInt8 = event.type == .keyUp ? 1 : 0
        if let code = Self.androidCode(event.keyCode) {
            var meta: UInt32 = 0
            if flags.contains(.shift) { meta |= 1 }
            if flags.contains(.control) { meta |= 1 << 12 }
            if flags.contains(.option) { meta |= 1 << 16 }
            return [ControlCodec.key(action: down, code: code, meta: meta)]
        }
        if event.type == .keyDown, let text = event.characters, !text.isEmpty, text.unicodeScalars.allSatisfy({ $0.value > 127 || !($0.value == 127) }) {
            if text.unicodeScalars.contains(where: { $0.value > 127 }) {
                return [ControlCodec.text(text)]
            }
        }
        return []
    }

    /// macOS virtual keycode to Android keycode. Letters follow the ANSI layout.
    static func androidCode(_ keyCode: UInt16) -> UInt32? {
        if let code = codes[keyCode] { return code }
        return nil
    }

    private static let codes: [UInt16: UInt32] = [
        0: 29, 11: 30, 8: 31, 2: 32, 14: 33, 3: 34, 5: 35, 4: 36, 34: 37, 38: 38,
        40: 39, 37: 40, 46: 41, 45: 42, 31: 43, 35: 44, 12: 45, 15: 46, 1: 47, 17: 48,
        32: 49, 9: 50, 13: 51, 7: 52, 16: 53, 6: 54,
        29: 7, 18: 8, 19: 9, 20: 10, 21: 11, 23: 12, 22: 13, 26: 14, 28: 15, 25: 16,
        36: 66, 51: 67, 48: 61, 49: 62, 53: 111,
        123: 21, 124: 22, 126: 19, 125: 20,
        115: 122, 119: 123, 116: 124, 121: 93,
    ]
}
