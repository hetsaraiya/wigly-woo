import CWooCore
import Foundation
import Combine

// Decoded shapes of the JSON event envelopes (see woocore.h).
struct Peer: Identifiable, Decodable, Hashable {
    let id: String
    let name: String
    let addr: String
    let port: Int
    let fingerprint: String
}

struct Identity: Decodable {
    let name: String
    let fingerprint: String
    let session: Int

    enum CodingKeys: String, CodingKey { case name, fingerprint, session }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        fingerprint = try c.decode(String.self, forKey: .fingerprint)
        session = try c.decodeIfPresent(Int.self, forKey: .session) ?? 0
    }
}

struct TrustRequest: Identifiable {
    let id = UUID()
    let name: String
    let fingerprint: String
    let file: String
    let size: Int64
}

struct ActiveTransfer {
    let name: String
    let dir: String        // "send" | "recv"
    let peer: String
    let sent: Int64
    let total: Int64
    let speed: Double      // bytes/sec, smoothed
}

struct QueuedSend: Identifiable, Equatable {
    let id = UUID()
    let peer: Peer
    let url: URL
    var size: Int64 {
        (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? -1
    }
}

struct SentRecord: Codable, Hashable {
    let name: String
    let size: Int64
    let peer: String
    let date: Date
}

/// Short-lived confirmation line at the bottom of the window.
final class Toaster: ObservableObject {
    static let shared = Toaster()
    @Published private(set) var message: String?
    private var work: DispatchWorkItem?

    func show(_ text: String) {
        work?.cancel()
        message = text
        let item = DispatchWorkItem { [weak self] in self?.message = nil }
        work = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.6, execute: item)
    }
}

/// CoreBridge is the Swift face of the C ABI. It owns the single event callback
/// and republishes core events as @Published state for SwiftUI.
final class CoreBridge: ObservableObject {
    static let shared = CoreBridge()

    @Published var identity: Identity?
    @Published var peers: [Peer] = []
    @Published var active: ActiveTransfer?
    @Published var received: [URL] = []
    @Published var sessionTotal: Int64 = 0
    @Published var pendingTrust: TrustRequest?
    /// TCP port of this Mac's media session, from the core identity.
    @Published var sessionPort: Int = 0

    /// The discovered peer that is the paired phone, falling back to the only peer.
    var pairedPeer: Peer? {
        let fp = CompanionBridge.shared.phoneFingerprint
        return peers.first { !fp.isEmpty && $0.fingerprint == fp } ?? (peers.count == 1 ? peers.first : nil)
    }
    /// Files waiting to go out after the current send.
    @Published private(set) var queue: [QueuedSend] = []
    @Published private(set) var sent: [SentRecord] = []
    /// Trusted sender fingerprint -> device name. Trusted senders skip the prompt.
    @Published private(set) var trusted: [String: String] = [:]
    /// Received filename -> sender name, so the inbox can say who sent it.
    @Published private(set) var receivedFrom: [String: String] = [:]

    private var currentSend: QueuedSend?
    private var incomingPeer = ""
    private let defaults = UserDefaults.standard

    private(set) var saveDir = URL(fileURLWithPath: ".")

    // Speed tracking (EMA-smoothed, see updateSpeed).
    private var trackName = ""
    private var lastMs = 0.0
    private var lastSent: Int64 = 0
    private var ema = 0.0

    private init() {
        if let data = defaults.data(forKey: "history.sent"),
           let list = try? JSONDecoder().decode([SentRecord].self, from: data) { sent = list }
        trusted = defaults.dictionary(forKey: "trusted.devices") as? [String: String] ?? [:]
        receivedFrom = defaults.dictionary(forKey: "history.receivedFrom") as? [String: String] ?? [:]
    }

    func start(name: String, saveDir: URL) {
        self.saveDir = saveDir
        woo_set_event_cb(coreEventCallback)
        let cfg = #"{"Name":"\#(name)","SaveDir":"\#(saveDir.path)","Port":0}"#
        _ = cfg.withCString { woo_start($0) }
        refreshIdentity()
        refreshReceived()
    }

    func stop() { woo_stop() }

    func enqueue(_ urls: [URL], to peer: Peer) {
        queue += urls.map { QueuedSend(peer: peer, url: $0) }
        pump()
    }

    func removeQueued(_ item: QueuedSend) { queue.removeAll { $0.id == item.id } }

    private func pump() {
        guard currentSend == nil, !queue.isEmpty else { return }
        let next = queue.removeFirst()
        currentSend = next
        next.peer.id.withCString { pid in
            next.url.path.withCString { p in _ = woo_send_file(pid, p) }
        }
    }

    func cancel() { woo_cancel() }

    func answerTrust(_ req: TrustRequest, accept: Bool, always: Bool = false) {
        if accept {
            incomingPeer = req.name
            if always { setTrusted(req.fingerprint, name: req.name) }
        }
        req.fingerprint.withCString { woo_trust($0, accept ? 1 : 0) }
        pendingTrust = nil
        if !accept { Toaster.shared.show("Declined") }
    }

    func setTrusted(_ fingerprint: String, name: String?) {
        trusted[fingerprint] = name
        defaults.set(trusted, forKey: "trusted.devices")
    }

    func isTrusted(_ peer: Peer) -> Bool { trusted[peer.fingerprint] != nil }

    func refreshReceived() {
        let keys: [URLResourceKey] = [.contentModificationDateKey, .isRegularFileKey]
        let items = (try? FileManager.default.contentsOfDirectory(
            at: saveDir, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles])) ?? []
        received = items
            .filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true }
            .sorted { modDate($0) > modDate($1) }
    }

    // MARK: - event handling (always on the main queue)

    fileprivate func handle(_ json: String) {
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = obj["type"] as? String else { return }

        switch type {
        case "peer_found":
            if let p = try? JSONDecoder().decode(Peer.self, from: data),
               !peers.contains(where: { $0.id == p.id }) {
                peers.append(p)
            }
        case "trust_request":
            let req = TrustRequest(
                name: obj["name"] as? String ?? "?",
                fingerprint: obj["fingerprint"] as? String ?? "",
                file: obj["file"] as? String ?? "?",
                size: (obj["size"] as? NSNumber)?.int64Value ?? 0)
            if trusted[req.fingerprint] != nil {
                answerTrust(req, accept: true)
                Toaster.shared.show("Receiving from \(req.name) (trusted)")
            } else {
                pendingTrust = req
            }
        case "progress":
            let name = obj["name"] as? String ?? ""
            let dir = obj["dir"] as? String ?? ""
            let sent = (obj["sent"] as? NSNumber)?.int64Value ?? 0
            let total = (obj["total"] as? NSNumber)?.int64Value ?? 0
            let peer = dir == "send" ? (currentSend?.peer.name ?? "") : incomingPeer
            active = ActiveTransfer(name: name, dir: dir, peer: peer, sent: sent, total: total,
                                    speed: updateSpeed(name: name, sent: sent))
        case "done":
            let dir = obj["dir"] as? String ?? active?.dir ?? ""
            let name = obj["name"] as? String ?? active?.name ?? ""
            let size = active.map { $0.total > 0 ? $0.total : $0.sent } ?? 0
            sessionTotal += size
            if dir == "send", let item = currentSend {
                sent.insert(SentRecord(name: name, size: size, peer: item.peer.name, date: Date()), at: 0)
                sent = Array(sent.prefix(200))
                if let data = try? JSONEncoder().encode(sent) { defaults.set(data, forKey: "history.sent") }
                currentSend = nil
                Toaster.shared.show("Sent \(name) to \(item.peer.name)")
            } else {
                let saved = (obj["path"] as? String).map { URL(fileURLWithPath: $0).lastPathComponent } ?? name
                if !incomingPeer.isEmpty {
                    receivedFrom[saved] = incomingPeer
                    defaults.set(receivedFrom, forKey: "history.receivedFrom")
                }
                Toaster.shared.show("Saved \(saved)")
            }
            active = nil
            resetSpeed()
            refreshReceived()
            if dir == "send", MirrorController.shared.consumeOpen(name) {
                CompanionBridge.shared.send(["type": "open_received", "name": name])
            }
            pump()
        case "canceled", "error":
            let wasSending = currentSend != nil
            active = nil
            currentSend = nil
            resetSpeed()
            refreshReceived()
            if type == "error" {
                Toaster.shared.show("Transfer failed. Check both devices are open on the same Wi‑Fi.")
            } else if wasSending {
                Toaster.shared.show("Canceled")
            }
            pump()
        case "session_open":
            MirrorController.shared.sessionOpened(obj)
        case "session_closed":
            MirrorController.shared.sessionClosed(obj["id"] as? String ?? "")
        case "session_error":
            MirrorController.shared.failed(obj["message"] as? String ?? "The session failed")
        default:
            break
        }
    }

    private func updateSpeed(name: String, sent: Int64) -> Double {
        let now = Date().timeIntervalSince1970 * 1000
        if name != trackName || sent < lastSent {
            trackName = name; lastMs = now; lastSent = sent; ema = 0
        }
        let dt = (now - lastMs) / 1000.0
        if dt >= 0.10 {
            let inst = Double(sent - lastSent) / dt
            ema = ema == 0 ? inst : ema * 0.6 + inst * 0.4
            lastMs = now; lastSent = sent
        }
        return ema
    }

    private func resetSpeed() { trackName = ""; lastMs = 0; lastSent = 0; ema = 0 }

    private func refreshIdentity() {
        guard let c = woo_identity_json() else { return }
        defer { woo_free(c) }
        if let d = String(cString: c).data(using: .utf8) {
            identity = try? JSONDecoder().decode(Identity.self, from: d)
            sessionPort = identity?.session ?? 0
        }
    }
}

private func modDate(_ url: URL) -> Date {
    (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
}

// @convention(c) callbacks can't capture context, so this forwards into the
// shared bridge on the main queue.
private func coreEventCallback(_ json: UnsafePointer<CChar>?) {
    guard let json = json else { return }
    let s = String(cString: json)
    DispatchQueue.main.async { CoreBridge.shared.handle(s) }
}
