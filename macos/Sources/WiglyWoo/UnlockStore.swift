import Darwin
import Foundation
import LocalAuthentication
import Security
import WiglyMirror

/// The phone PIN lives only in the Mac keychain, behind Touch ID.
/// It is off until the user saves one. It is sent once and then wiped.
enum UnlockStore {
    private static let service = "com.wiglywoo.phone-pin"

    static var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: "unlock.phone.enabled") }
        set { UserDefaults.standard.set(newValue, forKey: "unlock.phone.enabled") }
    }

    static func save(_ pin: String) -> String? {
        guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, .biometryCurrentSet, nil) else {
            return "This Mac could not bind the PIN to Touch ID"
        }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "phone",
        ]
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData as String] = Data(pin.utf8)
        add[kSecAttrAccessControl as String] = access
        let status = SecItemAdd(add as CFDictionary, nil)
        return status == errSecSuccess ? nil : "Keychain refused the PIN (\(status))"
    }

    static func unlockPhone() {
        guard enabled else {
            Toaster.shared.show("Phone unlock is off")
            return
        }
        let context = LAContext()
        context.localizedReason = "Unlock \(CompanionConfig.shared.phoneName)"
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else {
            Toaster.shared.show("Touch ID is not available")
            return
        }
        context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: "Unlock \(CompanionConfig.shared.phoneName)") { ok, _ in
            guard ok else { return }
            var item: CFTypeRef?
            let query: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
                kSecAttrAccount as String: "phone",
                kSecReturnData as String: true,
                kSecUseAuthenticationContext as String: context,
            ]
            guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return }
            var bytes = [UInt8](data)
            var packet = Data([12])
            packet.append(contentsOf: bytes)
            MirrorController.shared.sendControl(packet)
            for i in bytes.indices { bytes[i] = 0 }
            packet.withUnsafeMutableBytes { raw in
                if let base = raw.baseAddress { memset(base, 0, raw.count) }
            }
        }
    }
}
