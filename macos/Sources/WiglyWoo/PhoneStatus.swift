import AppKit
import Foundation

/// Menu-bar phone status. Updates arrive when the phone's battery or radio changes.
final class PhoneStatus: ObservableObject {
    static let shared = PhoneStatus()
    @Published var battery = -1
    @Published var charging = false
    @Published var wifi = ""
    @Published var dnd = false
    @Published var signal = -1

    func apply(_ message: [String: Any]) {
        battery = (message["battery"] as? NSNumber)?.intValue ?? battery
        charging = message["charging"] as? Bool ?? charging
        wifi = message["wifi"] as? String ?? wifi
        dnd = message["dnd"] as? Bool ?? dnd
        signal = (message["signal"] as? NSNumber)?.intValue ?? signal
    }

    var line: String {
        guard battery >= 0 else { return "Phone status will show once the phone is connected." }
        let charge = charging ? ", charging" : ""
        let net = wifi.isEmpty ? "" : " · \(wifi)"
        let quiet = dnd ? " · Do Not Disturb" : ""
        return "\(battery)%\(charge)\(net)\(quiet)"
    }
}
