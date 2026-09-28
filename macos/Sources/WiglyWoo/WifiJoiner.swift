import CoreWLAN
import Foundation

/// Joins a phone hotspot or a shared network. CoreWLAN asks for Location
/// the first time it reads the interface.
enum WifiJoiner {
    static func join(ssid: String, psk: String) -> String? {
        guard let iface = CWWiFiClient.shared().interface() else { return "No Wi-Fi interface" }
        do {
            iface.disassociate()
            let networks = try iface.scanForNetworks(withSSID: ssid.data(using: .utf8))
            guard let network = networks.first else { return "The hotspot is not visible yet" }
            try iface.associate(to: network, password: psk)
            return nil
        } catch {
            return error.localizedDescription
        }
    }
}
