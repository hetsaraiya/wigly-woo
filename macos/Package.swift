// swift-tools-version: 5.9
import PackageDescription

// Run ./build-core.sh first — it cross-builds the Go core into
// Vendor/woocore/libwoocore.a and refreshes Sources/CWooCore/woocore.h.
let package = Package(
    name: "WiglyWoo",
    platforms: [.macOS(.v13)],
    targets: [
        // Header-only wrapper exposing the Go core's C ABI to Swift.
        .systemLibrary(name: "CWooCore", path: "Sources/CWooCore"),
        // ObjC @try/@catch trampoline — Swift cannot catch NSException.
        .target(name: "WiglyExceptionGuard", path: "Sources/WiglyExceptionGuard"),
        .executableTarget(
            name: "WiglyWoo",
            dependencies: ["CWooCore", "WiglyExceptionGuard"],
            linkerSettings: [
                // The prebuilt static archive + the frameworks the Go runtime needs.
                .unsafeFlags([
                    "-L", "Vendor/woocore", "-lwoocore",
                    "-framework", "CoreFoundation",
                    "-framework", "Security",
                ]),
            ]
        ),
    ]
)
