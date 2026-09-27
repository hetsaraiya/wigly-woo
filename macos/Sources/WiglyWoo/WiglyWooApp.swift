import SwiftUI

@main
struct WiglyWooApp: App {
    @StateObject private var core = CoreBridge.shared
    @StateObject private var companion = CompanionBridge.shared
    @ObservedObject private var ui = AppUI.shared

    init() {
        WWFont.register()
        let dir = FileManager.default
            .urls(for: .downloadsDirectory, in: .userDomainMask).first?
            .appendingPathComponent("wigly-woo")
            ?? URL(fileURLWithPath: ".")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        CoreBridge.shared.start(name: Host.current().localizedName ?? "Mac", saveDir: dir)
        CompanionBridge.shared.start()
    }

    var body: some Scene {
        Window("Wigly Woo", id: "main") {
            ContentView()
                .environmentObject(core)
                .environmentObject(companion)
                .frame(minWidth: 860, idealWidth: 1040, minHeight: 620, idealHeight: 720)
        }
        .windowStyle(.hiddenTitleBar)

        MenuBarExtra(isInserted: $ui.showMenuBar) {
            MenuBarPanel()
                .environmentObject(core)
                .environmentObject(companion)
        } label: {
            MenuBarLabel(companion: companion)
        }
        .menuBarExtraStyle(.window)
    }
}
