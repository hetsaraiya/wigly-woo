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
    let sent: Int64
    let total: Int64
    let speed: Double      // bytes/sec, smoothed
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

    private(set) var saveDir = URL(fileURLWithPath: ".")

    // Speed tracking (EMA-smoothed, see updateSpeed).
    private var trackName = ""
    private var lastMs = 0.0
    private var lastSent: Int64 = 0
    private var ema = 0.0

    private init() {}

    func start(name: String, saveDir: URL) {
        self.saveDir = saveDir
        woo_set_event_cb(coreEventCallback)
        let cfg = #"{"Name":"\#(name)","SaveDir":"\#(saveDir.path)","Port":0}"#
        _ = cfg.withCString { woo_start($0) }
        refreshIdentity()
        refreshReceived()
    }

    func stop() { woo_stop() }

    func sendFile(to peer: Peer, path: String) {
        peer.id.withCString { pid in
            path.withCString { p in _ = woo_send_file(pid, p) }
        }
    }

    func cancel() { woo_cancel() }

    func answerTrust(_ req: TrustRequest, accept: Bool) {
        req.fingerprint.withCString { woo_trust($0, accept ? 1 : 0) }
        pendingTrust = nil
    }

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
            pendingTrust = TrustRequest(
                name: obj["name"] as? String ?? "?",
                fingerprint: obj["fingerprint"] as? String ?? "",
                file: obj["file"] as? String ?? "?",
                size: (obj["size"] as? NSNumber)?.int64Value ?? 0)
        case "progress":
            let name = obj["name"] as? String ?? ""
            let dir = obj["dir"] as? String ?? ""
            let sent = (obj["sent"] as? NSNumber)?.int64Value ?? 0
            let total = (obj["total"] as? NSNumber)?.int64Value ?? 0
            active = ActiveTransfer(name: name, dir: dir, sent: sent, total: total,
                                    speed: updateSpeed(name: name, sent: sent))
        case "done":
            if let a = active { sessionTotal += a.total > 0 ? a.total : a.sent }
            active = nil
            resetSpeed()
            refreshReceived()
        case "canceled", "error":
            active = nil
            resetSpeed()
            refreshReceived()
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
