import Foundation
import AppKit
import CWooCore
import UserNotifications

enum CompanionStatus { case notSet, paused, connecting, connected, error, offline }

final class CompanionBridge: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    static let shared = CompanionBridge()

    @Published private(set) var state: SupabaseRealtimeClient.State = .off
    @Published private(set) var lastActivity = "No activity yet."
    @Published private(set) var notificationsAuthorized = false
    /// Bumped whenever the phone announces itself; the pairing dialog waits on it.
    @Published private(set) var helloCount = 0
    @Published private(set) var connectedSince: Date?

    var status: CompanionStatus {
        if !config.isComplete { return .notSet }
        if !config.enabled { return .paused }
        switch state {
        case .connected: return .connected
        case .connecting: return .connecting
        case .error: return .error
        case .off: return .offline
        }
    }

    private let config = CompanionConfig.shared
    private var client: SupabaseRealtimeClient?
    private var clipboardTimer: Timer?
    private var pasteboardChangeCount = NSPasteboard.general.changeCount
    private var pendingClipboardID: String?
    private var pendingClipboardText: String?
    private var pendingClipboardLastSent = Date.distantPast
    private var pendingClipboardRetryInterval: TimeInterval = 2
    private var receivedClipboardIDs: [String] = []
    private var shownCallNotificationKeys = Set<String>()
    private var napActivity: NSObjectProtocol?

    private override init() {
        super.init()
        configureNotifications()
        startClipboardWatcher()
    }

    func start() {
        refreshNotificationAuthorization()
        reconfigure()
    }

    func reconfigure() {
        refreshNotificationAuthorization()
        client?.disconnect()
        client = nil
        guard config.enabled, config.isComplete else {
            state = .off
            connectedSince = nil
            stopNapProtection()
            return
        }
        startNapProtection()
        let realtime = SupabaseRealtimeClient(config: config)
        realtime.onState = { [weak self] newState in
            guard let self else { return }
            self.state = newState
            if newState == .connected {
                // Flush anything that was pending across the reconnect.
                self.pendingClipboardRetryInterval = 2
                self.pendingClipboardLastSent = .distantPast
                if self.connectedSince == nil { self.connectedSince = Date() }
                self.sendHello(reply: false)
            } else if newState != .connecting {
                self.connectedSince = nil
            }
        }
        realtime.onEnvelope = { [weak self] envelope in self?.receive(envelope) }
        client = realtime
        realtime.connect()
    }

    func sendKeyboardText(_ text: String) {
        guard !text.isEmpty else { return }
        send(["type": "keyboard", "action": "insert", "text": text])
        lastActivity = "Sent text to \(config.phoneName)."
    }

    func sendBackspace() {
        send(["type": "keyboard", "action": "delete"])
        lastActivity = "Sent Backspace to \(config.phoneName)."
    }

    func sendEnter() {
        send(["type": "keyboard", "action": "enter"])
        lastActivity = "Sent Enter to \(config.phoneName)."
    }

    /// Pushes the current Mac clipboard now, from the menu bar extra.
    @discardableResult
    func sendClipboardNow() -> Bool {
        guard let text = NSPasteboard.general.string(forType: .string), !text.isEmpty else { return false }
        pendingClipboardID = UUID().uuidString
        pendingClipboardText = String(text.prefix(65_536))
        pendingClipboardRetryInterval = 2
        pendingClipboardLastSent = .distantPast
        sendPendingClipboard()
        return state == .connected
    }

    private func sendHello(reply: Bool) {
        send([
            "type": "hello",
            "name": Host.current().localizedName ?? "Mac",
            "kind": "mac",
            "reply": reply,
            "fingerprint": CoreBridge.shared.identity?.fingerprint ?? "",
            "session": CoreBridge.shared.sessionPort,
        ])
    }

    func send(_ message: [String: Any]) {
        guard state == .connected else { return }
        var value = message
        value["sentAt"] = Int(Date().timeIntervalSince1970 * 1000)
        if let envelope = try? CompanionCrypto.encrypt(value, secret: config.pairingSecret, sender: config.deviceID) {
            client?.broadcast(envelope)
        }
    }

    private func receive(_ envelope: [String: Any]) {
        guard envelope["sender"] as? String != config.deviceID,
              let message = try? CompanionCrypto.decrypt(envelope, secret: config.pairingSecret),
              let type = message["type"] as? String else { return }
        DispatchQueue.main.async {
            switch type {
            case "notification": self.showNotification(message)
            case "notification_removed":
                if let key = message["notificationKey"] as? String {
                    let wasCall = self.shownCallNotificationKeys.remove(key) != nil
                    if !wasCall {
                        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [self.notificationID(key)])
                    }
                }
            case "hello":
                if let name = message["name"] as? String, !name.isEmpty { self.config.rememberPeer(name) }
                if let fp = message["fingerprint"] as? String, !fp.isEmpty {
                    fp.withCString { woo_session_allow($0) }
                }
                self.helloCount += 1
                if message["reply"] as? Bool != true { self.sendHello(reply: true) }
            case "mirror_ready":
                MirrorController.shared.ready(message["mode"] as? String ?? "shizuku")
            case "mirror_error":
                MirrorController.shared.failed(message["reason"] as? String ?? "The phone could not mirror")
            case "clipboard": self.receiveClipboard(message)
            case "clipboard_ack": self.receiveClipboardAcknowledgement(message)
            default: break
            }
        }
    }

    private func configureNotifications() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        let reply = UNTextInputNotificationAction(
            identifier: "REPLY", title: "Reply", options: [],
            textInputButtonTitle: "Send", textInputPlaceholder: "Type a reply…")
        let dismiss = UNNotificationAction(identifier: "DISMISS", title: "Dismiss", options: [])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: "ANDROID_REPLY", actions: [reply, dismiss], intentIdentifiers: []),
            UNNotificationCategory(identifier: "ANDROID_BASIC", actions: [dismiss], intentIdentifiers: [])
        ])
        center.requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] _, _ in
            self?.refreshNotificationAuthorization()
        }
    }

    func refreshNotificationAuthorization() {
        UNUserNotificationCenter.current().getNotificationSettings { [weak self] settings in
            let allowed = settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
            DispatchQueue.main.async { self?.notificationsAuthorized = allowed }
        }
    }

    func openNotificationSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=com.wiglywoo.macos") {
            NSWorkspace.shared.open(url)
        }
    }

    func showTestNotification() {
        let content = UNMutableNotificationContent()
        content.title = "wigly-woo is ready"
        content.body = "Android notifications will appear here."
        content.sound = .default
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: "wigly-test-\(UUID().uuidString)", content: content, trigger: nil))
    }

    private func showNotification(_ message: [String: Any]) {
        guard config.notificationsEnabled, let key = message["notificationKey"] as? String else { return }
        let content = UNMutableNotificationContent()
        let app = message["app"] as? String ?? "Android"
        let title = message["title"] as? String ?? ""
        let packageName = (message["package"] as? String ?? "").lowercased()
        let androidCategory = message["category"] as? String ?? ""
        let callLike = androidCategory == "call" || app.lowercased() == "call" || app.lowercased() == "phone"
            || packageName.contains("dialer") || packageName.contains("incallui")
        if callLike {
            guard !shownCallNotificationKeys.contains(key) else { return }
            shownCallNotificationKeys.insert(key)
            let body = message["body"] as? String ?? ""
            let generic = Set(["", "call", "phone", "incoming call"])
            let candidates = [body, title].map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            let caller = candidates.first { !generic.contains($0.lowercased()) }
            content.title = caller.map { "Incoming call from \($0)" } ?? "Incoming Android call"
            let formatter = DateFormatter()
            formatter.timeStyle = .short
            let postedAt = (message["postedAt"] as? NSNumber).map {
                Date(timeIntervalSince1970: $0.doubleValue / 1_000)
            } ?? Date()
            content.body = "Received at \(formatter.string(from: postedAt))"
            content.sound = .default
            UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: notificationID(key), content: content, trigger: nil))
            lastActivity = content.title
            return
        }
        content.title = title.isEmpty ? app : title
        content.subtitle = title.isEmpty ? "From Android" : app
        content.body = message["body"] as? String ?? ""
        content.sound = .default
        content.categoryIdentifier = (message["canReply"] as? Bool == true) ? "ANDROID_REPLY" : "ANDROID_BASIC"
        content.userInfo = ["notificationKey": key]
        let request = UNNotificationRequest(identifier: notificationID(key), content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
        lastActivity = "Received a notification from \(app)."
    }

    private func notificationID(_ key: String) -> String { "android-\(CompanionCrypto.digest(key))" }

    private func startClipboardWatcher() {
        let timer = Timer(timeInterval: 0.8, repeats: true) { [weak self] _ in
            guard let self, self.config.enabled, self.config.clipboardEnabled else { return }
            if self.pendingClipboardID != nil,
               Date().timeIntervalSince(self.pendingClipboardLastSent) >= self.pendingClipboardRetryInterval {
                self.sendPendingClipboard()
            }
            let pasteboard = NSPasteboard.general
            guard pasteboard.changeCount != self.pasteboardChangeCount else { return }
            self.pasteboardChangeCount = pasteboard.changeCount
            guard let text = pasteboard.string(forType: .string), !text.isEmpty else { return }
            self.pendingClipboardID = UUID().uuidString
            self.pendingClipboardText = String(text.prefix(65_536))
            self.pendingClipboardRetryInterval = 2
            self.sendPendingClipboard()
            self.lastActivity = "Synced the Mac clipboard."
        }
        RunLoop.main.add(timer, forMode: .common)
        clipboardTimer = timer
    }

    private func sendPendingClipboard() {
        guard state == .connected, let id = pendingClipboardID, let text = pendingClipboardText else { return }
        pendingClipboardLastSent = Date()
        // Back off while unacknowledged so an offline phone doesn't get spammed.
        pendingClipboardRetryInterval = min(pendingClipboardRetryInterval * 1.5, 20)
        send(["type": "clipboard", "clipboardID": id, "text": text])
    }

    private func receiveClipboard(_ message: [String: Any]) {
        guard config.clipboardEnabled, let text = message["text"] as? String, !text.isEmpty else { return }
        let id = message["clipboardID"] as? String
        if let id, receivedClipboardIDs.contains(id) {
            // A retry of something already applied — just re-ack, never
            // overwrite whatever the user copied since.
            send(["type": "clipboard_ack", "clipboardID": id])
            return
        }
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        pasteboardChangeCount = pasteboard.changeCount
        if let id {
            receivedClipboardIDs.append(id)
            if receivedClipboardIDs.count > 32 { receivedClipboardIDs.removeFirst() }
            send(["type": "clipboard_ack", "clipboardID": id])
        }
        lastActivity = "Clipboard from \(config.phoneName) is ready to paste."
    }

    private func receiveClipboardAcknowledgement(_ message: [String: Any]) {
        guard let id = message["clipboardID"] as? String, id == pendingClipboardID else { return }
        pendingClipboardID = nil
        pendingClipboardText = nil
        pendingClipboardRetryInterval = 2
    }

    private func startNapProtection() {
        guard napActivity == nil else { return }
        napActivity = ProcessInfo.processInfo.beginActivity(
            options: [.userInitiatedAllowingIdleSystemSleep],
            reason: "Maintaining encrypted connection to Android")
    }

    private func stopNapProtection() {
        if let activity = napActivity {
            ProcessInfo.processInfo.endActivity(activity)
            napActivity = nil
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        defer { completionHandler() }
        guard let key = response.notification.request.content.userInfo["notificationKey"] as? String else { return }
        if response.actionIdentifier == "REPLY", let reply = response as? UNTextInputNotificationResponse {
            send(["type": "notification_reply", "notificationKey": key, "text": reply.userText])
            DispatchQueue.main.async { self.lastActivity = "Replied through \(self.config.phoneName)." }
        } else if response.actionIdentifier == "DISMISS" || response.actionIdentifier == UNNotificationDismissActionIdentifier {
            send(["type": "notification_dismiss", "notificationKey": key])
        }
    }
}
