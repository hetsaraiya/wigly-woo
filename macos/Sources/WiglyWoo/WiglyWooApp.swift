import SwiftUI

@main
struct WiglyWooApp: App {
    @StateObject private var core = CoreBridge.shared
    @StateObject private var companion = CompanionBridge.shared

    var body: some Scene {
        WindowGroup("Wigly Woo") {
            ContentView()
                .environmentObject(core)
                .environmentObject(companion)
                .frame(minWidth: 820, idealWidth: 1040, minHeight: 620, idealHeight: 760)
                .onAppear {
                    let dir = FileManager.default
                        .urls(for: .downloadsDirectory, in: .userDomainMask).first?
                        .appendingPathComponent("wigly-woo")
                        ?? URL(fileURLWithPath: ".")
                    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                    core.start(name: Host.current().localizedName ?? "Mac", saveDir: dir)
                    companion.start()
                }
        }
        .windowStyle(.hiddenTitleBar)
    }
}
