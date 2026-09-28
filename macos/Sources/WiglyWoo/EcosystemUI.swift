import AppKit
import Foundation
import UserNotifications

/// Relay messages that are not mirroring: status, calls, SMS, OTP, hotspot.
enum EcosystemRouter {
    static func handle(_ type: String, _ message: [String: Any]) {
        switch type {
        case "phone_status":
            PhoneStatus.shared.apply(message)
        case "now_playing":
            NowPlayingBridge.shared.apply(message)
        case "otp":
            if let code = message["code"] as? String { showCode(code, source: message["source"] as? String ?? "Phone") }
        case "phone_recent":
            MirrorController.shared.setRecent(message["files"] as? [[String: Any]] ?? [])
        case "call":
            CallBanner.shared.show(message)
        case "sms":
            MessagesStore.shared.add(message)
        case "handoff":
            if message["direction"] as? String == "to-mac", let url = message["url"] as? String {
                HandoffBridge.openLocal(url)
            }
        case "hotspot_offer":
            let ssid = message["ssid"] as? String ?? ""
            Toaster.shared.show("Joining \(ssid)…")
            WifiJoiner.join(ssid: ssid, psk: message["psk"] as? String ?? "") { error in
                Toaster.shared.show(error ?? "Joined \(ssid)")
            }
        case "hotspot_error":
            let reason = message["reason"] as? String ?? ""
            Toaster.shared.show(reason == "shizuku"
                ? "Restart Shizuku on the phone to turn its hotspot on from here"
                : "The phone could not start its hotspot. Turn it on in the phone's settings.")
        case "unlock_result":
            if message["ok"] as? Bool != true {
                let reason = message["reason"] as? String ?? ""
                Toaster.shared.show(reason == "shizuku" ? "Restart Shizuku on the phone to unlock it from here"
                                    : reason.isEmpty ? "The phone did not unlock" : reason)
            }
        default:
            break
        }
    }

    private static func showCode(_ code: String, source: String) {
        let content = UNMutableNotificationContent()
        content.title = "Code from \(source)"
        content.body = code
        content.categoryIdentifier = "OTP_COPY"
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "otp-\(code)", content: content, trigger: nil))
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(code, forType: .string)
        Toaster.shared.show("Copied \(code)")
    }
}

final class MessagesStore: ObservableObject {
    static let shared = MessagesStore()
    struct Item: Identifiable {
        let id = UUID()
        let address: String
        let body: String
        let date: Date
    }
    @Published var items: [Item] = []

    func add(_ message: [String: Any]) {
        let date = Date(timeIntervalSince1970: ((message["date"] as? NSNumber)?.doubleValue ?? 0) / 1000)
        items.insert(Item(address: message["address"] as? String ?? "", body: message["body"] as? String ?? "", date: date), at: 0)
        items = Array(items.prefix(100))
    }

    func reply(to address: String, body: String) {
        CompanionBridge.shared.send(["type": "sms_send", "address": address, "body": body])
    }
}

final class CallBanner {
    static let shared = CallBanner()
    private var panel: NSPanel?

    func show(_ message: [String: Any]) {
        let state = message["state"] as? String ?? "idle"
        if state == "idle" { panel?.close(); panel = nil; return }
        let number = (message["number"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? CompanionConfig.shared.phoneName
        DispatchQueue.main.async {
            let panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 320, height: 120),
                                styleMask: [.titled, .nonactivatingPanel], backing: .buffered, defer: false)
            panel.title = state == "ringing" ? "Incoming call" : "Call"
            panel.level = .floating
            let view = NSView(frame: panel.contentView!.bounds)
            let label = NSTextField(labelWithString: number)
            label.frame = NSRect(x: 16, y: 64, width: 280, height: 24)
            view.addSubview(label)
            if state == "ringing" {
                let answer = NSButton(title: "Answer", target: self, action: #selector(self.answer))
                answer.frame = NSRect(x: 16, y: 16, width: 120, height: 32)
                let decline = NSButton(title: "Decline", target: self, action: #selector(self.decline))
                decline.frame = NSRect(x: 150, y: 16, width: 120, height: 32)
                view.addSubview(answer)
                view.addSubview(decline)
            }
            panel.contentView = view
            panel.center()
            panel.orderFrontRegardless()
            self.panel = panel
        }
    }

    @objc private func answer() { CompanionBridge.shared.send(["type": "call_answer"]); panel?.close() }
    @objc private func decline() { CompanionBridge.shared.send(["type": "call_decline"]); panel?.close() }
}
