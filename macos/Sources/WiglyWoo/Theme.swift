import SwiftUI
import AppKit
import CoreText

// Broadsheet "Column" direction: one quiet serif, cyan for anything
// interactive, hierarchy from size and whitespace only.

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

struct WWTheme {
    let bg, surface, text, accent, link, error: Color
    let accentTint, attentionTint, tagBg, tagText, toggleOff, dotIdle: Color
    let shadow: Color

    var muted: Color { text.opacity(0.55) }
    var divider: Color { text.opacity(0.16) }
    var rule: Color { text.opacity(0.08) }
    var hover: Color { text.opacity(0.05) }
    var scrim: Color { Color(hex: 0x2d2b2b, alpha: 0.45) }

    static func make(_ scheme: ColorScheme) -> WWTheme {
        if scheme == .dark {
            return WWTheme(
                bg: Color(hex: 0x1c1b1a), surface: Color(hex: 0x272524), text: Color(hex: 0xecebe9),
                accent: Color(hex: 0x38a6cf), link: Color(hex: 0x62c5ee), error: Color(hex: 0xd6006c),
                accentTint: Color(hex: 0x0a303e), attentionTint: Color(hex: 0x4b1528),
                tagBg: Color(hex: 0x2d2b2b), tagText: Color(hex: 0xd7d3d3),
                toggleOff: Color(hex: 0x605d5d), dotIdle: Color(hex: 0x9b9797),
                shadow: .black.opacity(0.55))
        }
        return WWTheme(
            bg: Color(hex: 0xf3f2f2), surface: Color(hex: 0xeae9e9), text: Color(hex: 0x201e1d),
            accent: Color(hex: 0x0088b0), link: Color(hex: 0x006786), error: Color(hex: 0xd6006c),
            accentTint: Color(hex: 0xe9f8ff), attentionTint: Color(hex: 0xfff1f4),
            tagBg: Color(hex: 0xf8f4f4), tagText: Color(hex: 0x444141),
            toggleOff: Color(hex: 0xbab6b6), dotIdle: Color(hex: 0x9b9797),
            shadow: Color(hex: 0x2d2b2b, alpha: 0.22))
    }
}

enum WWFont {
    private static var registered = false
    private static var available = false

    /// Registers the bundled Source Serif 4 files. Falls back to the system
    /// serif (New York) when running unbundled via `swift run`.
    static func register() {
        guard !registered else { return }
        registered = true
        let dirs = [Bundle.main.resourceURL?.appendingPathComponent("Fonts"),
                    URL(fileURLWithPath: #filePath).deletingLastPathComponent()
                        .appendingPathComponent("../../Resources/Fonts").standardized]
        for dir in dirs.compactMap({ $0 }) {
            for name in ["SourceSerif4-Regular", "SourceSerif4-SemiBold"] {
                let url = dir.appendingPathComponent("\(name).ttf")
                if FileManager.default.fileExists(atPath: url.path) {
                    CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
                }
            }
        }
        available = NSFont(name: "SourceSerif4-Regular", size: 12) != nil
    }

    static func serif(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        if available {
            return .custom(weight == .regular ? "SourceSerif4-Regular" : "SourceSerif4-SemiBold", size: size)
        }
        return .system(size: size, weight: weight, design: .serif)
    }
}

// MARK: - Primitives

enum WWButtonKind { case primary, secondary, ghost }

struct WWButtonStyle: ButtonStyle {
    let theme: WWTheme
    let kind: WWButtonKind
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        let pressed = configuration.isPressed
        return configuration.label
            .font(WWFont.serif(14, .semibold))
            .foregroundStyle(foreground)
            .padding(.horizontal, kind == .ghost ? 5 : 18)
            .frame(minHeight: 34)
            .background(background(pressed), in: RoundedRectangle(cornerRadius: 2))
            .overlay(RoundedRectangle(cornerRadius: 2)
                .stroke(kind == .secondary ? theme.divider : .clear, lineWidth: 1))
            .opacity(enabled ? 1 : 0.45)
            .contentShape(Rectangle())
    }

    private var foreground: Color {
        switch kind {
        case .primary: return theme.bg
        case .secondary: return theme.text
        case .ghost: return theme.link
        }
    }

    private func background(_ pressed: Bool) -> Color {
        switch kind {
        case .primary: return pressed ? theme.link : theme.accent
        case .secondary: return pressed ? theme.text.opacity(0.14) : .clear
        case .ghost: return pressed ? theme.accent.opacity(0.18) : .clear
        }
    }
}

extension View {
    func wwButton(_ theme: WWTheme, _ kind: WWButtonKind) -> some View {
        buttonStyle(WWButtonStyle(theme: theme, kind: kind))
    }
}

struct Kicker: View {
    let theme: WWTheme
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(WWFont.serif(11))
            .kerning(1.1)
            .foregroundStyle(theme.link)
    }
}

struct StatusDot: View {
    let theme: WWTheme
    let status: CompanionStatus
    var size: CGFloat = 8

    var body: some View {
        switch status {
        case .connecting:
            Circle().strokeBorder(theme.accent, lineWidth: 1.5).frame(width: size, height: size)
        case .connected:
            Circle().fill(theme.accent).frame(width: size, height: size)
        case .error:
            Circle().fill(theme.error).frame(width: size, height: size)
        default:
            Circle().fill(theme.dotIdle).frame(width: size, height: size)
        }
    }
}

struct WWToggle: View {
    let theme: WWTheme
    @Binding var isOn: Bool

    var body: some View {
        Button { isOn.toggle() } label: {
            ZStack(alignment: isOn ? .trailing : .leading) {
                RoundedRectangle(cornerRadius: 2).fill(isOn ? theme.accent : theme.toggleOff)
                RoundedRectangle(cornerRadius: 1).fill(theme.bg).frame(width: 14, height: 14).padding(3)
            }
            .frame(width: 34, height: 20)
            .animation(.easeOut(duration: 0.15), value: isOn)
        }
        .buttonStyle(.plain)
    }
}

struct Tag: View {
    let theme: WWTheme
    let text: String
    var body: some View {
        Text(text)
            .font(WWFont.serif(11))
            .foregroundStyle(theme.tagText)
            .padding(.horizontal, 10).padding(.vertical, 3)
            .background(theme.tagBg, in: RoundedRectangle(cornerRadius: 1.5))
    }
}

struct RuleRow<Content: View>: View {
    let theme: WWTheme
    var minHeight: CGFloat = 58
    @ViewBuilder let content: () -> Content
    var body: some View {
        HStack(spacing: 20) { content() }
            .frame(maxWidth: .infinity, minHeight: minHeight, alignment: .leading)
            .overlay(alignment: .bottom) { Rectangle().fill(theme.rule).frame(height: 1) }
    }
}

struct WWField: View {
    let theme: WWTheme
    let placeholder: String
    @Binding var text: String
    var secure = false

    var body: some View {
        Group {
            if secure { SecureField(placeholder, text: $text) } else { TextField(placeholder, text: $text) }
        }
        .textFieldStyle(.plain)
        .font(WWFont.serif(14))
        .foregroundStyle(theme.text)
        .padding(.horizontal, 10)
        .frame(minHeight: 36)
        .background(theme.surface, in: RoundedRectangle(cornerRadius: 2))
        .overlay(RoundedRectangle(cornerRadius: 2).stroke(theme.divider, lineWidth: 1))
    }
}

// MARK: - Formatting

func wwFmtBytes(_ bytes: Int64) -> String {
    if bytes < 0 { return "Unknown size" }
    if bytes < 1000 { return "\(max(bytes, 1)) B" }
    let units = ["KB", "MB", "GB", "TB"]
    var value = Double(bytes)
    var index = -1
    repeat { value /= 1000; index += 1 } while value >= 1000 && index < units.count - 1
    return index == 0 ? "\(Int(value.rounded())) KB" : String(format: "%.1f %@", value, units[index])
}

func wwFmtSpeed(_ bytesPerSecond: Double) -> String {
    guard bytesPerSecond > 0 else { return "Calculating speed…" }
    return "\(wwFmtBytes(Int64(bytesPerSecond)))/s"
}

func wwFmtTime(_ date: Date) -> String {
    let cal = Calendar.current
    if Date().timeIntervalSince(date) < 60 { return "Just now" }
    let f = DateFormatter()
    if cal.isDateInToday(date) { f.timeStyle = .short; return f.string(from: date) }
    if cal.isDateInYesterday(date) { return "Yesterday" }
    f.setLocalizedDateFormatFromTemplate("MMM d")
    return f.string(from: date)
}
