import AppKit
import AVFoundation
import Carbon
import CoreMedia
import Darwin
import SwiftUI
import WiglyMirror

/// One-click mirroring. The phone dials back on the LAN session this Mac is
/// already listening on.
final class MirrorController: ObservableObject {
    static let shared = MirrorController()

    enum Phase: String { case idle, requesting, connecting, streaming, ended }

    @Published var phase: Phase = .idle
    @Published var detail = "Your phone, in a window."
    @Published var shizuku = "Waiting for the phone"
    @Published var apps: [PhoneApp] = []
    @Published var recent: [RecentFile] = []
    @Published var latencyMs: Int = 0
    @Published var recording = false

    struct PhoneApp: Identifiable {
        let id: String
        let label: String
        let component: String
        let icon: NSImage?
    }
    struct RecentFile: Identifiable {
        let id: String
        let name: String
        let uri: String
    }

    private var videoFd: Int32 = -1
    private var audioFd: Int32 = -1
    private var controlFd: Int32 = -1
    private var metaFd: Int32 = -1
    private var sessionId = ""
    private var reader: Thread?
    private let decoder = VideoDecoder()
    private let audio = AudioPlayer()
    private var window: MirrorWindow?
    private var writer: AVAssetWriter?
    private var writerInput: AVAssetWriterInput?
    private var startedWriter = false
    private var pendingOpen: String?
    fileprivate var headless = false

    private init() {
        decoder.onSample = { [weak self] sample in
            DispatchQueue.main.async { self?.window?.enqueue(sample) }
        }
        decoder.onEncoded = { [weak self] sample in self?.appendRecording(sample) }
    }

    func toggle() {
        if phase == .streaming || phase == .connecting || phase == .requesting { stop() } else { start() }
    }

    func start(flags: UInt8 = 0, component: String = "") {
        guard CompanionBridge.shared.state == .connected else {
            detail = "Pair the phone first. Mirroring uses the companion link to start."
            phase = .ended
            return
        }
        guard let ip = lanIPv4(), let port = CoreBridge.shared.sessionPort, port > 0,
              let fp = CoreBridge.shared.identity?.fingerprint else {
            detail = "Turn on phone hotspot and connect"
            phase = .ended
            return
        }
        phase = .requesting
        detail = "Asking \(CompanionConfig.shared.phoneName)…"
        headless = flags & ControlCodec.flagHeadless != 0
        let config: [String: Any] = [
            "fps": 60, "bitrate": 8_000_000, "limit": 1080, "codec": 1,
            "flags": flags, "component": component,
        ]
        CompanionBridge.shared.send([
            "type": "mirror_request",
            "addr": "\(ip):\(port)",
            "fingerprint": fp,
            "config": config,
        ])
        if !headless { showWindow(aspect: 1080.0 / 2400.0) }
    }

    private var stopping = false

    func stop() {
        if stopping { return }
        stopping = true
        if !sessionId.isEmpty { sessionId.withCString { woo_session_close($0) } }
        finish(reason: "Stopped")
        CompanionBridge.shared.send(["type": "mirror_stop"])
        stopping = false
    }

    func launch(_ component: String) {
        guard controlFd >= 0 else {
            start(flags: ControlCodec.flagAppDisplay, component: component)
            return
        }
        Datagram.write(controlFd, ControlCodec.launch(component))
    }

    func button(_ id: UInt8) { guard controlFd >= 0 else { return }; Datagram.write(controlFd, ControlCodec.button(id)) }

    func screenOff(_ on: Bool) { Datagram.write(controlFd, ControlCodec.displayPower(on: !on)) }

    func toggleRecord() {
        recording.toggle()
        if recording { startRecording() } else { finishRecording() }
        if controlFd >= 0 { Datagram.write(controlFd, ControlCodec.record(on: recording)) }
    }

    func sendControl(_ data: Data) { guard controlFd >= 0 else { return }; Datagram.write(controlFd, data) }

    func noteDroppedFile(_ name: String) { pendingOpen = name }

    func consumeOpen(_ name: String) -> Bool {
        guard pendingOpen == name else { return false }
        pendingOpen = nil
        return true
    }

    func sessionOpened(_ obj: [String: Any]) {
        guard phase == .requesting || phase == .connecting || headless else { return }
        sessionId = obj["id"] as? String ?? ""
        videoFd = int32(obj["video"])
        audioFd = int32(obj["audio"])
        controlFd = int32(obj["control"])
        metaFd = int32(obj["meta"])
        phase = .connecting
        let size = window?.viewSize ?? CGSize(width: 360, height: 800)
        Datagram.write(controlFd, ControlCodec.config(
            width: UInt16(size.width), height: UInt16(size.height),
            fps: 60, bitrate: 8_000_000, limit: 1080, codec: 1,
            flags: headless ? ControlCodec.flagHeadless : 0))
        audio.start()
        reader = Thread { [weak self] in self?.readLoop() }
        reader?.start()
    }

    func sessionClosed(_ id: String) {
        guard id == sessionId || sessionId.isEmpty else { return }
        finish(reason: "The phone ended the session")
    }

    func failed(_ message: String) {
        if message == "basic" {
            shizuku = "Privileged features paused — restart Shizuku. Basic mirroring needs a tap on the phone."
            detail = "Basic mirroring"
        } else if message.localizedCaseInsensitiveContains("shizuku") {
            shizuku = "Privileged features paused — restart Shizuku"
            detail = shizuku
        } else {
            detail = message
        }
        phase = .ended
    }

    func ready(_ mode: String) {
        shizuku = mode == "basic" ? "Basic mirroring" : "Shizuku is ready"
        phase = .streaming
        detail = mode == "basic" ? "Basic mirroring" : "Live"
    }

    private func readLoop() {
        while phase == .connecting || phase == .streaming || phase == .requesting {
            if videoFd >= 0, let packet = Datagram.read(videoFd) {
                decoder.push(packet)
                let now = UInt64(Date().timeIntervalSince1970 * 1_000_000_000)
                if let media = Datagram.media(packet), media.pts > 0 {
                    let ms = Int((now &- media.pts) / 1_000_000)
                    if ms > 0 && ms < 5_000 { DispatchQueue.main.async { self.latencyMs = ms } }
                }
            }
            if audioFd >= 0, let packet = Datagram.read(audioFd) { audio.push(packet) }
            if metaFd >= 0, let packet = Datagram.read(metaFd),
               let obj = try? JSONSerialization.jsonObject(with: packet) as? [String: Any] {
                DispatchQueue.main.async { self.handleMeta(obj) }
            }
        }
    }

    private func handleMeta(_ obj: [String: Any]) {
        switch obj["type"] as? String {
        case "ready":
            phase = .streaming
            detail = "Live"
            if let w = obj["w"] as? Int, let h = obj["h"] as? Int, w > 0, h > 0, !headless {
                showWindow(aspect: CGFloat(w) / CGFloat(h))
            }
        case "error":
            failed(obj["reason"] as? String ?? "The phone stopped")
        case "apps":
            apps = (obj["apps"] as? [[String: Any]] ?? []).map { row in
                let component = row["component"] as? String ?? ""
                var image: NSImage?
                if let b64 = row["icon"] as? String, let data = Data(base64Encoded: b64) {
                    image = NSImage(data: data)
                }
                return PhoneApp(id: component, label: row["label"] as? String ?? component, component: component, icon: image)
            }
        case "recent":
            recent = (obj["files"] as? [[String: Any]] ?? []).map { row in
                let uri = row["uri"] as? String ?? ""
                return RecentFile(id: uri, name: row["name"] as? String ?? "Photo", uri: uri)
            }
        default:
            break
        }
    }

    private func finish(reason: String) {
        phase = .ended
        detail = reason
        decoder.stop()
        audio.stop()
        finishRecording()
        for fd in [videoFd, audioFd, controlFd, metaFd] where fd >= 0 { Darwin.close(fd) }
        videoFd = -1; audioFd = -1; controlFd = -1; metaFd = -1
        sessionId = ""
        let panel = window
        window = nil
        if !headless { panel?.close() }
    }

    private func showWindow(aspect: CGFloat) {
        if window == nil { window = MirrorWindow(controller: self) }
        window?.show(aspect: aspect)
    }

    private func startRecording() {
        let url = FileManager.default.urls(for: .moviesDirectory, in: .userDomainMask).first!
            .appendingPathComponent("Wigly-\(Int(Date().timeIntervalSince1970)).mov")
        writer = try? AVAssetWriter(outputURL: url, fileType: .mov)
        writerInput = AVAssetWriterInput(mediaType: .video, outputSettings: nil)
        writerInput?.expectsMediaDataInRealTime = true
        if let writer, let writerInput, writer.canAdd(writerInput) { writer.add(writerInput) }
        startedWriter = false
    }

    private func appendRecording(_ sample: CMSampleBuffer) {
        guard recording, let writer, let writerInput else { return }
        if !startedWriter {
            writer.startWriting()
            writer.startSession(atSourceTime: CMSampleBufferGetPresentationTimeStamp(sample))
            startedWriter = true
        }
        if writerInput.isReadyForMoreMediaData { writerInput.append(sample) }
    }

    private func finishRecording() {
        writerInput?.markAsFinished()
        writer?.finishWriting {}
        writer = nil
        writerInput = nil
        recording = false
    }

    private func int32(_ value: Any?) -> Int32 {
        if let n = value as? NSNumber { return n.int32Value }
        if let n = value as? Int { return Int32(n) }
        return -1
    }
}

func lanIPv4() -> String? {
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
    defer { freeifaddrs(first) }
    var ptr: UnsafeMutablePointer<ifaddrs>? = first
    while let current = ptr {
        if let addr = current.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) {
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                let ip = String(cString: host)
                if ip != "127.0.0.1" && !ip.hasPrefix("169.254.") { return ip }
            }
        }
        ptr = current.pointee.ifa_next
    }
    return nil
}

final class HotKey {
    static let shared = HotKey()
    private var hotKey: EventHotKeyRef?
    private var handler: EventHandlerRef?

    func install() {
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        InstallEventHandler(GetApplicationEventTarget(), { _, _, _ in
            DispatchQueue.main.async { MirrorController.shared.toggle() }
            return noErr
        }, 1, &spec, nil, &handler)
        let key = UInt32(UserDefaults.standard.object(forKey: "mirror.hotkey") as? Int ?? Int(kVK_ANSI_M))
        RegisterEventHotKey(key, UInt32(cmdKey | shiftKey), EventHotKeyID(signature: OSType(0x57474C59), id: 1), GetApplicationEventTarget(), 0, &hotKey)
    }
}

final class MirrorWindow: NSWindowController, NSWindowDelegate {
    private let display = AVSampleBufferDisplayLayer()
    private let host: MirrorVideoView
    private weak var controller: MirrorController?
    var viewSize: CGSize { host.frame.size }

    init(controller: MirrorController) {
        self.controller = controller
        host = MirrorVideoView(frame: NSRect(x: 0, y: 0, width: 390, height: 844))
        let window = NSWindow(
            contentRect: host.frame,
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered, defer: false)
        window.title = "Phone"
        window.backgroundColor = NSColor(srgbRed: 0.11, green: 0.105, blue: 0.102, alpha: 1)
        super.init(window: window)
        window.delegate = self
        window.contentView = host
        host.wantsLayer = true
        display.videoGravity = .resizeAspect
        display.frame = host.bounds
        host.layer?.addSublayer(display)
        host.onEvent = { [weak controller] data in controller?.sendControl(data) }
        host.onDropURL = { url in
            guard let peer = CoreBridge.shared.peers.first else { return }
            MirrorController.shared.noteDroppedFile(url.lastPathComponent)
            CoreBridge.shared.enqueue([url], to: peer)
        }
        window.aspectRatio = NSSize(width: 390, height: 844)
        window.collectionBehavior = [.fullScreenAuxiliary]
    }

    required init?(coder: NSCoder) { nil }

    func show(aspect: CGFloat) {
        let width: CGFloat = 390
        let height = max(200, width / max(aspect, 0.2))
        window?.setContentSize(NSSize(width: width, height: height))
        window?.aspectRatio = NSSize(width: width, height: height)
        display.frame = host.bounds
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func enqueue(_ sample: CMSampleBuffer) {
        if display.status == .failed { display.flush() }
        display.enqueue(sample)
    }

    func windowWillClose(_ notification: Notification) {
        let owner = controller
        controller = nil
        owner?.stop()
    }
}

final class MirrorVideoView: NSView {
    var onEvent: ((Data) -> Void)?
    var onDropURL: ((URL) -> Void)?
    private lazy var mapper = InputMapper(size: bounds.size)

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        registerForDraggedTypes([.fileURL])
    }
    required init?(coder: NSCoder) { nil }

    override func mouseDown(with event: NSEvent) { send(event) }
    override func mouseDragged(with event: NSEvent) { send(event) }
    override func mouseUp(with event: NSEvent) { send(event) }
    override func rightMouseDown(with event: NSEvent) { send(event) }
    override func otherMouseDown(with event: NSEvent) { send(event) }
    override func scrollWheel(with event: NSEvent) { send(event) }
    override func keyDown(with event: NSEvent) { sendKey(event) }
    override func keyUp(with event: NSEvent) { sendKey(event) }
    override var acceptsFirstResponder: Bool { true }

    override func layout() {
        super.layout()
        mapper = InputMapper(size: bounds.size)
        layer?.sublayers?.first?.frame = bounds
    }

    private func send(_ event: NSEvent) {
        let local = convert(event.locationInWindow, from: nil)
        guard let copy = event.copy() as? NSEvent else { return }
        // locationInWindow is what InputMapper reads. Convert by synthesizing
        // a mapper call with a view-local point through a stand-in size.
        let x = ControlCodec.norm(local.x, span: bounds.width)
        let y = ControlCodec.norm(bounds.height - local.y, span: bounds.height)
        switch event.type {
        case .rightMouseDown: onEvent?(ControlCodec.button(ControlCodec.back))
        case .otherMouseDown: onEvent?(ControlCodec.button(ControlCodec.home))
        case .scrollWheel:
            onEvent?(ControlCodec.scroll(x: x, y: y, dx: Int16(event.scrollingDeltaX), dy: Int16(event.scrollingDeltaY)))
        case .leftMouseDown: onEvent?(ControlCodec.touch(action: 0, pointer: 0, x: x, y: y))
        case .leftMouseDragged: onEvent?(ControlCodec.touch(action: 1, pointer: 0, x: x, y: y))
        case .leftMouseUp: onEvent?(ControlCodec.touch(action: 2, pointer: 0, x: x, y: y))
        default: if let data = mapper.mouse(event: copy) { onEvent?(data) }
        }
    }

    private func sendKey(_ event: NSEvent) {
        InputMapper(size: bounds.size).key(event: event).forEach { onEvent?($0) }
    }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation { .copy }
    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        guard let url = sender.draggingPasteboard.readObjects(forClasses: [NSURL.self])?.first as? URL else { return false }
        onDropURL?(url)
        return true
    }
}
