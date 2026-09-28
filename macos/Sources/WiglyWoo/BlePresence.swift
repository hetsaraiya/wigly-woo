import CoreBluetooth
import CryptoKit
import Foundation

/// "Lock this Mac when the phone walks away". Off by default. The phone
/// advertises an 8-byte token in manufacturer data (company 0xFFFF) derived
/// from the pairing secret; RSSI gives a coarse near/far, not a distance.
final class BlePresence: NSObject, CBCentralManagerDelegate {
    static let shared = BlePresence()

    static var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: "presence.lock") }
        set { UserDefaults.standard.set(newValue, forKey: "presence.lock"); shared.apply() }
    }

    private var central: CBCentralManager?
    private var samples: [Int] = []
    private var lastSeen = Date.distantPast
    private var near = true
    private var watchdog: Timer?

    /// Creating the central manager is what prompts for Bluetooth, so it only
    /// happens once the feature is on.
    func apply() {
        if Self.enabled {
            if central == nil { central = CBCentralManager(delegate: self, queue: .main) }
            else { scan() }
            watchdog = watchdog ?? Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in self?.checkGone() }
        } else {
            central?.stopScan()
            watchdog?.invalidate()
            watchdog = nil
            samples.removeAll()
            lastSeen = .distantPast
            report(near: true)
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) { scan() }

    private func scan() {
        guard Self.enabled, let central, central.state == .poweredOn else { return }
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard let data = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
              data.count == 10, data[data.startIndex] == 0xFF, data[data.startIndex + 1] == 0xFF else { return }
        let secret = CompanionConfig.shared.pairingSecret
        guard secret.count >= 20, verify(Data(data.dropFirst(2)), secret: secret) else { return }
        lastSeen = Date()
        samples.append(RSSI.intValue)
        if samples.count > 8 { samples.removeFirst() }
        guard samples.count >= 4 else { return }
        let average = samples.reduce(0, +) / samples.count
        // Hysteresis so a phone at the boundary does not flap.
        if near && average < -85 { report(near: false) }
        else if !near && average > -72 { report(near: true) }
    }

    /// No token for a while means the phone left. A phone never seen since
    /// the feature was turned on (its Nearby toggle is off) never locks the Mac.
    private func checkGone() {
        if near, lastSeen != .distantPast, Date().timeIntervalSince(lastSeen) > 45 {
            samples.removeAll()
            report(near: false)
        }
    }

    private func report(near: Bool) {
        guard near != self.near else { return }
        self.near = near
        CompanionBridge.shared.send(["type": "presence", "near": near])
        if !near && Self.enabled { lockMac() }
    }

    private func verify(_ token: Data, secret: String) -> Bool {
        let minute = Int(Date().timeIntervalSince1970 / 60)
        let key = SymmetricKey(data: Data(secret.utf8))
        return [0, -1, 1].contains { offset in
            let code = HMAC<SHA256>.authenticationCode(for: Data("wigly-ble-v1|\(minute + offset)".utf8), using: key)
            return Data(code).prefix(8) == token
        }
    }

    private func lockMac() {
        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/usr/bin/pmset")
        task.arguments = ["displaysleepnow"]
        try? task.run()
    }
}
