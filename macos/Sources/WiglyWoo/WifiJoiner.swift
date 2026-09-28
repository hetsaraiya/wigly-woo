import CoreWLAN
import Foundation

/// Joins a phone hotspot. CoreWLAN asks for Location the first time it scans.
/// The Mac stays on its current network until the hotspot is found, and the
/// slow scan runs off the main thread.
enum WifiJoiner {
    static func join(ssid: String, psk: String, done: @escaping (String?) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            let error = attempt(ssid: ssid, psk: psk)
            DispatchQueue.main.async { done(error) }
        }
    }

    private static func attempt(ssid: String, psk: String) -> String? {
        guard !ssid.isEmpty else { return "The phone did not send a hotspot name" }
        guard let iface = CWWiFiClient.shared().interface() else { return "No Wi‑Fi interface" }
        // A hotspot can take a few seconds to start beaconing.
        for _ in 0..<4 {
            if let network = try? iface.scanForNetworks(withSSID: ssid.data(using: .utf8)).first {
                do {
                    try iface.associate(to: network, password: psk.isEmpty ? nil : psk)
                    return nil
                } catch {
                    return error.localizedDescription
                }
            }
            Thread.sleep(forTimeInterval: 2)
        }
        return "The hotspot is not visible. Allow Location for Wigly Woo, then try again."
    }
}
