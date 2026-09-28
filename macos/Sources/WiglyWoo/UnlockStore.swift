import Foundation
import LocalAuthentication
import Security

/// The phone PIN lives only in this Mac's keychain, behind Touch ID. Unlock
/// sends it once over the end-to-end encrypted companion link; the phone's
/// Shizuku service types it on the lock screen.
enum UnlockStore {
    private static let service = "com.wiglywoo.phone-pin"
    private static let base: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: service,
        kSecAttrAccount as String: "phone",
    ]

    /// Whether a PIN is saved. Reading this does not prompt for Touch ID.
    static var hasPIN: Bool {
        var query = base
        let context = LAContext()
        context.interactionNotAllowed = true
        query[kSecUseAuthenticationContext as String] = context
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        return status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    static func save(_ pin: String) -> String? {
        guard !pin.isEmpty, pin.allSatisfy(\.isNumber) else { return "The PIN must be digits" }
        guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, .biometryCurrentSet, nil) else {
            return "This Mac could not bind the PIN to Touch ID"
        }
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = Data(pin.utf8)
        add[kSecAttrAccessControl as String] = access
        let status = SecItemAdd(add as CFDictionary, nil)
        return status == errSecSuccess ? nil : "Keychain refused the PIN (\(status))"
    }

    static func forget() { SecItemDelete(base as CFDictionary) }

    static func unlockPhone() {
        guard hasPIN else {
            Toaster.shared.show("Save the phone's PIN first")
            return
        }
        let context = LAContext()
        context.localizedReason = "Unlock \(CompanionConfig.shared.phoneName)"
        var query = base
        query[kSecReturnData as String] = true
        query[kSecUseAuthenticationContext as String] = context
        DispatchQueue.global(qos: .userInitiated).async {
            var item: CFTypeRef?
            guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
                  let data = item as? Data, let pin = String(data: data, encoding: .utf8) else { return }
            DispatchQueue.main.async {
                CompanionBridge.shared.send(["type": "unlock", "pin": pin])
                Toaster.shared.show("Unlocking \(CompanionConfig.shared.phoneName)…")
            }
        }
    }
}
