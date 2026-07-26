import SwiftUI
import AppKit
import UniformTypeIdentifiers

// MARK: - Product shell

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xff) / 255,
            green: Double((hex >> 8) & 0xff) / 255,
            blue: Double(hex & 0xff) / 255,
            opacity: alpha
        )
    }
}

private enum Destination: String, CaseIterable {
    case send = "Send"
    case inbox = "Inbox"
    case companion = "Companion"

    var symbol: String {
        switch self {
        case .send: return "paperplane.fill"
        case .inbox: return "tray.fill"
        case .companion: return "link"
        }
    }
}

private struct WWTheme {
    let background, sidebar, surface, surfaceRaised, border: Color
    let text, secondary, tertiary: Color
    let accent, accentSoft, onAccent: Color
    let success, successSoft, warning, warningSoft, danger, dangerSoft: Color
    let shadow: Color

    static func make(_ scheme: ColorScheme) -> WWTheme {
        if scheme == .dark {
            return WWTheme(
                background: Color(hex: 0x111217),
                sidebar: Color(hex: 0x17191f),
                surface: Color(hex: 0x1d2028),
                surfaceRaised: Color(hex: 0x242832),
                border: Color.white.opacity(0.08),
                text: Color(hex: 0xf4f5f8),
                secondary: Color(hex: 0xa8acb8),
                tertiary: Color(hex: 0x737886),
                accent: Color(hex: 0x7c83ff),
                accentSoft: Color(hex: 0x7c83ff, alpha: 0.14),
                onAccent: .white,
                success: Color(hex: 0x57cf91),
                successSoft: Color(hex: 0x57cf91, alpha: 0.12),
                warning: Color(hex: 0xf0b65d),
                warningSoft: Color(hex: 0xf0b65d, alpha: 0.12),
                danger: Color(hex: 0xf27d76),
                dangerSoft: Color(hex: 0xf27d76, alpha: 0.12),
                shadow: .black.opacity(0.28)
            )
        }
        return WWTheme(
            background: Color(hex: 0xf5f6fa),
            sidebar: Color(hex: 0xebedf4),
            surface: .white,
            surfaceRaised: Color(hex: 0xfbfbfd),
            border: Color(hex: 0x1f2430, alpha: 0.09),
            text: Color(hex: 0x1b1e27),
            secondary: Color(hex: 0x5e6370),
            tertiary: Color(hex: 0x8f94a0),
            accent: Color(hex: 0x555bdc),
            accentSoft: Color(hex: 0x555bdc, alpha: 0.10),
            onAccent: .white,
            success: Color(hex: 0x218b57),
            successSoft: Color(hex: 0x218b57, alpha: 0.10),
            warning: Color(hex: 0xa86912),
            warningSoft: Color(hex: 0xa86912, alpha: 0.10),
            danger: Color(hex: 0xc64c49),
            dangerSoft: Color(hex: 0xc64c49, alpha: 0.10),
            shadow: Color(hex: 0x242b3a, alpha: 0.10)
        )
    }
}

private extension View {
    func wwCard(_ theme: WWTheme, radius: CGFloat = 18, shadow: Bool = false) -> some View {
        self
            .background(theme.surface, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous)
                .stroke(theme.border, lineWidth: 1))
            .shadow(color: shadow ? theme.shadow : .clear, radius: 12, y: 5)
    }
}

struct ContentView: View {
    @EnvironmentObject private var core: CoreBridge
    @EnvironmentObject private var companion: CompanionBridge
    @ObservedObject private var config = CompanionConfig.shared
    @Environment(\.colorScheme) private var scheme

    @State private var destination: Destination = .send
    @State private var showingSettings = false
    @State private var keyboardText = ""

    private var theme: WWTheme { .make(scheme) }

    var body: some View {
        HStack(spacing: 0) {
            AppSidebar(
                theme: theme,
                selection: $destination,
                linkStatus: linkStatus,
                onSettings: { showingSettings = true }
            )
            .frame(width: 220)

            ZStack {
                theme.background.ignoresSafeArea()
                destinationView
            }
        }
        .background(theme.background)
        .sheet(isPresented: $showingSettings) {
            ConnectionSettingsSheet(theme: theme, config: config)
        }
        .alert(
            "Transfer failed",
            isPresented: Binding(
                get: { core.transferError != nil },
                set: { if !$0 { core.transferError = nil } }
            )
        ) {
            Button("OK", role: .cancel) { core.transferError = nil }
        } message: {
            Text(core.transferError ?? "")
        }
        .overlay {
            if let request = core.pendingTrust {
                ModernIncomingOverlay(
                    theme: theme,
                    request: request,
                    onAccept: { core.answerTrust(request, accept: true) },
                    onDecline: { core.answerTrust(request, accept: false) }
                )
            }
        }
    }

    @ViewBuilder
    private var destinationView: some View {
        switch destination {
        case .send:
            SendDashboard(
                theme: theme,
                identity: core.identity,
                peers: core.peers,
                transfer: core.active,
                recent: Array(core.received.prefix(3)),
                companionOnline: companion.state == .connected,
                onSend: pickAndSend,
                onDropped: sendDroppedFile,
                onCancel: core.cancel,
                onOpenInbox: { destination = .inbox },
                onOpenCompanion: { destination = .companion }
            )
        case .inbox:
            InboxDashboard(
                theme: theme,
                files: core.received,
                saveDirectory: core.saveDir,
                sessionTotal: core.sessionTotal,
                onRefresh: core.refreshReceived
            )
        case .companion:
            CompanionDashboard(
                theme: theme,
                state: companion.state,
                config: config,
                notificationsAuthorized: companion.notificationsAuthorized,
                lastActivity: companion.lastActivity,
                keyboardText: $keyboardText,
                onSendText: sendKeyboardText,
                onBackspace: companion.sendBackspace,
                onEnter: companion.sendEnter,
                onSettings: { showingSettings = true },
                onNotificationSettings: companion.openNotificationSettings
            )
        }
    }

    private var linkStatus: SidebarLinkStatus {
        if !config.isComplete {
            return SidebarLinkStatus(title: "Companion not set up", detail: "Files still work nearby",
                                     color: theme.tertiary, symbol: "circle.dashed")
        }
        if !config.enabled {
            return SidebarLinkStatus(title: "Companion paused", detail: "Nearby sharing is ready",
                                     color: theme.tertiary, symbol: "pause.fill")
        }
        switch companion.state {
        case .connected:
            return SidebarLinkStatus(title: "Android connected", detail: "Encrypted and ready",
                                     color: theme.success, symbol: "checkmark")
        case .connecting:
            return SidebarLinkStatus(title: "Connecting…", detail: "This usually takes a moment",
                                     color: theme.warning, symbol: "arrow.triangle.2.circlepath")
        case .error:
            return SidebarLinkStatus(title: "Trying to reconnect", detail: "Nearby sharing still works",
                                     color: theme.danger, symbol: "exclamationmark")
        case .off:
            return SidebarLinkStatus(title: "Companion offline", detail: "Open settings to connect",
                                     color: theme.tertiary, symbol: "circle.dashed")
        }
    }

    private func pickAndSend(to peer: Peer) {
        let panel = NSOpenPanel()
        panel.title = "Choose a file for \(peer.name)"
        panel.prompt = "Send"
        panel.message = "The file goes directly to \(peer.name) over your local network."
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        if panel.runModal() == .OK, let url = panel.url {
            core.sendFile(to: peer, path: url.path)
        }
    }

    private func sendDroppedFile(_ url: URL, to peer: Peer) {
        core.sendFile(to: peer, path: url.path)
    }

    private func sendKeyboardText() {
        let value = keyboardText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, companion.state == .connected else { return }
        companion.sendKeyboardText(value)
        keyboardText = ""
    }
}

// MARK: - Sidebar

private struct SidebarLinkStatus {
    let title, detail: String
    let color: Color
    let symbol: String
}

private struct AppSidebar: View {
    let theme: WWTheme
    @Binding var selection: Destination
    let linkStatus: SidebarLinkStatus
    let onSettings: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(theme.accent)
                    Text("w")
                        .font(.system(size: 19, weight: .heavy, design: .rounded))
                        .foregroundStyle(theme.onAccent)
                        .offset(y: -1)
                }
                .frame(width: 34, height: 34)
                Text("wigly woo")
                    .font(.system(size: 16, weight: .bold, design: .rounded))
                    .foregroundStyle(theme.text)
            }
            .padding(.leading, 18)
            .padding(.top, 22)
            .padding(.bottom, 28)

            VStack(spacing: 5) {
                ForEach(Destination.allCases, id: \.self) { item in
                    Button {
                        selection = item
                    } label: {
                        HStack(spacing: 11) {
                            Image(systemName: item.symbol)
                                .font(.system(size: 13, weight: .semibold))
                                .frame(width: 20)
                            Text(item.rawValue)
                                .font(.system(size: 13.5, weight: .semibold))
                            Spacer()
                        }
                        .foregroundStyle(selection == item ? theme.accent : theme.secondary)
                        .padding(.horizontal, 12)
                        .frame(height: 40)
                        .background(selection == item ? theme.accentSoft : .clear,
                                    in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 10)

            Spacer()

            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 10) {
                    ZStack {
                        Circle().fill(linkStatus.color.opacity(0.14))
                        Image(systemName: linkStatus.symbol)
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(linkStatus.color)
                    }
                    .frame(width: 28, height: 28)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(linkStatus.title)
                            .font(.system(size: 11.5, weight: .semibold))
                            .foregroundStyle(theme.text)
                        Text(linkStatus.detail)
                            .font(.system(size: 10.5))
                            .foregroundStyle(theme.tertiary)
                    }
                }

                Button(action: onSettings) {
                    HStack {
                        Image(systemName: "gearshape")
                        Text("Settings")
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.system(size: 9, weight: .bold))
                    }
                    .font(.system(size: 11.5, weight: .semibold))
                    .foregroundStyle(theme.secondary)
                    .padding(.horizontal, 11)
                    .frame(height: 34)
                    .background(theme.surface.opacity(0.65),
                                in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain)
            }
            .padding(14)
            .wwCard(theme, radius: 15)
            .padding(12)
        }
        .background(theme.sidebar)
        .overlay(alignment: .trailing) {
            Rectangle().fill(theme.border).frame(width: 1)
        }
    }
}

// MARK: - Shared page elements

private struct PageHeader: View {
    let theme: WWTheme
    let eyebrow, title, subtitle: String
    var actionTitle: String?
    var actionSymbol = "arrow.clockwise"
    var action: (() -> Void)?

    var body: some View {
        HStack(alignment: .top, spacing: 20) {
            VStack(alignment: .leading, spacing: 6) {
                Text(eyebrow.uppercased())
                    .font(.system(size: 10.5, weight: .bold))
                    .kerning(0.9)
                    .foregroundStyle(theme.accent)
                Text(title)
                    .font(.system(size: 28, weight: .bold, design: .rounded))
                    .foregroundStyle(theme.text)
                Text(subtitle)
                    .font(.system(size: 13))
                    .foregroundStyle(theme.secondary)
            }
            Spacer()
            if let actionTitle, let action {
                Button(action: action) {
                    Label(actionTitle, systemImage: actionSymbol)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(theme.secondary)
                        .padding(.horizontal, 13)
                        .frame(height: 34)
                        .background(theme.surface, in: Capsule())
                        .overlay(Capsule().stroke(theme.border, lineWidth: 1))
                }
                .buttonStyle(.plain)
            }
        }
    }
}

private struct SectionHeader: View {
    let theme: WWTheme
    let title: String
    var detail: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(theme.text)
            if let detail {
                Text(detail)
                    .font(.system(size: 11))
                    .foregroundStyle(theme.tertiary)
            }
            Spacer()
        }
    }
}

private struct StatusPill: View {
    let theme: WWTheme
    let text: String
    let color: Color

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 6, height: 6)
            Text(text)
                .font(.system(size: 10.5, weight: .semibold))
                .foregroundStyle(theme.secondary)
        }
        .padding(.horizontal, 9)
        .frame(height: 25)
        .background(color.opacity(0.10), in: Capsule())
    }
}

// MARK: - Send

private struct SendDashboard: View {
    let theme: WWTheme
    let identity: Identity?
    let peers: [Peer]
    let transfer: ActiveTransfer?
    let recent: [URL]
    let companionOnline: Bool
    let onSend: (Peer) -> Void
    let onDropped: (URL, Peer) -> Void
    let onCancel: () -> Void
    let onOpenInbox: () -> Void
    let onOpenCompanion: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                PageHeader(
                    theme: theme,
                    eyebrow: "Nearby sharing",
                    title: "Send a file",
                    subtitle: "Choose a nearby device. Files travel directly over your local network."
                )

                if let transfer {
                    TransferPanel(theme: theme, transfer: transfer, onCancel: onCancel)
                }

                VStack(alignment: .leading, spacing: 12) {
                    SectionHeader(
                        theme: theme,
                        title: "Nearby devices",
                        detail: peers.isEmpty ? "Searching automatically" : "\(peers.count) available"
                    )
                    if peers.isEmpty {
                        DiscoveryEmptyState(theme: theme, localName: identity?.name ?? "This Mac")
                    } else {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 250), spacing: 12)], spacing: 12) {
                            ForEach(peers) { peer in
                                DeviceSendCard(
                                    theme: theme,
                                    peer: peer,
                                    onSend: { onSend(peer) },
                                    onDropped: { onDropped($0, peer) }
                                )
                            }
                        }
                    }
                }

                HStack(alignment: .top, spacing: 12) {
                    QuickActionCard(
                        theme: theme,
                        symbol: "doc.on.clipboard",
                        title: "Clipboard & keyboard",
                        detail: companionOnline
                            ? "Your Android companion is connected and ready."
                            : "Connect Android to sync text and type remotely.",
                        action: companionOnline ? "Open companion" : "Set up companion",
                        color: companionOnline ? theme.success : theme.accent,
                        onTap: onOpenCompanion
                    )
                    QuickActionCard(
                        theme: theme,
                        symbol: "tray.full",
                        title: "Recent files",
                        detail: recent.isEmpty
                            ? "Received files will be saved in Downloads/wigly-woo."
                            : "\(recent.count) recent \(recent.count == 1 ? "file" : "files") ready to open.",
                        action: "Open inbox",
                        color: theme.accent,
                        onTap: onOpenInbox
                    )
                }
            }
            .padding(32)
            .frame(maxWidth: 980)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
    }
}

private struct DiscoveryEmptyState: View {
    let theme: WWTheme
    let localName: String

    @State private var pulse = false

    var body: some View {
        HStack(spacing: 22) {
            ZStack {
                Circle()
                    .stroke(theme.accent.opacity(pulse ? 0.10 : 0.28), lineWidth: 1)
                    .frame(width: pulse ? 108 : 76, height: pulse ? 108 : 76)
                    .animation(.easeOut(duration: 1.8).repeatForever(autoreverses: false), value: pulse)
                Circle().fill(theme.accentSoft).frame(width: 58, height: 58)
                Image(systemName: "wifi")
                    .font(.system(size: 21, weight: .semibold))
                    .foregroundStyle(theme.accent)
            }
            .frame(width: 116, height: 116)

            VStack(alignment: .leading, spacing: 7) {
                Text("Looking for your devices")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(theme.text)
                Text("Open Wigly Woo on the other device and keep both devices on the same Wi‑Fi network.")
                    .font(.system(size: 12.5))
                    .foregroundStyle(theme.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 6) {
                    Image(systemName: "lock.fill")
                    Text("\(localName) is visible only on your local network")
                }
                .font(.system(size: 10.5, weight: .medium))
                .foregroundStyle(theme.tertiary)
                .padding(.top, 2)
            }
            Spacer()
        }
        .padding(22)
        .frame(maxWidth: .infinity, minHeight: 150)
        .wwCard(theme, shadow: true)
        .onAppear { pulse = true }
    }
}

private struct DeviceSendCard: View {
    let theme: WWTheme
    let peer: Peer
    let onSend: () -> Void
    let onDropped: (URL) -> Void

    @State private var dropTargeted = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous).fill(theme.accentSoft)
                    Image(systemName: "iphone")
                        .font(.system(size: 21, weight: .medium))
                        .foregroundStyle(theme.accent)
                }
                .frame(width: 46, height: 46)
                VStack(alignment: .leading, spacing: 3) {
                    Text(peer.name)
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(theme.text)
                        .lineLimit(1)
                    StatusPill(theme: theme, text: "Nearby", color: theme.success)
                }
                Spacer()
            }

            Text(dropTargeted ? "Drop to send" : "Send directly over Wi‑Fi")
                .font(.system(size: 11.5))
                .foregroundStyle(dropTargeted ? theme.accent : theme.secondary)

            Button(action: onSend) {
                Label("Choose a file", systemImage: "plus")
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(theme.onAccent)
                    .frame(maxWidth: .infinity)
                    .frame(height: 38)
                    .background(theme.accent, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
            }
            .buttonStyle(.plain)
        }
        .padding(16)
        .background(dropTargeted ? theme.accentSoft : theme.surface,
                    in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
            .stroke(dropTargeted ? theme.accent : theme.border, lineWidth: dropTargeted ? 1.5 : 1))
        .onDrop(of: [UTType.fileURL], isTargeted: $dropTargeted) { providers in
            guard let provider = providers.first else { return false }
            _ = provider.loadObject(ofClass: URL.self) { item, _ in
                guard let url = item else { return }
                DispatchQueue.main.async { onDropped(url) }
            }
            return true
        }
    }
}

private struct TransferPanel: View {
    let theme: WWTheme
    let transfer: ActiveTransfer
    let onCancel: () -> Void

    private var progress: Double {
        guard transfer.total > 0 else { return 0 }
        return min(1, Double(transfer.sent) / Double(transfer.total))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack(spacing: 12) {
                ZStack {
                    Circle().fill(theme.accentSoft)
                    Image(systemName: transfer.dir == "send" ? "arrow.up" : "arrow.down")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(theme.accent)
                }
                .frame(width: 36, height: 36)
                VStack(alignment: .leading, spacing: 2) {
                    Text(transfer.dir == "send" ? "Sending now" : "Receiving now")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(theme.accent)
                    Text(transfer.name)
                        .font(.system(size: 13.5, weight: .bold))
                        .foregroundStyle(theme.text)
                        .lineLimit(1)
                }
                Spacer()
                Text("\(Int(progress * 100))%")
                    .font(.system(size: 14, weight: .bold).monospacedDigit())
                    .foregroundStyle(theme.text)
                Button("Cancel", action: onCancel)
                    .buttonStyle(.plain)
                    .font(.system(size: 11.5, weight: .semibold))
                    .foregroundStyle(theme.secondary)
            }

            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(theme.border)
                    Capsule().fill(theme.accent).frame(width: proxy.size.width * progress)
                }
            }
            .frame(height: 6)

            HStack {
                Text("\(wwFmtBytes(transfer.sent)) of \(wwFmtBytes(transfer.total))")
                Spacer()
                Text(wwFmtSpeed(transfer.speed))
            }
            .font(.system(size: 10.5).monospacedDigit())
            .foregroundStyle(theme.tertiary)
        }
        .padding(18)
        .wwCard(theme, shadow: true)
    }
}

private struct QuickActionCard: View {
    let theme: WWTheme
    let symbol, title, detail, action: String
    let color: Color
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 13, style: .continuous).fill(color.opacity(0.11))
                    Image(systemName: symbol)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(color)
                }
                .frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.system(size: 13.5, weight: .bold))
                        .foregroundStyle(theme.text)
                    Text(detail)
                        .font(.system(size: 11))
                        .foregroundStyle(theme.secondary)
                        .lineLimit(2)
                }
                Spacer(minLength: 6)
                VStack(alignment: .trailing, spacing: 5) {
                    Image(systemName: "arrow.right")
                    Text(action)
                }
                .font(.system(size: 10.5, weight: .semibold))
                .foregroundStyle(color)
            }
            .padding(16)
            .frame(maxWidth: .infinity, minHeight: 82)
            .wwCard(theme)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Inbox

private struct InboxDashboard: View {
    let theme: WWTheme
    let files: [URL]
    let saveDirectory: URL
    let sessionTotal: Int64
    let onRefresh: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            PageHeader(
                theme: theme,
                eyebrow: "Received",
                title: "Inbox",
                subtitle: files.isEmpty
                    ? "Files sent to this Mac will appear here."
                    : "\(files.count) \(files.count == 1 ? "file" : "files") saved on this Mac.",
                actionTitle: "Refresh",
                action: onRefresh
            )

            HStack(spacing: 10) {
                InfoChip(theme: theme, symbol: "folder", text: "Downloads/wigly-woo")
                InfoChip(theme: theme, symbol: "arrow.left.arrow.right",
                         text: "\(wwFmtBytes(sessionTotal)) this session")
                Spacer()
                Button {
                    NSWorkspace.shared.open(saveDirectory)
                } label: {
                    Label("Open folder", systemImage: "arrow.up.forward.app")
                        .font(.system(size: 11.5, weight: .semibold))
                        .foregroundStyle(theme.accent)
                }
                .buttonStyle(.plain)
            }

            if files.isEmpty {
                VStack(spacing: 12) {
                    ZStack {
                        Circle().fill(theme.accentSoft).frame(width: 68, height: 68)
                        Image(systemName: "tray")
                            .font(.system(size: 25, weight: .medium))
                            .foregroundStyle(theme.accent)
                    }
                    Text("Your inbox is ready")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(theme.text)
                    Text("Send something from Android and accept it here. It will be saved automatically.")
                        .font(.system(size: 12.5))
                        .foregroundStyle(theme.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 380)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .wwCard(theme)
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(files, id: \.self) { url in
                            InboxFileRow(theme: theme, url: url)
                        }
                    }
                    .padding(1)
                }
            }
        }
        .padding(32)
        .frame(maxWidth: 980)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

private struct InfoChip: View {
    let theme: WWTheme
    let symbol, text: String
    var body: some View {
        Label(text, systemImage: symbol)
            .font(.system(size: 10.5, weight: .medium))
            .foregroundStyle(theme.tertiary)
            .padding(.horizontal, 10)
            .frame(height: 28)
            .background(theme.surface, in: Capsule())
            .overlay(Capsule().stroke(theme.border, lineWidth: 1))
    }
}

private struct InboxFileRow: View {
    let theme: WWTheme
    let url: URL
    @State private var hovering = false

    private var extensionLabel: String {
        let value = url.pathExtension.uppercased()
        return value.isEmpty ? "FILE" : String(value.prefix(5))
    }

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous).fill(theme.accentSoft)
                Text(extensionLabel)
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(theme.accent)
            }
            .frame(width: 44, height: 44)

            VStack(alignment: .leading, spacing: 4) {
                Text(url.lastPathComponent)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(theme.text)
                    .lineLimit(1)
                Text("\(wwFmtBytes(wwFileSize(url))) · \(wwFmtDate(url))")
                    .font(.system(size: 10.5))
                    .foregroundStyle(theme.tertiary)
            }
            Spacer()
            Button("Reveal") { NSWorkspace.shared.activateFileViewerSelecting([url]) }
                .buttonStyle(.plain)
                .font(.system(size: 11.5, weight: .semibold))
                .foregroundStyle(theme.secondary)
                .padding(.horizontal, 12)
                .frame(height: 32)
                .background(theme.background, in: Capsule())
            Button("Open") { NSWorkspace.shared.open(url) }
                .buttonStyle(.plain)
                .font(.system(size: 11.5, weight: .semibold))
                .foregroundStyle(theme.onAccent)
                .padding(.horizontal, 14)
                .frame(height: 32)
                .background(theme.accent, in: Capsule())
        }
        .padding(.horizontal, 14)
        .frame(height: 68)
        .background(hovering ? theme.surfaceRaised : theme.surface,
                    in: RoundedRectangle(cornerRadius: 15, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 15, style: .continuous)
            .stroke(hovering ? theme.accent.opacity(0.25) : theme.border, lineWidth: 1))
        .onHover { hovering = $0 }
    }
}

// MARK: - Companion

private struct CompanionDashboard: View {
    let theme: WWTheme
    let state: SupabaseRealtimeClient.State
    @ObservedObject var config: CompanionConfig
    let notificationsAuthorized: Bool
    let lastActivity: String
    @Binding var keyboardText: String
    let onSendText: () -> Void
    let onBackspace: () -> Void
    let onEnter: () -> Void
    let onSettings: () -> Void
    let onNotificationSettings: () -> Void

    private var online: Bool { state == .connected && config.enabled && config.isComplete }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                PageHeader(
                    theme: theme,
                    eyebrow: "Across networks",
                    title: "Android companion",
                    subtitle: "Sync notifications and clipboard, or type on your phone from this Mac.",
                    actionTitle: "Connection settings",
                    actionSymbol: "gearshape",
                    action: onSettings
                )

                CompanionHero(
                    theme: theme,
                    state: state,
                    configured: config.isComplete,
                    enabled: config.enabled,
                    onSetUp: onSettings
                )

                if config.enabled && config.notificationsEnabled && !notificationsAuthorized {
                    ActionNotice(
                        theme: theme,
                        color: theme.warning,
                        symbol: "bell.slash",
                        title: "Notification permission is off",
                        detail: "Allow Wigly Woo to show Android notifications on this Mac.",
                        actionTitle: "Open Settings",
                        action: onNotificationSettings
                    )
                }

                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 12) {
                        SectionHeader(theme: theme, title: "Services", detail: "Choose what stays in sync")
                        VStack(spacing: 0) {
                            CompanionServiceRow(
                                theme: theme,
                                symbol: "bell.fill",
                                title: "Notifications",
                                detail: serviceDetail(
                                    enabled: config.notificationsEnabled,
                                    ready: notificationsAuthorized
                                ),
                                active: online && config.notificationsEnabled && notificationsAuthorized,
                                toggle: Binding(
                                    get: { config.notificationsEnabled },
                                    set: { config.notificationsEnabled = $0; config.save() }
                                )
                            )
                            Divider().overlay(theme.border).padding(.leading, 56)
                            CompanionServiceRow(
                                theme: theme,
                                symbol: "doc.on.clipboard.fill",
                                title: "Clipboard",
                                detail: config.clipboardEnabled
                                    ? (online ? "Changes sync automatically" : "Waiting for connection")
                                    : "Turned off",
                                active: online && config.clipboardEnabled,
                                toggle: Binding(
                                    get: { config.clipboardEnabled },
                                    set: { config.clipboardEnabled = $0; config.save() }
                                )
                            )
                            Divider().overlay(theme.border).padding(.leading, 56)
                            CompanionServiceRow(
                                theme: theme,
                                symbol: "keyboard.fill",
                                title: "Remote keyboard",
                                detail: online ? "Ready to type on Android" : "Waiting for connection",
                                active: online,
                                toggle: nil
                            )
                        }
                        .wwCard(theme)

                        HStack(spacing: 7) {
                            Circle().fill(online ? theme.success : theme.tertiary).frame(width: 6, height: 6)
                            Text(lastActivity)
                                .font(.system(size: 10.5))
                                .foregroundStyle(theme.tertiary)
                                .lineLimit(1)
                        }
                        .padding(.horizontal, 4)
                    }
                    .frame(maxWidth: .infinity)

                    RemoteKeyboardCard(
                        theme: theme,
                        online: online,
                        text: $keyboardText,
                        onSend: onSendText,
                        onBackspace: onBackspace,
                        onEnter: onEnter
                    )
                    .frame(maxWidth: .infinity)
                }
            }
            .padding(32)
            .frame(maxWidth: 980)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
    }

    private func serviceDetail(enabled: Bool, ready: Bool) -> String {
        if !enabled { return "Turned off" }
        if !ready { return "Permission needed" }
        return online ? "Mirroring from Android" : "Waiting for connection"
    }
}

private struct CompanionHero: View {
    let theme: WWTheme
    let state: SupabaseRealtimeClient.State
    let configured, enabled: Bool
    let onSetUp: () -> Void

    private var values: (String, String, Color, Color, String) {
        if !configured {
            return ("Connect your Android", "Add the same relay details and pairing secret on both devices.",
                    theme.accent, theme.accentSoft, "Set up companion")
        }
        if !enabled {
            return ("Companion is paused", "Your pairing is saved. Turn it on whenever you need remote features.",
                    theme.tertiary, theme.surfaceRaised, "Resume in settings")
        }
        switch state {
        case .connected:
            return ("Android is connected", "Your encrypted companion channel is ready.",
                    theme.success, theme.successSoft, "Manage connection")
        case .connecting:
            return ("Connecting to Android…", "Wigly Woo is opening the encrypted companion channel.",
                    theme.warning, theme.warningSoft, "Check settings")
        case .error:
            return ("Reconnecting automatically", "Nearby file sharing still works while the companion reconnects.",
                    theme.danger, theme.dangerSoft, "Check settings")
        case .off:
            return ("Companion is offline", "Open connection settings to bring remote features online.",
                    theme.tertiary, theme.surfaceRaised, "Open settings")
        }
    }

    var body: some View {
        let (title, detail, color, soft, action) = values
        HStack(spacing: 18) {
            ZStack {
                RoundedRectangle(cornerRadius: 18, style: .continuous).fill(soft)
                Image(systemName: configured ? "iphone.and.arrow.forward" : "link.badge.plus")
                    .font(.system(size: 28, weight: .medium))
                    .foregroundStyle(color)
            }
            .frame(width: 72, height: 72)
            VStack(alignment: .leading, spacing: 5) {
                Text(title)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(theme.text)
                Text(detail)
                    .font(.system(size: 12.5))
                    .foregroundStyle(theme.secondary)
            }
            Spacer()
            Button(action: onSetUp) {
                Text(action)
                    .font(.system(size: 11.5, weight: .semibold))
                    .foregroundStyle(theme.onAccent)
                    .padding(.horizontal, 16)
                    .frame(height: 36)
                    .background(color == theme.tertiary ? theme.secondary : color, in: Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(20)
        .background(soft.opacity(0.55), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous)
            .stroke(color.opacity(0.20), lineWidth: 1))
    }
}

private struct CompanionServiceRow: View {
    let theme: WWTheme
    let symbol, title, detail: String
    let active: Bool
    let toggle: Binding<Bool>?

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 11, style: .continuous)
                    .fill(active ? theme.successSoft : theme.background)
                Image(systemName: symbol)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(active ? theme.success : theme.tertiary)
            }
            .frame(width: 36, height: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(theme.text)
                Text(detail)
                    .font(.system(size: 10.5))
                    .foregroundStyle(theme.tertiary)
            }
            Spacer()
            if let toggle {
                Toggle("", isOn: toggle)
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .controlSize(.small)
                    .tint(theme.accent)
            } else {
                StatusPill(theme: theme, text: active ? "Ready" : "Offline",
                           color: active ? theme.success : theme.tertiary)
            }
        }
        .padding(.horizontal, 14)
        .frame(height: 62)
    }
}

private struct RemoteKeyboardCard: View {
    let theme: WWTheme
    let online: Bool
    @Binding var text: String
    let onSend, onBackspace, onEnter: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 11) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Type on Android")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(theme.text)
                    Text(online ? "Text appears in the active phone field" : "Connect Android to start typing")
                        .font(.system(size: 10.5))
                        .foregroundStyle(theme.tertiary)
                }
                Spacer()
                StatusPill(theme: theme, text: online ? "Ready" : "Offline",
                           color: online ? theme.success : theme.tertiary)
            }
            TextField("Message, URL, search…", text: $text)
                .textFieldStyle(.plain)
                .font(.system(size: 12.5))
                .foregroundStyle(theme.text)
                .padding(.horizontal, 12)
                .frame(height: 40)
                .background(theme.background, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 11, style: .continuous)
                    .stroke(theme.border, lineWidth: 1))
                .disabled(!online)
                .onSubmit(onSend)
            HStack(spacing: 7) {
                keyboardButton(theme: theme, symbol: "delete.left", enabled: online, action: onBackspace)
                keyboardButton(theme: theme, symbol: "return", enabled: online, action: onEnter)
                Button(action: onSend) {
                    Label("Send to phone", systemImage: "paperplane.fill")
                        .font(.system(size: 11.5, weight: .semibold))
                        .foregroundStyle(canSend ? theme.onAccent : theme.tertiary)
                        .frame(maxWidth: .infinity)
                        .frame(height: 34)
                        .background(canSend ? theme.accent : theme.background,
                                    in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain)
                .disabled(!canSend)
            }
        }
        .padding(16)
        .wwCard(theme)
    }

    private var canSend: Bool { online && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    private func keyboardButton(theme: WWTheme, symbol: String, enabled: Bool,
                                action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(enabled ? theme.secondary : theme.tertiary)
                .frame(width: 36, height: 34)
                .background(theme.background, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

private struct ActionNotice: View {
    let theme: WWTheme
    let color: Color
    let symbol, title, detail, actionTitle: String
    let action: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(color)
                .frame(width: 30, height: 30)
                .background(color.opacity(0.10), in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 12.5, weight: .semibold)).foregroundStyle(theme.text)
                Text(detail).font(.system(size: 10.5)).foregroundStyle(theme.secondary)
            }
            Spacer()
            Button(actionTitle, action: action)
                .buttonStyle(.plain)
                .font(.system(size: 11.5, weight: .semibold))
                .foregroundStyle(color)
        }
        .padding(14)
        .background(color.opacity(0.08), in: RoundedRectangle(cornerRadius: 15, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 15, style: .continuous)
            .stroke(color.opacity(0.18), lineWidth: 1))
    }
}

// MARK: - Connection settings

private struct ConnectionSettingsSheet: View {
    let theme: WWTheme
    @ObservedObject var config: CompanionConfig
    @Environment(\.dismiss) private var dismiss

    @State private var url: String
    @State private var key: String
    @State private var secret: String
    @State private var enabled: Bool
    @State private var notifications: Bool
    @State private var clipboard: Bool
    @State private var revealKey = false
    @State private var revealSecret = false
    @State private var copied = false

    init(theme: WWTheme, config: CompanionConfig) {
        self.theme = theme
        self.config = config
        _url = State(initialValue: config.supabaseURL)
        _key = State(initialValue: config.publishableKey)
        _secret = State(initialValue: config.pairingSecret)
        _enabled = State(initialValue: config.enabled)
        _notifications = State(initialValue: config.notificationsEnabled)
        _clipboard = State(initialValue: config.clipboardEnabled)
    }

    private var normalizedURL: String {
        url.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    private var valid: Bool {
        normalizedURL.hasPrefix("https://")
            && !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && secret.trimmingCharacters(in: .whitespacesAndNewlines).count >= 20
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top, spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous).fill(theme.accentSoft)
                    Image(systemName: "link")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(theme.accent)
                }
                .frame(width: 46, height: 46)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Connect Android")
                        .font(.system(size: 21, weight: .bold, design: .rounded))
                        .foregroundStyle(theme.text)
                    Text("Use the same relay details and pairing secret on both devices.")
                        .font(.system(size: 12))
                        .foregroundStyle(theme.secondary)
                }
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(theme.secondary)
                        .frame(width: 30, height: 30)
                        .background(theme.background, in: Circle())
                }
                .buttonStyle(.plain)
            }
            .padding(.bottom, 20)

            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    settingsSection(number: "1", title: "Relay details",
                                    detail: "Your Supabase project carries encrypted companion messages.") {
                        VStack(spacing: 10) {
                            settingsField(label: "Project URL", value: $url,
                                          placeholder: "https://your-project.supabase.co")
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Publishable key")
                                    .font(.system(size: 10.5, weight: .semibold))
                                    .foregroundStyle(theme.secondary)
                                HStack(spacing: 8) {
                                    Group {
                                        if revealKey {
                                            TextField("Supabase publishable / anon key", text: $key)
                                        } else {
                                            SecureField("Supabase publishable / anon key", text: $key)
                                        }
                                    }
                                    .textFieldStyle(.plain)
                                    .font(.system(size: 12, design: .monospaced))
                                    .foregroundStyle(theme.text)
                                    .padding(.horizontal, 12)
                                    .frame(height: 40)
                                    .background(theme.background,
                                                in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                                    .overlay(RoundedRectangle(cornerRadius: 11, style: .continuous)
                                        .stroke(theme.border, lineWidth: 1))
                                    smallButton(revealKey ? "Hide" : "Show") { revealKey.toggle() }
                                }
                            }
                        }
                    }

                    settingsSection(number: "2", title: "Pairing secret",
                                    detail: "This is the private password for your devices. Keep it out of screenshots and chat.") {
                        VStack(alignment: .leading, spacing: 7) {
                            Text("Shared secret")
                                .font(.system(size: 10.5, weight: .semibold))
                                .foregroundStyle(theme.secondary)
                            HStack(spacing: 8) {
                                Group {
                                    if revealSecret {
                                        TextField("At least 20 characters", text: $secret)
                                    } else {
                                        SecureField("At least 20 characters", text: $secret)
                                    }
                                }
                                    .textFieldStyle(.plain)
                                    .font(.system(size: 12, design: .monospaced))
                                    .foregroundStyle(theme.text)
                                    .padding(.horizontal, 12)
                                    .frame(height: 40)
                                    .background(theme.background,
                                                in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                                    .overlay(RoundedRectangle(cornerRadius: 11, style: .continuous)
                                        .stroke(theme.border, lineWidth: 1))
                                smallButton(revealSecret ? "Hide" : "Show") {
                                    revealSecret.toggle()
                                }
                                smallButton("Generate") {
                                    secret = wwGenerateSecret()
                                    copied = false
                                }
                                smallButton(copied ? "Copied" : "Copy") {
                                    NSPasteboard.general.clearContents()
                                    NSPasteboard.general.setString(secret, forType: .string)
                                    copied = true
                                }
                                .disabled(secret.isEmpty)
                            }
                        }
                    }

                    settingsSection(number: "3", title: "Choose what stays connected",
                                    detail: "You can change these at any time from the Companion screen.") {
                        VStack(spacing: 0) {
                            SettingsToggleRow(
                                theme: theme,
                                symbol: "power",
                                title: "Keep companion connected",
                                detail: "Maintain the encrypted link in the background",
                                isOn: $enabled
                            )
                            Divider().overlay(theme.border).padding(.leading, 52)
                            SettingsToggleRow(
                                theme: theme,
                                symbol: "bell",
                                title: "Android notifications",
                                detail: "Show phone notifications on this Mac",
                                isOn: $notifications
                            )
                            Divider().overlay(theme.border).padding(.leading, 52)
                            SettingsToggleRow(
                                theme: theme,
                                symbol: "doc.on.clipboard",
                                title: "Clipboard sync",
                                detail: "Keep copied text available on both devices",
                                isOn: $clipboard
                            )
                        }
                        .background(theme.background,
                                    in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .stroke(theme.border, lineWidth: 1))
                    }

                    if enabled && !valid {
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundStyle(theme.warning)
                            Text("Finish the relay URL, publishable key, and pairing secret before turning the companion on.")
                                .font(.system(size: 11.5))
                                .foregroundStyle(theme.secondary)
                        }
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(theme.warningSoft,
                                    in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                }
            }

            HStack(spacing: 9) {
                Button("Cancel") { dismiss() }
                    .buttonStyle(.plain)
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(theme.secondary)
                    .frame(width: 92, height: 40)
                    .background(theme.background,
                                in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                Spacer()
                Button("Save connection") {
                    config.supabaseURL = normalizedURL
                    config.publishableKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
                    config.pairingSecret = secret.trimmingCharacters(in: .whitespacesAndNewlines)
                    config.enabled = enabled
                    config.notificationsEnabled = notifications
                    config.clipboardEnabled = clipboard
                    config.save()
                    dismiss()
                }
                .buttonStyle(.plain)
                .keyboardShortcut(.defaultAction)
                .disabled(enabled && !valid)
                .font(.system(size: 12.5, weight: .semibold))
                .foregroundStyle(enabled && !valid ? theme.tertiary : theme.onAccent)
                .padding(.horizontal, 20)
                .frame(height: 40)
                .background(enabled && !valid ? theme.background : theme.accent,
                            in: RoundedRectangle(cornerRadius: 11, style: .continuous))
            }
            .padding(.top, 18)
        }
        .padding(24)
        .frame(width: 650, height: 700)
        .background(theme.surface)
    }

    private func settingsSection<Content: View>(
        number: String,
        title: String,
        detail: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(number)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(theme.accent)
                .frame(width: 26, height: 26)
                .background(theme.accentSoft, in: Circle())
            VStack(alignment: .leading, spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.system(size: 13.5, weight: .bold))
                        .foregroundStyle(theme.text)
                    Text(detail)
                        .font(.system(size: 10.5))
                        .foregroundStyle(theme.tertiary)
                }
                content()
            }
        }
    }

    private func settingsField(label: String, value: Binding<String>, placeholder: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.system(size: 10.5, weight: .semibold))
                .foregroundStyle(theme.secondary)
            TextField(placeholder, text: value)
                .textFieldStyle(.plain)
                .font(.system(size: 12.5))
                .foregroundStyle(theme.text)
                .padding(.horizontal, 12)
                .frame(height: 40)
                .background(theme.background,
                            in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 11, style: .continuous)
                    .stroke(theme.border, lineWidth: 1))
        }
    }

    private func smallButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(title, action: action)
            .buttonStyle(.plain)
            .font(.system(size: 11.5, weight: .semibold))
            .foregroundStyle(theme.secondary)
            .padding(.horizontal, 13)
            .frame(height: 40)
            .background(theme.background,
                        in: RoundedRectangle(cornerRadius: 11, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 11, style: .continuous)
                .stroke(theme.border, lineWidth: 1))
    }
}

private struct SettingsToggleRow: View {
    let theme: WWTheme
    let symbol, title, detail: String
    @Binding var isOn: Bool

    var body: some View {
        HStack(spacing: 11) {
            Image(systemName: symbol)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(theme.accent)
                .frame(width: 28, height: 28)
                .background(theme.accentSoft, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(theme.text)
                Text(detail)
                    .font(.system(size: 10))
                    .foregroundStyle(theme.tertiary)
            }
            Spacer()
            Toggle("", isOn: $isOn)
                .labelsHidden()
                .toggleStyle(.switch)
                .controlSize(.small)
                .tint(theme.accent)
        }
        .padding(.horizontal, 12)
        .frame(height: 58)
    }
}

// MARK: - Incoming confirmation

private struct ModernIncomingOverlay: View {
    let theme: WWTheme
    let request: TrustRequest
    let onAccept, onDecline: () -> Void
    @State private var showFingerprint = false

    var body: some View {
        ZStack {
            Color.black.opacity(0.42).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 14) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 16, style: .continuous).fill(theme.accentSoft)
                        Image(systemName: "arrow.down")
                            .font(.system(size: 19, weight: .bold))
                            .foregroundStyle(theme.accent)
                    }
                    .frame(width: 54, height: 54)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Accept this file?")
                            .font(.system(size: 20, weight: .bold, design: .rounded))
                            .foregroundStyle(theme.text)
                        Text("\(request.name) wants to send you a file")
                            .font(.system(size: 12.5))
                            .foregroundStyle(theme.secondary)
                    }
                }

                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 11, style: .continuous).fill(theme.accentSoft)
                        Image(systemName: "doc.fill").foregroundStyle(theme.accent)
                    }
                    .frame(width: 42, height: 42)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(request.file)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(theme.text)
                            .lineLimit(1)
                        Text(request.size > 0 ? wwFmtBytes(request.size) : "File size unavailable")
                            .font(.system(size: 10.5))
                            .foregroundStyle(theme.tertiary)
                    }
                    Spacer()
                }
                .padding(12)
                .background(theme.background, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                Button {
                    showFingerprint.toggle()
                } label: {
                    Label(
                        showFingerprint
                            ? String(request.fingerprint.uppercased().prefix(23))
                            : "Verify sender fingerprint",
                        systemImage: "lock.shield"
                    )
                    .font(.system(size: 10.5, design: .monospaced))
                    .foregroundStyle(theme.tertiary)
                }
                .buttonStyle(.plain)

                HStack(spacing: 9) {
                    Button("Decline", action: onDecline)
                        .buttonStyle(.plain)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(theme.secondary)
                        .frame(maxWidth: .infinity)
                        .frame(height: 42)
                        .background(theme.background, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    Button("Accept file", action: onAccept)
                        .buttonStyle(.plain)
                        .keyboardShortcut(.defaultAction)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(theme.onAccent)
                        .frame(maxWidth: .infinity)
                        .frame(height: 42)
                        .background(theme.accent, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
            }
            .padding(22)
            .frame(width: 410)
            .wwCard(theme, radius: 22, shadow: true)
        }
    }
}

// MARK: - Formatting

private func wwFmtBytes(_ bytes: Int64) -> String {
    if bytes < 0 { return "Unknown size" }
    if bytes < 1024 { return "\(bytes) B" }
    let units = ["KB", "MB", "GB", "TB"]
    var value = Double(bytes)
    var index = -1
    repeat {
        value /= 1024
        index += 1
    } while value >= 1024 && index < units.count - 1
    return String(format: "%.1f %@", value, units[index])
}

private func wwFmtSpeed(_ bytesPerSecond: Double) -> String {
    guard bytesPerSecond > 0 else { return "Calculating speed…" }
    return "\(wwFmtBytes(Int64(bytesPerSecond)))/s"
}

private func wwFileSize(_ url: URL) -> Int64 {
    (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
}

private func wwFmtDate(_ url: URL) -> String {
    let date = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date()
    let formatter = DateFormatter()
    formatter.dateStyle = Calendar.current.isDateInToday(date) ? .none : .medium
    formatter.timeStyle = .short
    return formatter.string(from: date)
}

private func wwGenerateSecret() -> String {
    UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
}
