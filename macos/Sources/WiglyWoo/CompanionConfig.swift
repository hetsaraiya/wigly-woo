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

    let deviceID: String
    private let defaults = UserDefaults.standard

    private init() {
        supabaseURL = defaults.string(forKey: "companion.url") ?? ""
        publishableKey = defaults.string(forKey: "companion.key") ?? ""
        pairingSecret = defaults.string(forKey: "companion.secret") ?? ""
        enabled = defaults.bool(forKey: "companion.enabled")
        notificationsEnabled = defaults.object(forKey: "companion.notifications") as? Bool ?? true
        clipboardEnabled = defaults.object(forKey: "companion.clipboard") as? Bool ?? true
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
        CompanionBridge.shared.reconfigure()
    }

    func generateSecret() {
        let key = SymmetricKey(size: .bits256)
        pairingSecret = key.withUnsafeBytes { Data($0).map { String(format: "%02x", $0) }.joined() }
    }
}
