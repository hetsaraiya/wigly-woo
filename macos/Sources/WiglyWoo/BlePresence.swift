import CoreBluetooth
import CryptoKit
import Foundation

/// Coarse near/far from a rotating token. RSSI is not a distance.
final class BlePresence: NSObject, CBCentralManagerDelegate {
    static let shared = BlePresence()
    static let service = CBUUID(string: "8f3c1c0e-6a3a-4b1e-9e2a-7c5d9a1b0001")

    private var central: CBCentralManager?
    private var samples: [Int] = []
    private(set) var near = true

    func start() {
        if central == nil { central = CBCentralManager(delegate: self, queue: .main) }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn else { return }
        central.scanForPeripherals(withServices: [Self.service], options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let secret = CompanionConfig.shared.pairingSecret
        guard secret.count >= 20,
              let data = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
              let token = data[Self.service],
              verify(token, secret: secret) else { return }
        samples.append(RSSI.intValue)
        if samples.count > 5 { samples.removeFirst() }
        let average = samples.reduce(0, +) / samples.count
        let was = near
        near = average > -80
        if was != near {
            CompanionBridge.shared.send(["type": "presence", "near": near])
            if !near { lockMac() }
        }
    }

    private func verify(_ token: Data, secret: String) -> Bool {
        let minute = Int(Date().timeIntervalSince1970 / 60)
        for offset in [0, -1] {
            let key = SymmetricKey(data: Data(secret.utf8))
            let code = HMAC<SHA256>.authenticationCode(for: Data("wigly-ble-v1|\(minute + offset)".utf8), using: key)
            if Data(code).prefix(8) == token { return true }
        }
        return false
    }

    private func lockMac() {
        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/usr/bin/pmset")
        task.arguments = ["displaysleepnow"]
        try? task.run()
    }
}
