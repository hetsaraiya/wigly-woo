import AppKit
import Foundation

/// A phone-as-display would use the private CGVirtualDisplay class. It is not
/// enabled: the symbol changes between macOS releases, and a 6.5-inch panel
/// is a poor second screen. This checks for the class and stops there.
enum Sidecar {
    static var available: Bool { NSClassFromString("CGVirtualDisplay") != nil }

    static func explain() -> String {
        if available {
            return "A virtual display class is present on this Mac. Wigly Woo does not use it."
        }
        return "This Mac does not expose a virtual display Wigly Woo can drive."
    }
}
