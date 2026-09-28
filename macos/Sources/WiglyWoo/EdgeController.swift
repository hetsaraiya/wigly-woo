import AppKit
import CoreGraphics
import Foundation
import WiglyMirror

/// Universal Control: push the pointer past the chosen screen edge and it
/// drives the phone; push back the other way to return. Off by default. It
/// needs Accessibility, which only sticks with a stable Developer ID signature.
final class EdgeController {
    static let shared = EdgeController()

    static var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: "edge.enabled") }
        set { UserDefaults.standard.set(newValue, forKey: "edge.enabled"); shared.apply() }
    }

    private var tap: CFMachPort?
    private var source: CFRunLoopSource?
    private var onPhone = false
    /// Pointer position on the phone, in `span` units.
    private var position = CGPoint.zero
    private var buttons: UInt8 = 0
    private let span = CGSize(width: 1080, height: 2400)
    /// Phone units moved per Mac point.
    private let gain: CGFloat = 2.5

    func apply() {
        if Self.enabled { install() } else { uninstall() }
    }

    /// The session ended, or the feature was turned off: give the pointer back.
    func release() {
        guard onPhone else { return }
        onPhone = false
        buttons = 0
        CGAssociateMouseAndMouseCursorPosition(1)
        CGDisplayShowCursor(CGMainDisplayID())
    }

    private func install() {
        guard tap == nil else { return }
        let types: [CGEventType] = [.mouseMoved, .leftMouseDown, .leftMouseUp, .leftMouseDragged,
                                    .rightMouseDown, .rightMouseUp, .scrollWheel, .keyDown, .keyUp]
        let mask = types.reduce(CGEventMask(0)) { $0 | CGEventMask(1) << $1.rawValue }
        tap = CGEvent.tapCreate(tap: .cgSessionEventTap, place: .headInsertEventTap, options: .defaultTap,
                                eventsOfInterest: mask, callback: { _, type, event, _ in
            EdgeController.shared.handle(type: type, event: event)
        }, userInfo: nil)
        guard let tap else {
            Toaster.shared.show("Allow Wigly Woo in Privacy & Security › Accessibility, then turn this on again")
            UserDefaults.standard.set(false, forKey: "edge.enabled")
            return
        }
        source = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0)
        CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
        CGEvent.tapEnable(tap: tap, enable: true)
    }

    private func uninstall() {
        release()
        if let source { CFRunLoopRemoveSource(CFRunLoopGetMain(), source, .commonModes) }
        if let tap { CGEvent.tapEnable(tap: tap, enable: false) }
        tap = nil
        source = nil
    }

    private var edgeIsRight: Bool { UserDefaults.standard.string(forKey: "mirror.edge") != "left" }

    /// Returns nil to swallow the event while the phone has the pointer.
    private func handle(type: CGEventType, event: CGEvent) -> Unmanaged<CGEvent>? {
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let tap { CGEvent.tapEnable(tap: tap, enable: true) }
            return Unmanaged.passUnretained(event)
        }
        if !onPhone {
            if type == .mouseMoved, atEdge(event.location), CompanionBridge.shared.state == .connected { enter(event.location) }
            return Unmanaged.passUnretained(event)
        }
        switch type {
        case .mouseMoved, .leftMouseDragged:
            position.x += CGFloat(event.getDoubleValueField(.mouseEventDeltaX)) * gain * (edgeIsRight ? 1 : -1)
            position.y += CGFloat(event.getDoubleValueField(.mouseEventDeltaY)) * gain
            position.y = min(max(position.y, 0), span.height)
            if position.x < 0 {
                leave()
                return nil
            }
            position.x = min(position.x, span.width)
            send(action: ControlCodec.pointerMove)
        case .leftMouseDown:
            buttons = 1
            send(action: ControlCodec.pointerDown)
        case .leftMouseUp:
            send(action: ControlCodec.pointerUp)
            buttons = 0
        case .rightMouseDown:
            MirrorController.shared.button(ControlCodec.back)
        case .scrollWheel:
            let dy = CGFloat(event.getDoubleValueField(.scrollWheelEventPointDeltaAxis1))
            let dx = CGFloat(event.getDoubleValueField(.scrollWheelEventPointDeltaAxis2))
            MirrorController.shared.sendControl(ControlCodec.scroll(
                x: ControlCodec.norm(phoneX, span: span.width), y: ControlCodec.norm(position.y, span: span.height),
                dx: Int16(clamping: Int(dx)), dy: Int16(clamping: Int(dy))))
        case .keyDown, .keyUp:
            if let key = NSEvent(cgEvent: event) {
                InputMapper.key(key, pasteboard: { NSPasteboard.general.string(forType: .string) })
                    .forEach { MirrorController.shared.sendControl($0) }
            }
        default:
            break
        }
        return nil
    }

    private func atEdge(_ loc: CGPoint) -> Bool {
        // CGEvent locations are in global display space, origin top-left.
        let bounds = CGDisplayBounds(CGMainDisplayID())
        return edgeIsRight ? loc.x >= bounds.maxX - 1 : loc.x <= bounds.minX
    }

    /// x on the phone, measured from its left edge.
    private var phoneX: CGFloat { edgeIsRight ? position.x : span.width - position.x }

    private func enter(_ loc: CGPoint) {
        let bounds = CGDisplayBounds(CGMainDisplayID())
        onPhone = true
        position = CGPoint(x: 0, y: (loc.y - bounds.minY) / max(bounds.height, 1) * span.height)
        CGAssociateMouseAndMouseCursorPosition(0)
        CGDisplayHideCursor(CGMainDisplayID())
        MirrorController.shared.startHeadless()
        send(action: ControlCodec.pointerMove)
    }

    private func leave() {
        let bounds = CGDisplayBounds(CGMainDisplayID())
        let y = bounds.minY + position.y / span.height * bounds.height
        release()
        CGWarpMouseCursorPosition(CGPoint(x: edgeIsRight ? bounds.maxX - 24 : bounds.minX + 24, y: y))
    }

    private func send(action: UInt8) {
        MirrorController.shared.sendControl(ControlCodec.pointer(
            action: action, buttons: buttons,
            x: ControlCodec.norm(phoneX, span: span.width),
            y: ControlCodec.norm(position.y, span: span.height)))
    }
}
