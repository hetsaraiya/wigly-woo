import AppKit
import Foundation

/// Turns Mac pointer and keyboard events into phone control datagrams.
public struct InputMapper {
    public var size: CGSize
    public init(size: CGSize) { self.size = size }

    /// `point` is in the mirror view's coordinates, origin bottom-left.
    public func mouse(_ event: NSEvent, at point: CGPoint) -> Data? {
        let x = ControlCodec.norm(point.x, span: size.width)
        let y = ControlCodec.norm(size.height - point.y, span: size.height)
        switch event.type {
        case .rightMouseDown:
            return ControlCodec.button(ControlCodec.back)
        case .otherMouseDown:
            return ControlCodec.button(ControlCodec.home)
        case .scrollWheel:
            // A wheel reports lines, a trackpad points. The phone divides by 16.
            let scale: CGFloat = event.hasPreciseScrollingDeltas ? 1 : 16
            return ControlCodec.scroll(x: x, y: y, dx: clamp(event.scrollingDeltaX * scale), dy: clamp(event.scrollingDeltaY * scale))
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

    /// Keys with a meaning of their own (arrows, Return, shortcuts) go as key
    /// codes. Printable text goes as text, so punctuation and other layouts
    /// arrive as typed. ⌘V pastes the Mac clipboard on the phone.
    public static func key(_ event: NSEvent, pasteboard: () -> String? = { nil }) -> [Data] {
        let flags = event.modifierFlags
        let down = event.type == .keyDown
        let action: UInt8 = down ? 0 : 1
        if flags.contains(.command) {
            guard down, !event.isARepeat else { return [] }
            switch event.charactersIgnoringModifiers?.lowercased() {
            case "1": return [ControlCodec.button(ControlCodec.home)]
            case "2": return [ControlCodec.button(ControlCodec.recents)]
            case "v":
                guard let text = pasteboard(), !text.isEmpty else { return [] }
                return [ControlCodec.clipboard(text)] + tap(paste)
            case "c": return tap(copy)
            case "x": return tap(cut)
            case "a": return tap(29, meta: metaCtrl)
            default: return []
            }
        }
        var meta: UInt32 = 0
        if flags.contains(.shift) { meta |= metaShift }
        if flags.contains(.control) { meta |= metaCtrl }
        if flags.contains(.option) { meta |= metaAlt }
        if let code = special[event.keyCode] {
            return [ControlCodec.key(action: action, code: code, meta: meta)]
        }
        if flags.contains(.control), let code = letters[event.keyCode] {
            return [ControlCodec.key(action: action, code: code, meta: meta)]
        }
        guard down, let text = event.characters, !text.isEmpty,
              text.unicodeScalars.allSatisfy({ $0.value >= 0x20 && $0.value != 0x7f && !(0xF700...0xF8FF).contains($0.value) })
        else { return [] }
        return [ControlCodec.text(text)]
    }

    private static func tap(_ code: UInt32, meta: UInt32 = 0) -> [Data] {
        [ControlCodec.key(action: 0, code: code, meta: meta), ControlCodec.key(action: 1, code: code, meta: meta)]
    }

    private func clamp(_ v: CGFloat) -> Int16 { Int16(max(-32000, min(32000, v.rounded()))) }

    // Android KeyEvent meta states and key codes.
    static let metaShift: UInt32 = 0x1
    static let metaAlt: UInt32 = 0x2
    static let metaCtrl: UInt32 = 0x1000
    static let paste: UInt32 = 279
    static let copy: UInt32 = 278
    static let cut: UInt32 = 277

    /// macOS virtual key code to Android key code, for keys that are not text.
    static let special: [UInt16: UInt32] = [
        36: 66, 76: 66,          // Return, keypad Enter
        51: 67, 117: 112,        // Delete, Forward Delete
        48: 61, 53: 111,         // Tab, Escape
        123: 21, 124: 22, 126: 19, 125: 20, // arrows
        115: 122, 119: 123, 116: 92, 121: 93, // Home, End, Page Up, Page Down
    ]

    /// Letters by ANSI position, for Control shortcuts.
    static let letters: [UInt16: UInt32] = [
        0: 29, 11: 30, 8: 31, 2: 32, 14: 33, 3: 34, 5: 35, 4: 36, 34: 37, 38: 38,
        40: 39, 37: 40, 46: 41, 45: 42, 31: 43, 35: 44, 12: 45, 15: 46, 1: 47, 17: 48,
        32: 49, 9: 50, 13: 51, 7: 52, 16: 53, 6: 54,
    ]
}
