import SwiftUI
import AppKit

/// Starts the core and companion once, outside SwiftUI's scene updates.
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        let dir = FileManager.default
            .urls(for: .downloadsDirectory, in: .userDomainMask).first?
            .appendingPathComponent("wigly-woo")
            ?? URL(fileURLWithPath: ".")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        CoreBridge.shared.start(name: Host.current().localizedName ?? "Mac", saveDir: dir)
        CompanionBridge.shared.start()
        HotKey.shared.install()
    }
}

@main
struct WiglyWooApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var core = CoreBridge.shared
    @StateObject private var companion = CompanionBridge.shared
    @AppStorage("showMenuBar") private var showMenuBar = true

    // Fonts only; nothing here may publish state SwiftUI is observing.
    init() { WWFont.register() }

    var body: some Scene {
        Window("Wigly Woo", id: "main") {
            ContentView()
                .environmentObject(core)
                .environmentObject(companion)
                .frame(minWidth: 860, idealWidth: 1040, minHeight: 620, idealHeight: 720)
        }
        .windowStyle(.hiddenTitleBar)

        MenuBarExtra(isInserted: $showMenuBar) {
            MenuBarPanel()
                .environmentObject(core)
                .environmentObject(companion)
        } label: {
            MenuBarLabel(companion: companion)
        }
        .menuBarExtraStyle(.window)
    }
}
