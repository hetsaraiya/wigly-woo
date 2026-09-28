import AppKit
import CoreGraphics
import Foundation
import WiglyMirror

/// Slides the pointer off a screen edge onto the phone. Needs Accessibility
/// and Input Monitoring, which only stick with a stable Developer ID signature.
final class EdgeController {
    static let shared = EdgeController()
    private var tap: CFMachPort?
    private var onPhone = false
    private var edge = "right"

    func start() {
        guard tap == nil else { return }
        edge = UserDefaults.standard.string(forKey: "mirror.edge") ?? "right"
        let mask = CGEventMask(1 << CGEventType.mouseMoved.rawValue | 1 << CGEventType.leftMouseDown.rawValue | 1 << CGEventType.leftMouseUp.rawValue | 1 << CGEventType.leftMouseDragged.rawValue | 1 << CGEventType.keyDown.rawValue | 1 << CGEventType.keyUp.rawValue)
        tap = CGEvent.tapCreate(tap: .cgSessionEventTap, place: .headInsertEventTap, options: .defaultTap, eventsOfInterest: mask, callback: { _, type, event, _ in
            EdgeController.shared.handle(type: type, event: event)
            return Unmanaged.passUnretained(event)
        }, userInfo: nil)
        guard let tap else {
            Toaster.shared.show("Universal Control needs Accessibility access, and that grant resets on each unsigned update")
            return
        }
        let source = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0)
        CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
        CGEvent.tapEnable(tap: tap, enable: true)
    }

    private func handle(type: CGEventType, event: CGEvent) {
        guard let screen = NSScreen.main else { return }
        let loc = event.location
        let frame = screen.frame
        let atEdge = edge == "right" ? loc.x >= frame.maxX - 2 : loc.x <= frame.minX + 2
        if !onPhone && atEdge && (type == .mouseMoved) {
            onPhone = true
            CGDisplayHideCursor(CGMainDisplayID())
            MirrorController.shared.start(flags: ControlCodec.flagHeadless)
        }
        guard onPhone else { return }
        let span = CGSize(width: 1080, height: 2400)
        let x = max(0, min(span.width, (loc.x - frame.minX) / frame.width * span.width))
        let y = max(0, min(span.height, (frame.maxY - loc.y) / frame.height * span.height))
        let action: UInt8 = switch type {
        case .leftMouseDown: 1
        case .leftMouseUp: 2
        default: 0
        }
        MirrorController.shared.sendControl(InputMapper(size: span).pointer(action: action, buttons: type == .leftMouseDown ? 1 : 0, x: x, y: y, span: span))
        if type == .mouseMoved && (edge == "right" ? x < 8 : x > span.width - 8) {
            onPhone = false
            CGDisplayShowCursor(CGMainDisplayID())
        }
    }
}
