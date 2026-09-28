import SwiftUI
import AppKit
import ServiceManagement
import UniformTypeIdentifiers
import CoreImage.CIFilterBuiltins

// MARK: - Shared UI state

enum Destination: String, CaseIterable {
    case send = "Send"
    case inbox = "Inbox"
    case companion = "Companion"

    var symbol: String {
        switch self {
        case .send: return "paperplane"
        case .inbox: return "tray"
        case .companion: return "link"
        }
    }
}

enum PairStage { case qr, confirm, manual }

/// Window-level UI state, shared with the menu bar extra so it can open
/// settings or pairing in the main window.
final class AppUI: ObservableObject {
    static let shared = AppUI()

    @Published var destination: Destination = .send
    @Published var showSettings = false
    @Published var pair: PairStage?
    @AppStorage("onboarded") var onboarded = false
    @AppStorage("showMenuBar") var showMenuBar = true

    var openAtLogin: Bool {
        get { SMAppService.mainApp.status == .enabled }
        set {
            do {
                if newValue { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            } catch {
                Toaster.shared.show("Open at login needs the installed app")
            }
            objectWillChange.send()
        }
    }

    func startPairing() {
        let config = CompanionConfig.shared
        showSettings = false
        guard config.hasRelay else { pair = .manual; return }
        if config.pairingSecret.count < 20 { config.generateSecret() }
        config.enabled = true
        config.save()
        pair = .qr
    }
}

private func statusCopy(_ status: CompanionStatus, phone: String) -> (String, String) {
    switch status {
    case .notSet: return ("Companion not set up", "Files still work nearby")
    case .paused: return ("Companion paused", "Nearby sharing is ready")
    case .connecting: return ("Connecting…", "Usually a moment")
    case .connected: return ("\(phone) connected", "Encrypted and ready")
    case .error: return ("Reconnecting", "Nearby still works")
    case .offline: return ("Companion offline", "Open settings to connect")
    }
}

private func capitalized(_ s: String) -> String { s.prefix(1).uppercased() + s.dropFirst() }

// MARK: - Root

struct ContentView: View {
    @EnvironmentObject private var core: CoreBridge
    @EnvironmentObject private var companion: CompanionBridge
    @ObservedObject private var ui = AppUI.shared
    @ObservedObject private var toaster = Toaster.shared
    @Environment(\.colorScheme) private var scheme

    private var theme: WWTheme { .make(scheme) }

    var body: some View {
        ZStack {
            theme.bg.ignoresSafeArea()
            if ui.onboarded {
                HStack(spacing: 0) {
                    Sidebar(theme: theme)
                        .frame(width: 208)
                    ScrollView {
                        Group {
                            switch ui.destination {
                            case .send: SendScreen(theme: theme)
                            case .inbox: InboxScreen(theme: theme)
                            case .companion: CompanionScreen(theme: theme)
                            }
                        }
                        .padding(.top, 44).padding(.leading, 20).padding(.trailing, 40).padding(.bottom, 40)
                        .frame(maxWidth: .infinity, alignment: .topLeading)
                    }
                    .scrollIndicators(.never)
                }
            } else {
                Onboarding(theme: theme)
            }

            if let request = core.pendingTrust {
                Modal(theme: theme) { IncomingDialog(theme: theme, request: request) }
            } else if let stage = ui.pair {
                Modal(theme: theme) { PairDialog(theme: theme, stage: stage) }
            } else if ui.showSettings {
                Modal(theme: theme) { SettingsDialog(theme: theme) }
            }

            if let message = toaster.message {
                VStack {
                    Spacer()
                    Text(message)
                        .font(WWFont.serif(14))
                        .foregroundStyle(theme.bg)
                        .padding(.horizontal, 16).padding(.vertical, 9)
                        .background(theme.text, in: RoundedRectangle(cornerRadius: 2))
                        .shadow(color: theme.shadow, radius: 8, y: 3)
                        .padding(.bottom, 22)
                }
                .transition(.opacity)
                .allowsHitTesting(false)
            }
        }
        .foregroundStyle(theme.text)
        .font(WWFont.serif(15))
        .animation(.easeOut(duration: 0.15), value: toaster.message)
    }
}

private struct Modal<Content: View>: View {
    let theme: WWTheme
    @ViewBuilder let content: () -> Content

    var body: some View {
        ZStack {
            theme.scrim.ignoresSafeArea()
            content()
                .padding(20)
                .background(theme.surface, in: RoundedRectangle(cornerRadius: 4))
                .shadow(color: theme.shadow, radius: 16, y: 12)
        }
    }
}

// MARK: - Sidebar

private struct Sidebar: View {
    let theme: WWTheme
    @EnvironmentObject private var companion: CompanionBridge
    @ObservedObject private var ui = AppUI.shared
    @ObservedObject private var config = CompanionConfig.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 40) {
            Text("wigly woo")
                .font(WWFont.serif(21, .semibold))
                .kerning(-0.4)
                .padding(.top, 44)
            VStack(alignment: .leading, spacing: 5) {
                ForEach(Destination.allCases, id: \.self) { item in
                    let selected = ui.destination == item
                    Button { ui.destination = item } label: {
                        HStack(spacing: 10) {
                            Image(systemName: item.symbol).font(.system(size: 15)).frame(width: 20)
                            Text(item.rawValue).font(WWFont.serif(15, selected ? .semibold : .regular))
                        }
                        .foregroundStyle(selected ? theme.link : theme.text)
                        .padding(.vertical, 6)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            Spacer()
            VStack(alignment: .leading, spacing: 10) {
                let (title, detail) = statusCopy(companion.status, phone: capitalized(config.phoneName))
                HStack(alignment: .top, spacing: 10) {
                    StatusDot(theme: theme, status: companion.status).padding(.top, 6)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(title).font(WWFont.serif(14, .semibold))
                        Text(detail).font(WWFont.serif(13)).foregroundStyle(theme.muted)
                    }
                }
                Button { ui.showSettings = true } label: {
                    Label("Settings", systemImage: "gearshape")
                }
                .wwButton(theme, .ghost)
                .padding(.leading, -5)
            }
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 20)
        .frame(maxHeight: .infinity, alignment: .top)
    }
}

// MARK: - Onboarding

private struct Onboarding: View {
    let theme: WWTheme
    @ObservedObject private var ui = AppUI.shared
    @State private var step = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 30) {
            Text("Step \(step + 1) of 3").font(WWFont.serif(13)).foregroundStyle(theme.muted)
            switch step {
            case 0:
                Text("Your Mac and your phone, linked.").font(WWFont.serif(34, .semibold))
                HStack(alignment: .top, spacing: 30) {
                    pillar("Nearby sharing", "Send files over Wi‑Fi. Nothing to set up.")
                    pillar("Companion", "Notifications, clipboard and typing over any network. Pair once.")
                }
                Button("Continue") { step = 1 }.wwButton(theme, .primary)
            case 1:
                VStack(alignment: .leading, spacing: 20) {
                    Text("Pair your phone").font(WWFont.serif(34, .semibold))
                    Text("The companion needs a one-time scan. Nearby sharing works without it.")
                        .foregroundStyle(theme.muted).frame(maxWidth: 440, alignment: .leading)
                    HStack(spacing: 10) {
                        Button("Show QR code") { step = 2; ui.startPairing() }.wwButton(theme, .primary)
                        Button("Later") { step = 2 }.wwButton(theme, .secondary)
                    }
                }
            default:
                VStack(alignment: .leading, spacing: 20) {
                    Text("Keep it close").font(WWFont.serif(34, .semibold))
                    MacToggles(theme: theme).frame(maxWidth: 440)
                    Button("Start") { ui.onboarded = true }.wwButton(theme, .primary)
                }
            }
        }
        .padding(.horizontal, 64).padding(.vertical, 48)
        .frame(maxWidth: 620 + 128, maxHeight: .infinity, alignment: .topLeading)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 28)
    }

    private func pillar(_ kicker: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Kicker(theme: theme, text: kicker)
            Text(text).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct MacToggles: View {
    let theme: WWTheme
    var showDetail = true
    @ObservedObject private var ui = AppUI.shared

    var body: some View {
        VStack(spacing: 0) {
            row("Open at login", "Stay ready without opening the app",
                Binding(get: { ui.openAtLogin }, set: { ui.openAtLogin = $0 }))
            row("Show in menu bar", "Status, transfers and recent files", $ui.showMenuBar)
        }
    }

    private func row(_ title: String, _ detail: String, _ isOn: Binding<Bool>) -> some View {
        RuleRow(theme: theme, minHeight: showDetail ? 58 : 48) {
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                if showDetail { Text(detail).font(WWFont.serif(13)).foregroundStyle(theme.muted) }
            }
            Spacer()
            WWToggle(theme: theme, isOn: isOn)
        }
    }
}

// MARK: - Send

private struct SendScreen: View {
    let theme: WWTheme
    @EnvironmentObject private var core: CoreBridge

    var body: some View {
        VStack(alignment: .leading, spacing: 40) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Send").font(WWFont.serif(34, .semibold))
                Text("Drop files on a device. They go straight over Wi‑Fi.").foregroundStyle(theme.muted)
            }

            if core.active != nil || !core.queue.isEmpty {
                TransferBlock(theme: theme)
            }

            VStack(alignment: .leading, spacing: 15) {
                Kicker(theme: theme, text: core.peers.isEmpty ? "Nearby" : "Nearby · \(core.peers.count)")
                if core.peers.isEmpty {
                    Searching(theme: theme, localName: core.identity?.name ?? "This Mac")
                } else {
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 20), GridItem(.flexible(), spacing: 20)],
                              spacing: 20) {
                        ForEach(core.peers) { DeviceCard(theme: theme, peer: $0) }
                    }
                }
            }

            Text("Received files save to Downloads/wigly-woo.")
                .font(WWFont.serif(13)).foregroundStyle(theme.muted)
        }
        .frame(maxWidth: 740, alignment: .leading)
    }
}

private struct TransferBlock: View {
    let theme: WWTheme
    @EnvironmentObject private var core: CoreBridge

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let t = core.active {
                let progress = t.total > 0 ? min(1, Double(t.sent) / Double(t.total)) : 0
                Kicker(theme: theme, text: t.dir == "send" ? "Sending" : "Receiving")
                HStack(alignment: .firstTextBaseline) {
                    (Text(t.name).font(WWFont.serif(17, .semibold))
                     + Text(t.peer.isEmpty ? "" : "  \(t.dir == "send" ? "to" : "from") \(t.peer)")
                        .font(WWFont.serif(17)).foregroundColor(theme.muted))
                        .lineLimit(1)
                    Spacer()
                    Text("\(Int(progress * 100))%").monospacedDigit()
                }
                ProgressLine(theme: theme, value: progress)
                HStack {
                    Text("\(wwFmtBytes(t.sent)) of \(wwFmtBytes(t.total)) · \(wwFmtSpeed(t.speed))")
                        .font(WWFont.serif(13)).monospacedDigit().foregroundStyle(theme.muted)
                    Spacer()
                    Button("Cancel", action: core.cancel).wwButton(theme, .ghost)
                }
            } else {
                Kicker(theme: theme, text: "Starting")
            }
            if !core.queue.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    Text("UP NEXT · \(core.queue.count)")
                        .font(WWFont.serif(11)).kerning(1.1).foregroundStyle(theme.muted)
                        .padding(.bottom, 4)
                    ForEach(core.queue) { item in
                        RuleRow(theme: theme, minHeight: 40) {
                            (Text(item.url.lastPathComponent)
                             + Text("  \(wwFmtBytes(item.size)) · to \(item.peer.name)").foregroundColor(theme.muted))
                                .font(WWFont.serif(14)).lineLimit(1)
                            Spacer()
                            Button("Remove") { core.removeQueued(item) }.wwButton(theme, .ghost)
                        }
                    }
                }
                .padding(.top, 15)
            }
        }
    }
}

struct ProgressLine: View {
    let theme: WWTheme
    let value: Double
    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Rectangle().fill(theme.divider)
                Rectangle().fill(theme.accent).frame(width: proxy.size.width * value)
            }
        }
        .frame(height: 2)
    }
}

private struct Searching: View {
    let theme: WWTheme
    let localName: String
    @State private var pulse = false

    var body: some View {
        HStack(spacing: 30) {
            ZStack {
                Circle().stroke(theme.divider, lineWidth: 1).frame(width: 96, height: 96)
                Circle().stroke(theme.divider, lineWidth: 1).frame(width: 56, height: 56)
                Circle().stroke(theme.accent.opacity(pulse ? 0 : 0.5), lineWidth: 1)
                    .frame(width: pulse ? 96 : 10, height: pulse ? 96 : 10)
                    .animation(.easeOut(duration: 2).repeatForever(autoreverses: false), value: pulse)
                Circle().fill(theme.accent).frame(width: 10, height: 10)
            }
            .frame(width: 96, height: 96)
            VStack(alignment: .leading, spacing: 4) {
                Text("Looking for your devices").font(WWFont.serif(20, .semibold))
                Text("Open Wigly Woo on the other device, on the same Wi‑Fi.")
                    .font(WWFont.serif(14)).foregroundStyle(theme.muted)
                Text("\(localName) is visible only on your local network.")
                    .font(WWFont.serif(12)).foregroundStyle(theme.muted).padding(.top, 4)
            }
        }
        .padding(.vertical, 20)
        .onAppear { pulse = true }
    }
}

private struct DeviceCard: View {
    let theme: WWTheme
    let peer: Peer
    @EnvironmentObject private var core: CoreBridge
    @State private var dropTargeted = false

    private var phoneSymbol: String {
        NSImage(systemSymbolName: "smartphone", accessibilityDescription: nil) != nil ? "smartphone" : "iphone"
    }

    var body: some View {
        VStack(alignment: .leading) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Image(systemName: phoneSymbol).font(.system(size: 24)).foregroundStyle(theme.accent)
                    Text(peer.name).font(WWFont.serif(20, .semibold)).lineLimit(1).padding(.top, 8)
                    Text("Nearby on Wi‑Fi").font(WWFont.serif(13)).foregroundStyle(theme.muted)
                }
                Spacer()
                if core.isTrusted(peer) { Tag(theme: theme, text: "Trusted") }
            }
            Spacer(minLength: 20)
            if dropTargeted {
                Text("Drop to send").font(WWFont.serif(22, .semibold)).foregroundStyle(theme.link)
            } else {
                HStack {
                    Text("Drop files here").font(WWFont.serif(13)).foregroundStyle(theme.muted)
                    Spacer()
                    Button("Choose files", action: choose).wwButton(theme, .secondary)
                }
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, minHeight: 180, alignment: .topLeading)
        .background(dropTargeted ? theme.accentTint : theme.surface, in: RoundedRectangle(cornerRadius: 2))
        .overlay(RoundedRectangle(cornerRadius: 2)
            .strokeBorder(dropTargeted ? theme.accent : .clear, style: StrokeStyle(lineWidth: 2, dash: [6, 4])))
        .onDrop(of: [UTType.fileURL], isTargeted: $dropTargeted) { providers in
            let group = DispatchGroup()
            let lock = NSLock()
            var urls: [URL] = []
            for provider in providers {
                group.enter()
                _ = provider.loadObject(ofClass: URL.self) { url, _ in
                    if let url { lock.lock(); urls.append(url); lock.unlock() }
                    group.leave()
                }
            }
            group.notify(queue: .main) {
                let files = urls.filter { !$0.hasDirectoryPath }
                if !files.isEmpty { core.enqueue(files, to: peer) }
            }
            return true
        }
    }

    private func choose() {
        let panel = NSOpenPanel()
        panel.title = "Choose files for \(peer.name)"
        panel.prompt = "Send"
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = true
        if panel.runModal() == .OK { core.enqueue(panel.urls, to: peer) }
    }
}

// MARK: - Inbox

private struct InboxScreen: View {
    let theme: WWTheme
    @EnvironmentObject private var core: CoreBridge
    @State private var showSent = false

    var body: some View {
        VStack(alignment: .leading, spacing: 30) {
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Inbox").font(WWFont.serif(34, .semibold))
                    Text("\(core.received.count) received · \(core.sent.count) sent · \(wwFmtBytes(core.sessionTotal)) this session")
                        .foregroundStyle(theme.muted)
                }
                Spacer()
                Segmented(theme: theme, options: ["Received", "Sent"],
                          selection: Binding(get: { showSent ? 1 : 0 }, set: { showSent = $0 == 1 }))
            }

            VStack(spacing: 0) {
                if showSent {
                    if core.sent.isEmpty { empty("Nothing sent yet. Drop files on a device to send them.") }
                    ForEach(Array(core.sent.enumerated()), id: \.offset) { _, r in
                        InboxRow(theme: theme, name: r.name, peerLine: "to \(r.peer)",
                                 size: r.size, date: r.date, url: nil)
                    }
                } else {
                    if core.received.isEmpty { empty("Nothing received yet. Accepted files land here.") }
                    ForEach(core.received, id: \.self) { url in
                        let peer = core.receivedFrom[url.lastPathComponent]
                        InboxRow(theme: theme, name: url.lastPathComponent,
                                 peerLine: peer.map { "from \($0)" } ?? "Received",
                                 size: fileSize(url), date: modDate(url), url: url)
                    }
                }
            }

            HStack(spacing: 10) {
                Text("Saved to Downloads/wigly-woo").font(WWFont.serif(13)).foregroundStyle(theme.muted)
                Button("Open folder") { NSWorkspace.shared.open(core.saveDir) }.wwButton(theme, .ghost)
            }
        }
        .frame(maxWidth: 760, alignment: .leading)
        .onAppear(perform: core.refreshReceived)
    }

    private func empty(_ text: String) -> some View {
        Text(text).foregroundStyle(theme.muted).padding(.vertical, 20)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func fileSize(_ url: URL) -> Int64 {
        (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
    }

    private func modDate(_ url: URL) -> Date {
        (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date()
    }
}

private struct InboxRow: View {
    let theme: WWTheme
    let name, peerLine: String
    let size: Int64
    let date: Date
    let url: URL?
    @State private var hovering = false

    private var ext: String {
        let e = (name as NSString).pathExtension.uppercased()
        return e.isEmpty ? "FILE" : String(e.prefix(4))
    }

    var body: some View {
        HStack(spacing: 15) {
            Text(ext).font(WWFont.serif(11)).kerning(0.9).foregroundStyle(theme.muted).frame(width: 52, alignment: .leading)
            VStack(alignment: .leading, spacing: 1) {
                Text(name).lineLimit(1).truncationMode(.middle)
                Text(peerLine).font(WWFont.serif(12)).foregroundStyle(theme.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(wwFmtBytes(size)).font(WWFont.serif(13)).monospacedDigit().foregroundStyle(theme.muted)
                .frame(width: 88, alignment: .leading)
            Text(wwFmtTime(date)).font(WWFont.serif(13)).foregroundStyle(theme.muted)
                .frame(width: 92, alignment: .leading)
            HStack(spacing: 4) {
                if let url {
                    Button("Reveal") { NSWorkspace.shared.activateFileViewerSelecting([url]) }.wwButton(theme, .ghost)
                    Button("Open") { NSWorkspace.shared.open(url) }.wwButton(theme, .ghost)
                }
            }
            .frame(width: 140, alignment: .trailing)
            .opacity(hovering ? 1 : 0)
        }
        .padding(.horizontal, 10)
        .frame(minHeight: 60)
        .background(hovering ? theme.hover : .clear)
        .overlay(alignment: .bottom) { Rectangle().fill(theme.rule).frame(height: 1) }
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
    }
}

struct Segmented: View {
    let theme: WWTheme
    let options: [String]
    @Binding var selection: Int

    var body: some View {
        HStack(spacing: 0) {
            ForEach(options.indices, id: \.self) { i in
                if i > 0 { Rectangle().fill(theme.divider).frame(width: 1) }
                Button { selection = i } label: {
                    Text(options[i]).font(WWFont.serif(13))
                        .foregroundStyle(selection == i ? theme.bg : theme.text)
                        .padding(.horizontal, 12).frame(minHeight: 32)
                        .background(selection == i ? theme.accent : .clear)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .fixedSize()
        .clipShape(RoundedRectangle(cornerRadius: 2))
        .overlay(RoundedRectangle(cornerRadius: 2).stroke(theme.divider, lineWidth: 1))
    }
}

// MARK: - Companion

private struct CompanionScreen: View {
    let theme: WWTheme
    @EnvironmentObject private var companion: CompanionBridge
    @ObservedObject private var config = CompanionConfig.shared
    @ObservedObject private var ui = AppUI.shared
    @State private var typed = ""

    private var connected: Bool { companion.status == .connected }
    private var phone: String { config.phoneName }

    var body: some View {
        // Two columns only when the main column keeps a comfortable measure;
        // otherwise the keyboard drops below and everything stays one column.
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: 40) {
                main.frame(minWidth: 440, maxWidth: .infinity, alignment: .leading)
                keyboard.frame(width: 290)
            }
            .frame(maxWidth: 800, alignment: .leading)

            VStack(alignment: .leading, spacing: 40) {
                main
                keyboard
            }
            .frame(maxWidth: 560, alignment: .leading)
        }
    }

    private var main: some View {
            VStack(alignment: .leading, spacing: 40) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Companion").font(WWFont.serif(34, .semibold))
                    Text("Notifications, clipboard and typing, over any network.").foregroundStyle(theme.muted)
                }
                hero
                VStack(alignment: .leading, spacing: 0) {
                    Kicker(theme: theme, text: "In sync").padding(.bottom, 4)
                    serviceRow("Notifications", notificationDetail, toggle: Binding(
                        get: { config.notificationsEnabled },
                        set: { config.notificationsEnabled = $0; config.save() }))
                    serviceRow("Clipboard",
                               !config.clipboardEnabled ? "Off" : connected ? "Syncs both ways" : "Waiting for connection",
                               toggle: Binding(get: { config.clipboardEnabled },
                                               set: { config.clipboardEnabled = $0; config.save() }))
                    RuleRow(theme: theme) {
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Remote keyboard")
                            Text(connected ? "Wigly keyboard is active on the phone" : "Needs the companion")
                                .font(WWFont.serif(13)).foregroundStyle(theme.muted)
                        }
                        Spacer()
                        Tag(theme: theme, text: connected ? "Ready" : "Offline")
                    }
                    Text(companion.lastActivity)
                        .font(WWFont.serif(13)).foregroundStyle(theme.muted).padding(.top, 15)
                }
                .opacity(connected ? 1 : 0.55)
            }
    }

    private var notificationDetail: String {
        if !config.notificationsEnabled { return "Off" }
        if !companion.notificationsAuthorized { return "Allow notifications in System Settings" }
        return connected ? "Mirroring from \(phone)" : "Waiting for connection"
    }

    private var hero: some View {
        // `urgent` CTAs are the next step; the rest are quiet links.
        let (title, detail, cta, action, urgent): (String, String, String?, (() -> Void)?, Bool) = {
            let settings = { ui.showSettings = true }
            switch companion.status {
            case .notSet:
                return ("Link your phone",
                        "Scan one code to sync notifications, clipboard and typing. Nearby sharing works without it.",
                        "Pair phone", ui.startPairing, true)
            case .paused:
                return ("Companion paused", "Your pairing is saved. Resume whenever you like.", "Resume",
                        { config.enabled = true; config.save() }, true)
            case .connecting:
                return ("Connecting to \(phone)…", "This usually takes a moment.", nil, nil, false)
            case .connected:
                let since = companion.connectedSince.map { " since \(DateFormatter.localizedString(from: $0, dateStyle: .none, timeStyle: .short))" } ?? ""
                return ("\(capitalized(phone)) is connected", "Encrypted relay\(since).", "Manage", settings, false)
            case .error:
                return ("Reconnecting", "Retrying on its own. Nearby sharing still works.", "Check settings", settings, false)
            case .offline:
                return ("Companion offline", "The link is on but not running.", "Open settings", settings, true)
            }
        }()
        return HStack(alignment: .firstTextBaseline, spacing: 12) {
            StatusDot(theme: theme, status: companion.status, size: 10)
                .alignmentGuide(.firstTextBaseline) { $0[.bottom] - 1 }
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(WWFont.serif(21, .semibold)).fixedSize(horizontal: false, vertical: true)
                if urgent || cta == nil {
                    Text(detail).font(WWFont.serif(14)).foregroundStyle(theme.muted)
                        .frame(maxWidth: 420, alignment: .leading)
                } else if let cta, let action {
                    HStack(spacing: 6) {
                        Text(detail).font(WWFont.serif(14)).foregroundStyle(theme.muted)
                        Button(cta, action: action).wwButton(theme, .ghost)
                    }
                }
                if urgent, let cta, let action {
                    Button(cta, action: action).wwButton(theme, .primary).padding(.top, 11)
                }
            }
        }
    }

    private func serviceRow(_ title: String, _ detail: String, toggle: Binding<Bool>) -> some View {
        RuleRow(theme: theme) {
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                Text(detail).font(WWFont.serif(13)).foregroundStyle(theme.muted)
            }
            Spacer()
            if title == "Notifications" && config.notificationsEnabled && !companion.notificationsAuthorized {
                Button("Open Settings", action: companion.openNotificationSettings).wwButton(theme, .ghost)
            }
            WWToggle(theme: theme, isOn: toggle)
        }
    }

    private var keyboard: some View {
        VStack(alignment: .leading, spacing: 15) {
            Kicker(theme: theme, text: "Type on \(phone)")
            TextField("Message, link, search…", text: $typed, axis: .vertical)
                .textFieldStyle(.plain)
                .font(WWFont.serif(15))
                .lineLimit(5...8)
                .onSubmit(send)
                .padding(10)
                .frame(minHeight: 120, alignment: .topLeading)
                .background(theme.surface, in: RoundedRectangle(cornerRadius: 2))
                .overlay(RoundedRectangle(cornerRadius: 2).stroke(theme.divider, lineWidth: 1))
            HStack(spacing: 5) {
                Button("⌫", action: companion.sendBackspace).wwButton(theme, .secondary)
                Button("↵", action: companion.sendEnter).wwButton(theme, .secondary)
                Spacer()
                Button("Send to phone", action: send).wwButton(theme, .primary)
                    .disabled(typed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            Text(connected ? "Text lands in whichever field is focused on the phone."
                 : "Connect \(phone) to start typing.")
                .font(WWFont.serif(13)).foregroundStyle(theme.muted)
        }
        .padding(.top, 6)
        .disabled(!connected)
        .opacity(connected ? 1 : 0.5)
    }

    private func send() {
        let value = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, connected else { return }
        companion.sendKeyboardText(value)
        typed = ""
        Toaster.shared.show("Typed on \(phone)")
    }
}

// MARK: - Incoming

private struct IncomingDialog: View {
    let theme: WWTheme
    let request: TrustRequest
    @EnvironmentObject private var core: CoreBridge
    @State private var showFingerprint = false
    @State private var always = false

    private var fingerprint: String {
        let hex = request.fingerprint.uppercased().filter { $0.isHexDigit }.prefix(12)
        return stride(from: 0, to: hex.count, by: 4).map { i in
            let start = hex.index(hex.startIndex, offsetBy: i)
            return String(hex[start..<hex.index(start, offsetBy: min(4, hex.count - i))])
        }.joined(separator: " ")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 15) {
            Text("\(request.name) wants to send you a file").font(WWFont.serif(13)).foregroundStyle(theme.muted)
            VStack(alignment: .leading, spacing: 2) {
                Text(request.file).font(WWFont.serif(25, .semibold)).lineLimit(2)
                Text(request.size > 0 ? wwFmtBytes(request.size) : "Size unavailable")
                    .font(WWFont.serif(13)).monospacedDigit().foregroundStyle(theme.muted)
            }
            HStack {
                Text("Sender fingerprint").font(WWFont.serif(13)).foregroundStyle(theme.muted)
                Spacer()
                if showFingerprint {
                    Text(fingerprint).kerning(1.5).monospacedDigit()
                } else {
                    Button("Verify") { showFingerprint = true }.wwButton(theme, .ghost)
                }
            }
            .frame(minHeight: 36)
            Button { always.toggle() } label: {
                HStack(spacing: 10) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(always ? theme.accent : .clear)
                            .overlay(RoundedRectangle(cornerRadius: 2).stroke(always ? theme.accent : theme.divider, lineWidth: 1.5))
                        if always {
                            Image(systemName: "checkmark").font(.system(size: 9, weight: .bold)).foregroundStyle(theme.bg)
                        }
                    }
                    .frame(width: 16, height: 16)
                    Text("Always accept from \(request.name)").font(WWFont.serif(14))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            HStack(spacing: 10) {
                Spacer()
                Button("Decline") { core.answerTrust(request, accept: false) }.wwButton(theme, .secondary)
                Button("Accept file") { core.answerTrust(request, accept: true, always: always) }
                    .wwButton(theme, .primary)
                    .keyboardShortcut(.defaultAction)
            }
            .padding(.top, 10)
        }
        .frame(width: 380)
    }
}

// MARK: - Pairing

private struct PairDialog: View {
    let theme: WWTheme
    let stage: PairStage
    @EnvironmentObject private var companion: CompanionBridge
    @ObservedObject private var config = CompanionConfig.shared
    @ObservedObject private var ui = AppUI.shared
    @State private var helloAtOpen = 0
    @State private var url = CompanionConfig.shared.supabaseURL
    @State private var key = CompanionConfig.shared.publishableKey
    @State private var secret = CompanionConfig.shared.pairingSecret

    private var relayValid: Bool {
        url.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("https://")
            && !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        Group {
            switch stage {
            case .qr: qr
            case .confirm: confirm
            case .manual: manual
            }
        }
        .frame(width: 480)
        .onAppear { helloAtOpen = companion.helloCount }
        .onChange(of: companion.helloCount) { count in
            if stage == .qr && count > helloAtOpen { ui.pair = .confirm }
        }
    }

    private var qr: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("Pair your phone").font(WWFont.serif(20, .semibold))
            HStack(spacing: 30) {
                QRCode(text: config.pairingURL(macName: Host.current().localizedName ?? "Mac"))
                    .frame(width: 176, height: 176)
                VStack(alignment: .leading, spacing: 10) {
                    Text("1   Open Wigly Woo on Android")
                    Text("2   Companion, then Scan code")
                    Text("3   Point the camera here")
                    HStack(spacing: 8) {
                        Circle().strokeBorder(theme.accent, lineWidth: 1.5).frame(width: 8, height: 8)
                        Text("Waiting for your phone…")
                    }
                    .font(WWFont.serif(13)).foregroundStyle(theme.muted).padding(.top, 10)
                }
                .font(WWFont.serif(14))
            }
            Text("The code carries your pairing secret. Keep it off screenshares.")
                .font(WWFont.serif(12)).foregroundStyle(theme.muted)
            HStack {
                Button("Enter details manually") { ui.pair = .manual }.wwButton(theme, .ghost)
                Spacer()
                Button("Cancel") { ui.pair = nil }.wwButton(theme, .secondary)
            }
        }
    }

    private var confirm: some View {
        VStack(alignment: .leading, spacing: 15) {
            Text("Confirm pairing").font(WWFont.serif(20, .semibold))
            Text("\(capitalized(config.phoneName)) scanned the code. Check the same number is on your phone.")
                .font(WWFont.serif(14)).opacity(0.85)
            Text(config.pairingCode).font(WWFont.serif(48, .semibold)).kerning(2).monospacedDigit()
            HStack(spacing: 10) {
                Spacer()
                Button("Cancel") { config.unpair(); ui.pair = nil }.wwButton(theme, .secondary)
                Button("Pair") {
                    ui.pair = nil
                    Toaster.shared.show("\(capitalized(config.phoneName)) paired")
                }
                .wwButton(theme, .primary)
                .keyboardShortcut(.defaultAction)
            }
            .padding(.top, 10)
        }
    }

    private var manual: some View {
        VStack(alignment: .leading, spacing: 15) {
            Text("Enter details manually").font(WWFont.serif(20, .semibold))
            Text("Use the same relay details and secret on both devices.").font(WWFont.serif(14)).opacity(0.85)
            field("Relay URL") { WWField(theme: theme, placeholder: "https://…", text: $url) }
            field("Publishable key") { WWField(theme: theme, placeholder: "", text: $key, secure: true) }
            field("Pairing secret") {
                HStack(spacing: 5) {
                    WWField(theme: theme, placeholder: "At least 20 characters", text: $secret, secure: true)
                    Button("Generate") { config.generateSecret(); secret = config.pairingSecret }
                        .wwButton(theme, .secondary)
                }
            }
            Text("Keep the secret out of screenshots and chat.").font(WWFont.serif(12)).foregroundStyle(theme.muted)
            HStack(spacing: 10) {
                Button("Show QR code") {
                    apply(secretRequired: false)
                    ui.startPairing()
                }
                .wwButton(theme, .ghost)
                .disabled(!relayValid)
                Spacer()
                Button("Cancel") { ui.pair = nil }.wwButton(theme, .secondary)
                Button("Save") {
                    apply(secretRequired: true)
                    ui.pair = nil
                    Toaster.shared.show("Companion saved")
                }
                .wwButton(theme, .primary)
                .disabled(!relayValid || secret.trimmingCharacters(in: .whitespacesAndNewlines).count < 20)
            }
            .padding(.top, 10)
        }
    }

    private func apply(secretRequired: Bool) {
        config.supabaseURL = url
        config.publishableKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
        let s = secret.trimmingCharacters(in: .whitespacesAndNewlines)
        if secretRequired || s.count >= 20 { config.pairingSecret = s }
        config.enabled = true
        config.save()
    }

    private func field<C: View>(_ label: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(label).font(WWFont.serif(12)).foregroundStyle(theme.text.opacity(0.7))
            content()
        }
    }
}

private struct QRCode: View {
    let text: String

    var body: some View {
        if let image = render() {
            Image(nsImage: image)
                .interpolation(.none)
                .resizable()
                .padding(10)
                .background(Color(hex: 0xf3f2f2), in: RoundedRectangle(cornerRadius: 4))
        }
    }

    private func render() -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let colored = output.applyingFilter("CIFalseColor", parameters: [
            "inputColor0": CIColor(red: 0x20 / 255, green: 0x1e / 255, blue: 0x1d / 255),
            "inputColor1": CIColor(red: 0xf3 / 255, green: 0xf2 / 255, blue: 0xf2 / 255),
        ])
        let rep = NSCIImageRep(ciImage: colored)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }
}

// MARK: - Settings

private struct SettingsDialog: View {
    let theme: WWTheme
    @EnvironmentObject private var core: CoreBridge
    @EnvironmentObject private var companion: CompanionBridge
    @ObservedObject private var config = CompanionConfig.shared
    @ObservedObject private var ui = AppUI.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 30) {
            Text("Settings").font(WWFont.serif(20, .semibold))

            VStack(alignment: .leading, spacing: 10) {
                Kicker(theme: theme, text: "Companion")
                HStack {
                    HStack(spacing: 10) {
                        StatusDot(theme: theme, status: companion.status)
                        Text(statusCopy(companion.status, phone: capitalized(config.phoneName)).0)
                    }
                    Spacer()
                    if config.isComplete {
                        Button("Unpair") { config.unpair(); Toaster.shared.show("Unpaired") }.wwButton(theme, .secondary)
                    } else {
                        Button("Pair phone", action: ui.startPairing).wwButton(theme, .secondary)
                    }
                }
                if config.isComplete {
                    HStack {
                        Text("Keep connected")
                        Spacer()
                        WWToggle(theme: theme, isOn: Binding(
                            get: { config.enabled }, set: { config.enabled = $0; config.save() }))
                    }
                    .frame(minHeight: 40)
                }
            }

            VStack(alignment: .leading, spacing: 5) {
                Kicker(theme: theme, text: "Trusted devices")
                if core.trusted.isEmpty {
                    Text("None yet. Trusted devices skip the accept prompt.")
                        .font(WWFont.serif(14)).foregroundStyle(theme.muted)
                } else {
                    ForEach(core.trusted.sorted(by: { $0.value < $1.value }), id: \.key) { entry in
                        HStack {
                            Text(entry.value)
                            Spacer()
                            Button("Remove") { core.setTrusted(entry.key, name: nil) }.wwButton(theme, .ghost)
                        }
                        .frame(minHeight: 40)
                    }
                }
            }

            VStack(alignment: .leading, spacing: 0) {
                Kicker(theme: theme, text: "This Mac")
                MacToggles(theme: theme, showDetail: false)
            }

            HStack {
                Spacer()
                Button("Done") { ui.showSettings = false }.wwButton(theme, .primary).keyboardShortcut(.defaultAction)
            }
        }
        .frame(width: 440)
    }
}

// MARK: - Menu bar extra

struct MenuBarLabel: View {
    @ObservedObject var companion: CompanionBridge
    var body: some View {
        Image(systemName: companion.status == .connected ? "w.circle.fill" : "w.circle")
    }
}

struct MenuBarPanel: View {
    @EnvironmentObject private var core: CoreBridge
    @EnvironmentObject private var companion: CompanionBridge
    @ObservedObject private var config = CompanionConfig.shared
    @ObservedObject private var ui = AppUI.shared
    @Environment(\.colorScheme) private var scheme
    @Environment(\.openWindow) private var openWindow

    private var theme: WWTheme { .make(scheme) }
    private var phone: String { capitalized(config.phoneName) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                StatusDot(theme: theme, status: companion.status)
                Text(statusCopy(companion.status, phone: phone).0).font(WWFont.serif(14, .semibold))
            }
            .padding(.horizontal, 8).padding(.vertical, 6)

            if let t = core.active {
                let progress = t.total > 0 ? min(1, Double(t.sent) / Double(t.total)) : 0
                VStack(spacing: 6) {
                    HStack {
                        Text(t.name).lineLimit(1)
                        Spacer()
                        Text("\(Int(progress * 100))%").monospacedDigit()
                    }
                    .font(WWFont.serif(13))
                    ProgressLine(theme: theme, value: progress)
                }
                .padding(.horizontal, 8).padding(.vertical, 6)
            }

            Spacer().frame(height: 10)
            item("Send clipboard to \(phone)") {
                let sent = companion.sendClipboardNow()
                Toaster.shared.show(sent ? "Clipboard sent to \(phone)" : "Queued. Sends when \(phone) reconnects")
            }
            if !core.received.isEmpty { Spacer().frame(height: 8) }
            ForEach(core.received.prefix(2), id: \.self) { url in
                item(url.lastPathComponent) { NSWorkspace.shared.open(url) }
            }
            Spacer().frame(height: 8)
            item("Open Wigly Woo") { show() }
            switch companion.status {
            case .connected, .connecting, .error:
                item("Pause companion") { config.enabled = false; config.save() }
            case .notSet:
                item("Pair phone…") { show(); ui.startPairing() }
            default:
                item("Resume companion") { config.enabled = true; config.save() }
            }
            item("Settings…") { show(); ui.showSettings = true }
            item("Quit Wigly Woo") { NSApp.terminate(nil) }
        }
        .padding(10)
        .frame(width: 290)
        .background(theme.surface)
        .foregroundStyle(theme.text)
    }

    private func show() {
        openWindow(id: "main")
        NSApp.activate(ignoringOtherApps: true)
    }

    private func item(_ title: String, action: @escaping () -> Void) -> some View {
        MenuRow(theme: theme, title: title, action: action)
    }
}

private struct MenuRow: View {
    let theme: WWTheme
    let title: String
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            Text(title).font(WWFont.serif(14)).lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 8).padding(.vertical, 6)
                .background(hovering ? theme.text.opacity(0.08) : .clear, in: RoundedRectangle(cornerRadius: 2))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
    }
}
