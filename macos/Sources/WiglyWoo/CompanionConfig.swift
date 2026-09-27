import Foundation
import CryptoKit

final class CompanionConfig: ObservableObject {
    static let shared = CompanionConfig()

    @Published var supabaseURL: String
    @Published var publishableKey: String
    @Published var pairingSecret: String
    @Published var enabled: Bool
    @Published var notificationsEnabled: Bool
    @Published var clipboardEnabled: Bool
    /// Name the paired phone announced over the channel ("Pixel 8").
    @Published var peerName: String

    let deviceID: String
    private let defaults = UserDefaults.standard

    private init() {
        supabaseURL = defaults.string(forKey: "companion.url") ?? ""
        publishableKey = defaults.string(forKey: "companion.key") ?? ""
        pairingSecret = defaults.string(forKey: "companion.secret") ?? ""
        enabled = defaults.bool(forKey: "companion.enabled")
        notificationsEnabled = defaults.object(forKey: "companion.notifications") as? Bool ?? true
        clipboardEnabled = defaults.object(forKey: "companion.clipboard") as? Bool ?? true
        peerName = defaults.string(forKey: "companion.peerName") ?? ""
        if let existing = defaults.string(forKey: "companion.deviceID") {
            deviceID = existing
        } else {
            deviceID = UUID().uuidString
            defaults.set(deviceID, forKey: "companion.deviceID")
        }
    }

    var isComplete: Bool {
        normalizedURL.hasPrefix("https://") && !publishableKey.trimmingCharacters(in: .whitespaces).isEmpty
            && pairingSecret.count >= 20
    }

    var normalizedURL: String {
        supabaseURL.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    var channelID: String {
        SHA256.hash(data: Data("wigly-channel-v1|\(pairingSecret)".utf8))
            .map { String(format: "%02x", $0) }.joined().prefix(40).description
    }

    func save() {
        supabaseURL = normalizedURL
        defaults.set(supabaseURL, forKey: "companion.url")
        defaults.set(publishableKey.trimmingCharacters(in: .whitespacesAndNewlines), forKey: "companion.key")
        defaults.set(pairingSecret.trimmingCharacters(in: .whitespacesAndNewlines), forKey: "companion.secret")
        defaults.set(enabled, forKey: "companion.enabled")
        defaults.set(notificationsEnabled, forKey: "companion.notifications")
        defaults.set(clipboardEnabled, forKey: "companion.clipboard")
        defaults.set(peerName, forKey: "companion.peerName")
        CompanionBridge.shared.reconfigure()
    }

    var hasRelay: Bool {
        normalizedURL.hasPrefix("https://") && !publishableKey.trimmingCharacters(in: .whitespaces).isEmpty
    }

    /// Six digits both devices derive from the shared secret, shown side by
    /// side so the person can confirm they paired the right pair.
    var pairingCode: String {
        let digest = Array(SHA256.hash(data: Data("wigly-pair-v1|\(pairingSecret)".utf8)))
        let n = digest.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) } % 1_000_000
        let s = String(format: "%06d", n)
        return "\(s.prefix(3)) \(s.suffix(3))"
    }

    /// What the phone scans: the relay details plus the secret.
    func pairingURL(macName: String) -> String {
        var c = URLComponents()
        c.scheme = "wiglywoo"
        c.host = "pair"
        c.queryItems = [
            URLQueryItem(name: "u", value: normalizedURL),
            URLQueryItem(name: "k", value: publishableKey.trimmingCharacters(in: .whitespacesAndNewlines)),
            URLQueryItem(name: "s", value: pairingSecret),
            URLQueryItem(name: "n", value: macName),
        ]
        return c.string ?? ""
    }

    /// Persists the announced phone name without reconnecting.
    func rememberPeer(_ name: String) {
        peerName = name
        defaults.set(name, forKey: "companion.peerName")
    }

    var phoneName: String { peerName.isEmpty ? "your phone" : peerName }

    func unpair() {
        pairingSecret = ""
        peerName = ""
        enabled = false
        save()
    }

    func generateSecret() {
        let key = SymmetricKey(size: .bits256)
        pairingSecret = key.withUnsafeBytes { Data($0).map { String(format: "%02x", $0) }.joined() }
    }
}
