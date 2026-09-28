import AppKit
import Foundation

/// Reads the front browser tab on the Mac and offers it to the phone.
/// Automation permission is requested the first time.
enum HandoffBridge {
    static func frontURL() -> String? {
        let scripts = [
            "tell application \"Safari\" to get URL of front document",
            "tell application \"Google Chrome\" to get URL of active tab of front window",
            "tell application \"Arc\" to get URL of active tab of front window",
        ]
        for source in scripts {
            if let url = run(source), url.hasPrefix("http") { return url }
        }
        return nil
    }

    static func sendFrontTab() {
        guard let url = frontURL() else {
            Toaster.shared.show("No browser tab to hand off")
            return
        }
        CompanionBridge.shared.send(["type": "handoff", "url": url, "direction": "to-phone"])
        Toaster.shared.show("Opening on the phone")
    }

    static func openLocal(_ url: String) {
        guard let parsed = URL(string: url), parsed.scheme == "http" || parsed.scheme == "https" else { return }
        NSWorkspace.shared.open(parsed)
    }

    private static func run(_ source: String) -> String? {
        var error: NSDictionary?
        let script = NSAppleScript(source: source)
        let value = script?.executeAndReturnError(&error)
        if error != nil { return nil }
        return value?.stringValue
    }
}
