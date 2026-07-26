import Foundation
import CryptoKit

enum CompanionCrypto {
    private static let aad = Data("wigly-companion-v1".utf8)

    static func encrypt(_ message: [String: Any], secret: String, sender: String) throws -> [String: Any] {
        let plain = try JSONSerialization.data(withJSONObject: message)
        let sealed = try AES.GCM.seal(plain, using: key(secret), authenticating: aad)
        let nonce = sealed.nonce.withUnsafeBytes { Data($0) }
        var encrypted = sealed.ciphertext
        encrypted.append(sealed.tag)
        return [
            "v": 1,
            "sender": sender,
            "nonce": nonce.base64EncodedString(),
            "ciphertext": encrypted.base64EncodedString()
        ]
    }

    static func decrypt(_ envelope: [String: Any], secret: String) throws -> [String: Any] {
        guard envelope["v"] as? Int == 1,
              let nonceString = envelope["nonce"] as? String,
              let cipherString = envelope["ciphertext"] as? String,
              let nonceData = Data(base64Encoded: nonceString),
              let combined = Data(base64Encoded: cipherString), combined.count >= 16 else {
            throw CryptoError.invalidEnvelope
        }
        let nonce = try AES.GCM.Nonce(data: nonceData)
        let ciphertext = combined.dropLast(16)
        let tag = combined.suffix(16)
        let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
        let plain = try AES.GCM.open(box, using: key(secret), authenticating: aad)
        guard let object = try JSONSerialization.jsonObject(with: plain) as? [String: Any] else {
            throw CryptoError.invalidEnvelope
        }
        return object
    }

    static func digest(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private static func key(_ secret: String) -> SymmetricKey {
        let hash = SHA256.hash(data: Data("wigly-key-v1|\(secret)".utf8))
        return SymmetricKey(data: Data(hash))
    }

    private enum CryptoError: Error { case invalidEnvelope }
}
