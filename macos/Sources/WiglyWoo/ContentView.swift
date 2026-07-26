import SwiftUI
import AppKit

// MARK: - v2 design tokens

struct V2 {
    let wallTop, wallBottom: Color
    let glass, glass2, stroke, hair: Color
    let tx, tx2, tx3: Color
    let ink, onInk: Color
    let ok, warn, warnBg, warnLine, err: Color
    let inset, chipBg: Color
    let shadow: Color

    static func of(_ scheme: ColorScheme) -> V2 {
        scheme == .dark
            ? V2(wallTop: Color(hex: 0x131418), wallBottom: Color(hex: 0x0e0f13),
                 glass: Color(hex: 0x1d1f25, alpha: 0.55), glass2: Color(hex: 0x24262d, alpha: 0.78),
                 stroke: Color.white.opacity(0.09), hair: Color.white.opacity(0.09),
                 tx: Color(hex: 0xf1f1ee), tx2: Color(hex: 0xa3a6ae), tx3: Color(hex: 0x686b74),
                 ink: Color(hex: 0xf1f1ee), onInk: Color(hex: 0x131418),
                 ok: Color(hex: 0x4ecb85), warn: Color(hex: 0xe8b45a),
                 warnBg: Color(hex: 0xe8b45a, alpha: 0.10), warnLine: Color(hex: 0xe8b45a, alpha: 0.28),
                 err: Color(hex: 0xee7b6f),
                 inset: Color.black.opacity(0.3), chipBg: Color.white.opacity(0.06),
                 shadow: Color.black.opacity(0.38))
            : V2(wallTop: Color(hex: 0xefede8), wallBottom: Color(hex: 0xe3e1db),
                 glass: Color.white.opacity(0.52), glass2: Color.white.opacity(0.75),
                 stroke: Color.white.opacity(0.75), hair: Color(hex: 0x191a20, alpha: 0.10),
                 tx: Color(hex: 0x17181c), tx2: Color(hex: 0x54565e), tx3: Color(hex: 0x8e9097),
                 ink: Color(hex: 0x17181c), onInk: Color(hex: 0xf4f3ef),
                 ok: Color(hex: 0x1f9d54), warn: Color(hex: 0xb57a12),
                 warnBg: Color(hex: 0xb57a12, alpha: 0.10), warnLine: Color(hex: 0xb57a12, alpha: 0.26),
                 err: Color(hex: 0xcf4a3f),
                 inset: Color.white.opacity(0.6), chipBg: Color(hex: 0x17181c, alpha: 0.05),
                 shadow: Color(hex: 0x1e1e28, alpha: 0.14))
    }
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xff) / 255,
                  green: Double((hex >> 8) & 0xff) / 255,
                  blue: Double(hex & 0xff) / 255,
                  opacity: alpha)
    }
}

private struct GlassCard: ViewModifier {
    let t: V2
    var radius: CGFloat = 20
    var prominent = false
    func body(content: Content) -> some View {
        content
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .background((prominent ? t.glass2 : t.glass), in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).stroke(t.stroke, lineWidth: 1))
            .shadow(color: t.shadow, radius: 14, y: 7)
    }
}

private extension View {
    func glassCard(_ t: V2, radius: CGFloat = 20, prominent: Bool = false) -> some View {
        modifier(GlassCard(t: t, radius: radius, prominent: prominent))
    }
}

private struct SectionLabel: View {
    let text: String
    let t: V2
    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 10.5, weight: .bold))
            .kerning(0.7)
            .foregroundStyle(t.tx3)
    }
}

private struct Spinner: View {
    let color: Color
    @State private var spin = false
    var body: some View {
        Circle()
            .trim(from: 0.18, to: 1)
            .stroke(color, style: StrokeStyle(lineWidth: 2, lineCap: .round))
            .frame(width: 12, height: 12)
            .rotationEffect(.degrees(spin ? 360 : 0))
            .animation(.linear(duration: 0.9).repeatForever(autoreverses: false), value: spin)
            .onAppear { spin = true }
    }
}

private struct StatusDot: View {
    let color: Color
    var size: CGFloat = 9
    var body: some View {
        Circle().fill(color)
            .frame(width: size, height: size)
            .background(Circle().fill(color.opacity(0.16)).frame(width: size + 6, height: size + 6))
    }
}

// MARK: - Root

struct LegacyContentView: View {
    @EnvironmentObject var core: CoreBridge
    @EnvironmentObject var companion: CompanionBridge
    @ObservedObject private var config = CompanionConfig.shared
    @Environment(\.colorScheme) private var scheme
    @State private var showingSettings = false
    @State private var keyboardText = ""

    private var t: V2 { V2.of(scheme) }

    var body: some View {
        ZStack {
            LinearGradient(colors: [t.wallTop, t.wallBottom], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()

            VStack(spacing: 0) {
                HeaderBar(t: t, capsule: capsule, onSettings: { showingSettings = true })
                BridgeView(t: t,
                           identity: core.identity,
                           peer: core.peers.first,
                           linkState: companion.state,
                           configComplete: config.isComplete,
                           transfer: core.active,
                           onSend: { peer in pickAndSend(to: peer) },
                           onCancel: { core.cancel() })
                HStack(alignment: .top, spacing: 14) {
                    LinkColumn(t: t,
                               state: companion.state,
                               config: config,
                               notificationsAuthorized: companion.notificationsAuthorized,
                               lastActivity: companion.lastActivity,
                               keyboardText: $keyboardText,
                               onSend: sendKeyboardText,
                               onBackspace: { companion.sendBackspace() },
                               onEnter: { companion.sendEnter() },
                               onNotificationSettings: { companion.openNotificationSettings() })
                        .frame(maxWidth: .infinity)
                    DropsColumn(t: t, received: core.received, sessionTotal: core.sessionTotal,
                                onRefresh: { core.refreshReceived() })
                        .frame(maxWidth: .infinity)
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 20)
                .padding(.top, 4)
            }

            if let req = core.pendingTrust {
                IncomingDropOverlay(t: t, request: req,
                                    onAccept: { core.answerTrust(req, accept: true) },
                                    onDecline: { core.answerTrust(req, accept: false) })
            }
        }
        .sheet(isPresented: $showingSettings) { LinkSetupSheet(t: t) }
    }

    private var capsule: (text: String, color: Color, spinning: Bool) {
        if let tr = core.active {
            let pct = tr.total > 0 ? Int(tr.sent * 100 / tr.total) : 0
            return ("\(tr.dir == "send" ? "Sending" : "Receiving") \(pct)%", t.tx, false)
        }
        switch companion.state {
        case .connected: return ("Linked · encrypted", t.ok, false)
        case .connecting: return ("Linking…", t.warn, true)
        case .error: return ("Link lost · retrying", t.err, false)
        case .off: return (config.isComplete ? "Link paused" : "Not paired", t.tx3, false)
        }
    }

    private func sendKeyboardText() {
        guard !keyboardText.isEmpty else { return }
        companion.sendKeyboardText(keyboardText)
        keyboardText = ""
    }

    private func pickAndSend(to peer: Peer) {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        if panel.runModal() == .OK, let url = panel.url { core.sendFile(to: peer, path: url.path) }
    }
}

// MARK: - Header

private struct HeaderBar: View {
    let t: V2
    let capsule: (text: String, color: Color, spinning: Bool)
    let onSettings: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            Text("wigly-woo")
                .font(.system(size: 17, weight: .heavy))
                .kerning(-0.4)
                .foregroundStyle(t.tx)
            Spacer()
            HStack(spacing: 9) {
                if capsule.spinning { Spinner(color: t.tx) } else { StatusDot(color: capsule.color) }
                Text(capsule.text)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(t.tx)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 7)
            .glassCard(t, radius: 100, prominent: true)

            Button(action: onSettings) {
                Image(systemName: "gearshape")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(t.tx2)
                    .frame(width: 34, height: 34)
                    .background(t.chipBg, in: Circle())
                    .overlay(Circle().stroke(t.hair, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .help("Link settings")
        }
        .padding(.leading, 78)   // clears the traffic lights with the hidden titlebar
        .padding(.trailing, 20)
        .padding(.top, 12)
        .padding(.bottom, 6)
    }
}

// MARK: - Bridge

private struct BridgeView: View {
    let t: V2
    let identity: Identity?
    let peer: Peer?
    let linkState: SupabaseRealtimeClient.State
    let configComplete: Bool
    let transfer: ActiveTransfer?
    let onSend: (Peer) -> Void
    let onCancel: () -> Void
    @State private var showFingerprint = false

    var body: some View {
        HStack(spacing: 0) {
            macNode
            stream
            phoneNode
        }
        .padding(.horizontal, 46)
        .padding(.top, 22)
        .padding(.bottom, 14)
    }

    private var macNode: some View {
        VStack(spacing: 8) {
            RoundedRectangle(cornerRadius: 30, style: .continuous)
                .fill(t.ink)
                .frame(width: 92, height: 92)
                .overlay(Image(systemName: "laptopcomputer")
                    .font(.system(size: 34, weight: .light))
                    .foregroundStyle(t.onInk))
                .shadow(color: t.shadow, radius: 20, y: 10)
            Text(identity?.name ?? "This Mac")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(t.tx)
                .lineLimit(1)
            Button(action: { showFingerprint.toggle() }) {
                Text(showFingerprint ? groupedFingerprint : "show fingerprint")
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(t.tx3)
            }
            .buttonStyle(.plain)
        }
        .frame(width: 150)
    }

    private var groupedFingerprint: String {
        let hex = (identity?.fingerprint ?? "").uppercased().prefix(16)
        return stride(from: 0, to: hex.count, by: 4).map { i -> String in
            let start = hex.index(hex.startIndex, offsetBy: i)
            let end = hex.index(start, offsetBy: min(4, hex.count - i))
            return String(hex[start..<end])
        }.joined(separator: "-")
    }

    private var streamCaption: String {
        if transfer != nil { return "" }
        switch linkState {
        case .connected: return "encrypted link · online"
        case .connecting: return "opening encrypted channel…"
        case .error: return "link lost · retrying automatically"
        case .off: return configComplete ? "link paused" : "no link · set up in settings"
        }
    }

    private var stream: some View {
        GeometryReader { geo in
            let flowing = linkState == .connected || linkState == .connecting
            let pct: CGFloat = {
                guard let tr = transfer, tr.total > 0 else { return 0 }
                return CGFloat(tr.sent) / CGFloat(tr.total)
            }()
            let chipX = geo.size.width * (0.08 + 0.84 * pct)
            let midY = geo.size.height / 2

            ZStack {
                DashStream(t: t, flowing: flowing)
                    .frame(height: 2)
                    .padding(.horizontal, geo.size.width * 0.06)
                    .position(x: geo.size.width / 2, y: midY)

                Text(streamCaption)
                    .font(.system(size: 11))
                    .kerning(0.2)
                    .foregroundStyle(t.tx3)
                    .position(x: geo.size.width / 2, y: midY + 18)

                if let tr = transfer {
                    let pctText = tr.total > 0 ? "\(Int(tr.sent * 100 / tr.total))%" : "…"
                    Text("\(fmtSpeed(tr.speed)) · \(fmtBytes(tr.sent)) of \(fmtBytes(tr.total))")
                        .font(.system(size: 10.5).monospacedDigit())
                        .foregroundStyle(t.tx3)
                        .position(x: geo.size.width / 2, y: midY - 44)

                    HStack(spacing: 7) {
                        Circle().fill(t.tx).frame(width: 7, height: 7)
                        Text(tr.name)
                            .font(.system(size: 11, weight: .semibold))
                            .lineLimit(1)
                            .frame(maxWidth: 150)
                            .foregroundStyle(t.tx)
                        Text(pctText)
                            .font(.system(size: 11, weight: .bold).monospacedDigit())
                            .foregroundStyle(t.tx)
                    }
                    .padding(.horizontal, 13)
                    .padding(.vertical, 6)
                    .glassCard(t, radius: 100, prominent: true)
                    .position(x: chipX, y: midY - 20)
                    .animation(.linear(duration: 0.14), value: chipX)

                    Button(action: onCancel) {
                        Text("Cancel")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(t.tx2)
                            .padding(.horizontal, 13)
                            .padding(.vertical, 4)
                            .background(t.chipBg, in: Capsule())
                            .overlay(Capsule().stroke(t.hair, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .position(x: geo.size.width / 2, y: midY + 40)
                }
            }
        }
        .frame(height: 110)
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var phoneNode: some View {
        VStack(spacing: 8) {
            if let peer {
                Button(action: { onSend(peer) }) {
                    RoundedRectangle(cornerRadius: 30, style: .continuous)
                        .fill(.ultraThinMaterial)
                        .frame(width: 92, height: 92)
                        .overlay(RoundedRectangle(cornerRadius: 30, style: .continuous).fill(t.glass2))
                        .overlay(RoundedRectangle(cornerRadius: 30, style: .continuous).stroke(t.stroke, lineWidth: 1))
                        .overlay(Image(systemName: "iphone")
                            .font(.system(size: 32, weight: .light))
                            .foregroundStyle(t.tx))
                        .shadow(color: t.shadow, radius: 14, y: 7)
                }
                .buttonStyle(.plain)
                .help("Send a file")
                Text(peer.name)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(t.tx)
                    .lineLimit(1)
                Button(action: { onSend(peer) }) {
                    Text("Send file…")
                        .font(.system(size: 11.5, weight: .semibold))
                        .foregroundStyle(t.onInk)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 6)
                        .background(t.ink, in: Capsule())
                }
                .buttonStyle(.plain)
            } else {
                RoundedRectangle(cornerRadius: 30, style: .continuous)
                    .strokeBorder(t.tx3, style: StrokeStyle(lineWidth: 1.5, dash: [5, 5]))
                    .frame(width: 92, height: 92)
                    .overlay(Spinner(color: t.tx2))
                    .opacity(0.75)
                Text("Scanning…")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(t.tx2)
                Text("Open wigly-woo on your\nphone nearby")
                    .font(.system(size: 10.5))
                    .foregroundStyle(t.tx3)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(width: 150)
    }
}

/// The dashed relay line: animated flow when the link is live, a static faint dash otherwise.
private struct DashStream: View {
    let t: V2
    let flowing: Bool

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: !flowing)) { ctx in
            let phase = flowing
                ? -CGFloat(ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.1) / 1.1) * 15
                : 0
            Line()
                .stroke(flowing ? t.tx2.opacity(0.8) : t.hair,
                        style: StrokeStyle(lineWidth: flowing ? 2 : 1.5,
                                           lineCap: .round,
                                           dash: flowing ? [7, 8] : [4, 8],
                                           dashPhase: phase))
        }
    }

    private struct Line: Shape {
        func path(in rect: CGRect) -> Path {
            var p = Path()
            p.move(to: CGPoint(x: rect.minX, y: rect.midY))
            p.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
            return p
        }
    }
}

// MARK: - Link column

private struct LinkColumn: View {
    let t: V2
    let state: SupabaseRealtimeClient.State
    @ObservedObject var config: CompanionConfig
    let notificationsAuthorized: Bool
    let lastActivity: String
    @Binding var keyboardText: String
    let onSend: () -> Void
    let onBackspace: () -> Void
    let onEnter: () -> Void
    let onNotificationSettings: () -> Void

    private var online: Bool { state == .connected }

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            SectionLabel(text: "Link", t: t).padding(.horizontal, 4)

            if !notificationsAuthorized && config.enabled && config.notificationsEnabled {
                Button(action: onNotificationSettings) {
                    HStack(spacing: 10) {
                        Circle().fill(t.warn).frame(width: 7, height: 7)
                        (Text("macOS is blocking notification banners. ")
                            .foregroundColor(t.tx2)
                         + Text("Open Notification Settings")
                            .foregroundColor(t.tx).fontWeight(.semibold))
                            .font(.system(size: 11.5))
                            .multilineTextAlignment(.leading)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                    .background(t.warnBg, in: RoundedRectangle(cornerRadius: 15, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 15, style: .continuous).stroke(t.warnLine, lineWidth: 1))
                }
                .buttonStyle(.plain)
            }

            VStack(spacing: 0) {
                tileRow(title: "Notifications",
                        subtitle: notifSubtitle,
                        active: online && config.notificationsEnabled,
                        toggle: Binding(get: { config.notificationsEnabled },
                                        set: { config.notificationsEnabled = $0; config.save() }))
                divider
                tileRow(title: "Clipboard",
                        subtitle: config.clipboardEnabled ? (online ? "Two-way sync live" : "Waiting for link") : "Off",
                        active: online && config.clipboardEnabled,
                        toggle: Binding(get: { config.clipboardEnabled },
                                        set: { config.clipboardEnabled = $0; config.save() }))
                divider
                tileRow(title: "Keyboard",
                        subtitle: online ? "Your keys type on the phone" : "Waiting for link",
                        active: online,
                        toggle: nil)
            }
            .glassCard(t)

            Text(lastActivity)
                .font(.system(size: 10.5))
                .foregroundStyle(t.tx3)
                .padding(.horizontal, 4)

            Spacer(minLength: 0)

            VStack(alignment: .leading, spacing: 9) {
                SectionLabel(text: "Type on Android", t: t)
                TextField("Message, URL, search…", text: $keyboardText)
                    .textFieldStyle(.plain)
                    .font(.system(size: 13))
                    .foregroundStyle(t.tx)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(t.inset, in: RoundedRectangle(cornerRadius: 13, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 13, style: .continuous).stroke(t.hair, lineWidth: 1))
                    .onSubmit(onSend)
                HStack(spacing: 7) {
                    keyButton("delete.left", help: "Backspace", action: onBackspace)
                    keyButton("return", help: "Return", action: onEnter)
                    Button(action: onSend) {
                        Text("Send to phone")
                            .font(.system(size: 12.5, weight: .semibold))
                            .foregroundStyle(sendDisabled ? t.tx3 : t.onInk)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                            .background(sendDisabled ? t.chipBg : t.ink,
                                        in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .disabled(sendDisabled)
                }
            }
            .padding(14)
            .glassCard(t)
        }
    }

    private var sendDisabled: Bool { keyboardText.isEmpty || state != .connected }

    private var notifSubtitle: String {
        if !config.notificationsEnabled { return "Off" }
        if !notificationsAuthorized { return "Needs permission" }
        return online ? "Mirroring from the phone" : "Waiting for link"
    }

    private var divider: some View {
        Rectangle().fill(t.hair).frame(height: 1).padding(.horizontal, 15)
    }

    private func tileRow(title: String, subtitle: String, active: Bool, toggle: Binding<Bool>?) -> some View {
        HStack(spacing: 11) {
            StatusDot(color: active ? t.ok : t.tx3, size: 8)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.system(size: 13, weight: .semibold)).foregroundStyle(t.tx)
                Text(subtitle).font(.system(size: 10.5)).foregroundStyle(t.tx3)
            }
            Spacer()
            if let toggle {
                Toggle("", isOn: toggle)
                    .toggleStyle(.switch)
                    .labelsHidden()
                    .controlSize(.small)
                    .tint(t.ink)
            }
        }
        .padding(.horizontal, 15)
        .padding(.vertical, 12)
    }

    private func keyButton(_ symbol: String, help: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(t.tx2)
                .frame(width: 36, height: 34)
                .background(t.chipBg, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(t.hair, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .help(help)
    }
}

// MARK: - Drops column

private struct DropsColumn: View {
    let t: V2
    let received: [URL]
    let sessionTotal: Int64
    let onRefresh: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(alignment: .firstTextBaseline, spacing: 9) {
                SectionLabel(text: "Drops", t: t)
                Text("Downloads/wigly-woo")
                    .font(.system(size: 10.5))
                    .foregroundStyle(t.tx3)
                Spacer()
                Button(action: onRefresh) {
                    Text("Refresh")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(t.tx2)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 4)
                        .background(t.chipBg, in: Capsule())
                        .overlay(Capsule().stroke(t.hair, lineWidth: 1))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 4)

            if received.isEmpty {
                VStack(spacing: 9) {
                    Circle()
                        .strokeBorder(t.tx3, style: StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
                        .frame(width: 52, height: 52)
                        .overlay(Image(systemName: "arrow.down.to.line")
                            .font(.system(size: 17))
                            .foregroundStyle(t.tx3))
                        .opacity(0.7)
                    Text("Nothing dropped yet")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(t.tx2)
                    Text("Files from your phone land here the moment you accept them.")
                        .font(.system(size: 11.5))
                        .foregroundStyle(t.tx3)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 250)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 52)
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 9), GridItem(.flexible())], spacing: 9) {
                        ForEach(received, id: \.self) { url in
                            FileCard(t: t, url: url)
                        }
                    }
                    .padding(2)
                }
            }

            Spacer(minLength: 0)
            Text("\(fmtBytes(sessionTotal)) moved this session")
                .font(.system(size: 11))
                .foregroundStyle(t.tx3)
                .frame(maxWidth: .infinity)
        }
    }
}

private struct FileCard: View {
    let t: V2
    let url: URL
    @State private var hovering = false

    private var ext: String {
        let e = url.pathExtension.uppercased()
        return e.isEmpty ? "FILE" : String(e.prefix(4))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(ext)
                    .font(.system(size: 9.5, weight: .bold))
                    .kerning(0.6)
                    .foregroundStyle(t.tx3)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(t.chipBg, in: Capsule())
                Spacer()
                Text(fmtDate(url))
                    .font(.system(size: 10))
                    .foregroundStyle(t.tx3)
            }
            Text(url.lastPathComponent)
                .font(.system(size: 12.5, weight: .semibold))
                .foregroundStyle(t.tx)
                .lineLimit(1)
                .padding(.top, 9)
            Text(fmtBytes(fileSize(url)))
                .font(.system(size: 10.5))
                .foregroundStyle(t.tx3)
                .padding(.top, 2)
            HStack(spacing: 6) {
                cardAction("Open", primary: true) { NSWorkspace.shared.open(url) }
                cardAction("Reveal", primary: false) { NSWorkspace.shared.activateFileViewerSelecting([url]) }
            }
            .padding(.top, 10)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 13)
        .glassCard(t, radius: 18, prominent: hovering)
        .onHover { hovering = $0 }
    }

    private func cardAction(_ label: String, primary: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(primary ? t.tx : t.tx2)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 5)
                .background(t.chipBg, in: Capsule())
                .overlay(Capsule().stroke(t.hair, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Incoming drop overlay

private struct IncomingDropOverlay: View {
    let t: V2
    let request: TrustRequest
    let onAccept: () -> Void
    let onDecline: () -> Void
    @State private var showFingerprint = false

    var body: some View {
        ZStack {
            Color.black.opacity(0.35).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 12) {
                    Circle()
                        .strokeBorder(t.tx3, style: StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
                        .frame(width: 46, height: 46)
                        .overlay(Image(systemName: "arrow.down")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(t.tx))
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Incoming drop")
                            .font(.system(size: 18, weight: .heavy))
                            .kerning(-0.3)
                            .foregroundStyle(t.tx)
                        Text("from \(request.name)")
                            .font(.system(size: 12))
                            .foregroundStyle(t.tx2)
                    }
                }
                HStack(spacing: 10) {
                    Text(extOf(request.file))
                        .font(.system(size: 9.5, weight: .bold))
                        .kerning(0.6)
                        .foregroundStyle(t.tx3)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(t.chipBg, in: Capsule())
                    Text(request.file)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(t.tx)
                        .lineLimit(1)
                    Spacer()
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 11)
                .background(t.inset, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(t.hair, lineWidth: 1))
                .padding(.top, 14)

                Button(action: { showFingerprint.toggle() }) {
                    Text(showFingerprint
                         ? "Sender: \(request.fingerprint.uppercased().prefix(19))"
                         : "Verify sender fingerprint")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(t.tx3)
                }
                .buttonStyle(.plain)
                .padding(.top, 10)

                HStack(spacing: 9) {
                    Button(action: onDecline) {
                        Text("Decline")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(t.tx2)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 11)
                            .background(t.chipBg, in: Capsule())
                            .overlay(Capsule().stroke(t.hair, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    Button(action: onAccept) {
                        Text("Accept")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(t.onInk)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 11)
                            .background(t.ink, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .keyboardShortcut(.defaultAction)
                }
                .padding(.top, 16)
            }
            .padding(20)
            .frame(width: 380)
            .glassCard(t, radius: 24, prominent: true)
            .transition(.scale(scale: 0.96).combined(with: .opacity))
        }
        .animation(.easeOut(duration: 0.2), value: request.fingerprint)
    }

    private func extOf(_ name: String) -> String {
        let e = (name as NSString).pathExtension.uppercased()
        return e.isEmpty ? "FILE" : String(e.prefix(4))
    }
}

// MARK: - Link setup sheet

struct LinkSetupSheet: View {
    let t: V2
    @ObservedObject private var config = CompanionConfig.shared
    @Environment(\.dismiss) private var dismiss
    @State private var showKey = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Link setup")
                .font(.system(size: 21, weight: .heavy))
                .kerning(-0.4)
                .foregroundStyle(t.tx)
            Text("Account-free testing mode — no device management yet. The pairing secret is the only lock on this channel; treat it like a password.")
                .font(.system(size: 11.5))
                .foregroundStyle(t.tx3)
                .padding(.top, 3)

            SectionLabel(text: "Relay", t: t).padding(.top, 20).padding(.bottom, 8)
            field(TextField("Supabase project URL", text: $config.supabaseURL), mono: false)
            HStack(spacing: 8) {
                if showKey {
                    field(TextField("Publishable / anon key", text: $config.publishableKey), mono: true)
                } else {
                    field(SecureField("Publishable / anon key", text: $config.publishableKey), mono: true)
                }
                chipButton(showKey ? "Hide" : "Show") { showKey.toggle() }
            }
            .padding(.top, 8)

            SectionLabel(text: "Pairing secret", t: t).padding(.top, 18).padding(.bottom, 8)
            HStack(spacing: 8) {
                field(TextField("Shared secret", text: $config.pairingSecret), mono: true)
                chipButton("Generate") { config.generateSecret() }
                chipButton("Copy") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(config.pairingSecret, forType: .string)
                }
            }

            SectionLabel(text: "Behavior", t: t).padding(.top, 18).padding(.bottom, 4)
            behaviorRow("Keep the link alive", isOn: $config.enabled)
            behaviorRow("Show Android notifications on this Mac", isOn: $config.notificationsEnabled)
            behaviorRow("Synchronize text clipboard", isOn: $config.clipboardEnabled)

            if config.enabled && !config.isComplete {
                Text("Configuration is incomplete — the link can't connect until the URL, key and a pairing secret of at least 20 characters are filled in.")
                    .font(.system(size: 11.5))
                    .foregroundStyle(t.tx2)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(t.warnBg, in: RoundedRectangle(cornerRadius: 13, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 13, style: .continuous).stroke(t.warnLine, lineWidth: 1))
                    .padding(.top, 12)
            }

            HStack {
                Spacer()
                Button(action: { config.save(); dismiss() }) {
                    Text("Done")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(t.onInk)
                        .padding(.horizontal, 28)
                        .padding(.vertical, 10)
                        .background(t.ink, in: Capsule())
                }
                .buttonStyle(.plain)
                .keyboardShortcut(.defaultAction)
            }
            .padding(.top, 18)
        }
        .padding(26)
        .frame(width: 600)
        .background(LinearGradient(colors: [t.wallTop, t.wallBottom], startPoint: .top, endPoint: .bottom))
    }

    private func field<F: View>(_ input: F, mono: Bool) -> some View {
        input
            .textFieldStyle(.plain)
            .font(mono ? .system(size: 12.5, design: .monospaced) : .system(size: 12.5))
            .foregroundStyle(t.tx)
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .frame(maxWidth: .infinity)
            .background(t.inset, in: RoundedRectangle(cornerRadius: 13, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 13, style: .continuous).stroke(t.hair, lineWidth: 1))
    }

    private func chipButton(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(t.tx2)
                .padding(.horizontal, 15)
                .padding(.vertical, 11)
                .background(t.chipBg, in: RoundedRectangle(cornerRadius: 13, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 13, style: .continuous).stroke(t.hair, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private func behaviorRow(_ label: String, isOn: Binding<Bool>) -> some View {
        HStack(spacing: 12) {
            Text(label)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(t.tx)
            Spacer()
            Toggle("", isOn: isOn)
                .toggleStyle(.switch)
                .labelsHidden()
                .controlSize(.small)
                .tint(t.ink)
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 2)
    }
}

// MARK: - Formatting helpers

private func fmtBytes(_ b: Int64) -> String {
    if b < 1024 { return "\(b) B" }
    let units = ["KB", "MB", "GB", "TB"]
    var v = Double(b); var i = -1
    repeat { v /= 1024; i += 1 } while v >= 1024 && i < units.count - 1
    return String(format: "%.1f %@", v, units[i])
}

private func fmtSpeed(_ bytesPerSec: Double) -> String {
    if bytesPerSec <= 0 { return "—" }
    let units = ["B/s", "KB/s", "MB/s", "GB/s"]
    var v = bytesPerSec; var i = 0
    while v >= 1024 && i < units.count - 1 { v /= 1024; i += 1 }
    return String(format: "%.1f %@", v, units[i])
}

private func fileSize(_ url: URL) -> Int64 { Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
private func fmtDate(_ url: URL) -> String {
    let date = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date()
    let formatter = DateFormatter(); formatter.dateFormat = "MMM d, HH:mm"
    return formatter.string(from: date)
}
