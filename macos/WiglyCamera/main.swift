import CoreMediaIO
import Foundation

/// Publishes "Wigly Woo Camera" to other apps. A system extension needs a
/// Developer ID signature, notarization, and an install in /Applications.
/// Without those, this target builds but cannot be installed.
@main
struct WiglyCameraMain {
    static func main() {
        let provider = WiglyProviderSource(clientQueue: nil)
        CMIOExtensionProvider.startService(provider: provider.provider)
        CFRunLoopRun()
    }
}

final class WiglyProviderSource: NSObject, CMIOExtensionProviderSource {
    private(set) var provider: CMIOExtensionProvider!
    init(clientQueue: DispatchQueue?) {
        super.init()
        provider = CMIOExtensionProvider(source: self, clientQueue: clientQueue)
        let device = CMIOExtensionDevice(localizedName: "Wigly Woo Camera", deviceID: "wigly-camera", legacyDeviceID: nil, source: WiglyDeviceSource())
        try? provider.addDevice(device)
    }
    func connect(to client: CMIOExtensionClient) throws {}
    func disconnect(from client: CMIOExtensionClient) {}
    var availableProperties: Set<CMIOExtensionProperty> { [] }
    func providerProperties(forProperties properties: Set<CMIOExtensionProperty>) throws -> CMIOExtensionProviderProperties { CMIOExtensionProviderProperties(dictionary: [:]) }
    func setProviderProperties(_ providerProperties: CMIOExtensionProviderProperties) throws {}
}

final class WiglyDeviceSource: NSObject, CMIOExtensionDeviceSource {
    private(set) var device: CMIOExtensionDevice!
    func connect(to client: CMIOExtensionClient) throws {}
    func disconnect(from client: CMIOExtensionClient) {}
    var availableProperties: Set<CMIOExtensionProperty> { [.deviceTransportType, .deviceModel] }
    func deviceProperties(forProperties properties: Set<CMIOExtensionProperty>) throws -> CMIOExtensionDeviceProperties {
        CMIOExtensionDeviceProperties(dictionary: [
            CMIOExtensionProperty.deviceTransportType: "wifi",
            CMIOExtensionProperty.deviceModel: "Wigly Woo",
        ])
    }
    func setDeviceProperties(_ deviceProperties: CMIOExtensionDeviceProperties) throws {}
}
