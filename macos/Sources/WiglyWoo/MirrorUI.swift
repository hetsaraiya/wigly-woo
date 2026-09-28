import AppKit
import AVFoundation
import Carbon
import CoreMedia
import CWooCore
import Darwin
import SwiftUI
import WiglyMirror

/// One-click mirroring. The phone dials back on the LAN session this Mac is
/// already listening on; the request itself goes over the companion relay.
final class MirrorController: ObservableObject {
    static let shared = MirrorController()

    enum Phase { case idle, requesting, connecting, streaming, ended }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var detail = "Your phone, in a window."
    @Published private(set) var shizuku = "Waiting for the phone"
    @Published private(set) var apps: [PhoneApp] = []
    @Published private(set) var recent: [RecentFile] = []
    @Published private(set) var recording = false
    @Published private(set) var screenDark = false

    struct PhoneApp: Identifiable {
        let id: String
        let label: String
        let icon: NSImage?
    }
    struct RecentFile: Identifiable {
        let id: String
        let name: String
    }

    var active: Bool { phase == .requesting || phase == .connecting || phase == .streaming }

    private var io: SessionIO?
    private var sessionId = ""
    private var headless = false
    private var timeout: DispatchWorkItem?
    private let audio = AudioPlayer()
    private let recorder = Recorder()
    private var window: MirrorWindow?
    private var pendingOpen: String?
    /// Decoded frames go straight from the decoder thread to the layer; a hop
    /// through the busy main thread adds a frame or more of lag.
    private let sink = LayerSink()

    func toggle() { if active { stop() } else { start() } }

    func start(flags: UInt8 = 0, component: String = "") {
        guard !active else { return }
        guard CompanionBridge.shared.state == .connected else {
            end("Pair the phone first. Mirroring uses the companion link to start.")
            return
        }
        let port = CoreBridge.shared.sessionPort
        guard port > 0, let ip = lanIPv4(), let fp = CoreBridge.shared.identity?.fingerprint else {
            end("This Mac is not on a network the phone can reach. Join the same Wi‑Fi, or use Hotspot.")
            return
        }
        headless = flags & ControlCodec.flagHeadless != 0
        phase = .requesting
        detail = "Asking \(CompanionConfig.shared.phoneName)…"
        CompanionBridge.shared.send([
            "type": "mirror_request",
            "addr": "\(ip):\(port)",
            "fingerprint": fp,
            "config": ["fps": 60, "bitrate": 8_000_000, "limit": 1080, "codec": 1, "flags": flags, "component": component],
        ])
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.phase == .requesting || self.phase == .connecting else { return }
            self.stop(reason: "The phone did not answer. Open Wigly Woo on the phone and try again.")
        }
        timeout = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 15, execute: work)
        if !headless { showWindow(aspect: 1080.0 / 2400.0) }
    }

    /// Universal control borrows the open window session, or starts one without video.
    func startHeadless() {
        if !active { start(flags: ControlCodec.flagHeadless) }
    }

    func stop(reason: String = "Stopped") {
        guard active else { return }
        if !sessionId.isEmpty { sessionId.withCString { woo_session_close($0) } }
        CompanionBridge.shared.send(["type": "mirror_stop"])
        end(reason)
    }

    func launch(_ component: String) {
        if phase == .streaming, let io { Datagram.write(io.control, ControlCodec.launch(component)) }
        else { start(flags: ControlCodec.flagAppDisplay, component: component) }
    }

    func button(_ id: UInt8) { sendControl(ControlCodec.button(id)) }

    func toggleScreen() {
        guard phase == .streaming else { return }
        screenDark.toggle()
        sendControl(ControlCodec.displayPower(on: !screenDark))
    }

    func toggleRecord() {
        if recording {
            recording = false
            recorder.finish()
        } else if phase == .streaming {
            recording = true
            recorder.start()
        }
    }

    func sendControl(_ data: Data) {
        guard let io else { return }
        Datagram.write(io.control, data)
    }

    func sendRecent(_ file: RecentFile) {
        CompanionBridge.shared.send(["type": "send_recent", "uri": file.id])
    }

    func noteDroppedFile(_ name: String) { pendingOpen = name }

    func consumeOpen(_ name: String) -> Bool {
        guard pendingOpen == name else { return false }
        pendingOpen = nil
        return true
    }

    // MARK: Core and relay events (main thread)

    func sessionOpened(_ obj: [String: Any]) {
        let fds = ["video", "audio", "control", "meta"].map { int32(obj[$0]) }
        guard phase == .requesting, obj["role"] as? String == "listen", fds.allSatisfy({ $0 >= 0 }) else {
            // Not ours to use: an old dial landing late.
            fds.filter { $0 >= 0 }.forEach { Darwin.close($0) }
            if let id = obj["id"] as? String { id.withCString { woo_session_close($0) } }
            return
        }
        sessionId = obj["id"] as? String ?? ""
        phase = .connecting
        let session = SessionIO(control: fds[2])
        io = session

        let decoder = VideoDecoder()
        decoder.onSample = { [sink] sample in sink.enqueue(sample) }
        decoder.onEncoded = { [recorder] sample in recorder.append(sample) }
        session.read(fds[0], capacity: 1 << 20, onData: decoder.push, onExit: decoder.stop)

        audio.start()
        session.read(fds[1], capacity: 256 << 10, onData: { [audio] in audio.push($0) })
        session.read(fds[3], capacity: 1 << 20, onData: { [weak self] packet in
            guard let obj = try? JSONSerialization.jsonObject(with: packet) as? [String: Any] else { return }
            DispatchQueue.main.async { self?.handleMeta(obj) }
        })
    }

    /// The phone's latest photos, sent over the relay when mirroring starts.
    func setRecent(_ rows: [[String: Any]]) {
        recent = rows.compactMap { row in
            guard let uri = row["uri"] as? String else { return nil }
            return RecentFile(id: uri, name: row["name"] as? String ?? "Photo")
        }
    }

    func sessionClosed(_ id: String) {
        guard !id.isEmpty, id == sessionId else { return }
        end("The phone ended mirroring")
    }

    /// The phone's reasons arrive as short codes or sentences.
    func failed(_ message: String) {
        guard active else { return }
        switch message {
        case "basic":
            timeout?.cancel() // the person may take a while to tap Allow
            shizuku = "Shizuku is not running. Tap Allow on the phone for basic mirroring."
            detail = "Waiting for you to allow basic mirroring on the phone"
        case let m where m.localizedCaseInsensitiveContains("shizuku"):
            shizuku = "Privileged features paused — restart Shizuku on the phone"
            end(shizuku)
        case "":
            end("The phone stopped mirroring")
        default:
            end(message)
        }
    }

    func ready(_ mode: String) {
        shizuku = mode == "basic" ? "Basic mirroring: taps and swipes only" : "Shizuku is ready"
        // Basic capture sends no "ready" on the session, so the relay says it.
        if mode == "basic", phase == .connecting {
            timeout?.cancel()
            phase = .streaming
            detail = "Basic mirroring"
        }
    }

    private func handleMeta(_ obj: [String: Any]) {
        switch obj["type"] as? String {
        case "ready":
            guard phase == .connecting else { return }
            timeout?.cancel()
            phase = .streaming
            detail = headless ? "Controlling the phone" : "Live"
            if let w = obj["w"] as? Int, let h = obj["h"] as? Int, w > 0, h > 0, !headless {
                showWindow(aspect: CGFloat(w) / CGFloat(h))
            }
        case "error":
            failed(obj["reason"] as? String ?? "The phone stopped")
        case "notice":
            if let reason = obj["reason"] as? String { Toaster.shared.show(reason) }
            screenDark = false
        case "apps":
            apps = (obj["apps"] as? [[String: Any]] ?? []).map { row in
                let component = row["component"] as? String ?? ""
                let icon = (row["icon"] as? String).flatMap { Data(base64Encoded: $0) }.flatMap(NSImage.init(data:))
                return PhoneApp(id: component, label: row["label"] as? String ?? component, icon: icon)
            }
        default:
            break
        }
    }

    private func end(_ reason: String) {
        timeout?.cancel()
        timeout = nil
        io?.cancel()
        io = nil
        sessionId = ""
        audio.stop()
        if recording { toggleRecord() }
        screenDark = false
        phase = .ended
        detail = reason
        sink.layer = nil
        let closing = window
        window = nil
        closing?.close()
        if headless { EdgeController.shared.release() }
        headless = false
    }

    private func showWindow(aspect: CGFloat) {
        if window == nil { window = MirrorWindow(controller: self) }
        sink.layer = window?.displayLayer
        window?.show(aspect: aspect)
    }

    fileprivate func windowClosed() {
        sink.layer = nil
        window = nil
        stop()
    }

    private func int32(_ value: Any?) -> Int32 { (value as? NSNumber)?.int32Value ?? -1 }
}

/// Hands decoded frames to the window's layer from any thread.
private final class LayerSink {
    private let lock = NSLock()
    private var _layer: AVSampleBufferDisplayLayer?

    var layer: AVSampleBufferDisplayLayer? {
        get { lock.lock(); defer { lock.unlock() }; return _layer }
        set { lock.lock(); _layer = newValue; lock.unlock() }
    }

    func enqueue(_ sample: CMSampleBuffer) {
        guard let layer else { return }
        if layer.status == .failed { layer.flush() }
        layer.enqueue(sample)
    }
}

/// The shell side of one session: reader threads own and close their sockets.
private final class SessionIO {
    let control: Int32
    private let lock = NSLock()
    private var cancelled = false

    init(control: Int32) { self.control = control }

    var isCancelled: Bool {
        lock.lock(); defer { lock.unlock() }
        return cancelled
    }

    func cancel() {
        lock.lock()
        let first = !cancelled
        cancelled = true
        lock.unlock()
        if first { Darwin.close(control) }
    }

    /// Reads until cancelled or the core closes the socket, then closes it.
    /// Closing here, not from the main thread, means a reused descriptor
    /// number can never be read by a stale thread.
    func read(_ fd: Int32, capacity: Int, onData: @escaping (Data) -> Void, onExit: @escaping () -> Void = {}) {
        let thread = Thread { [self] in
            let reader = DatagramReader(fd: fd, capacity: capacity)
            loop: while !isCancelled {
                switch reader.next() {
                case .data(let packet): onData(packet)
                case .idle: continue
                case .closed: break loop
                }
            }
            onExit()
            Darwin.close(fd)
        }
        thread.qualityOfService = .userInteractive
        thread.start()
    }
}

/// Passthrough recording of the phone stream to ~/Movies.
private final class Recorder {
    private let queue = DispatchQueue(label: "wigly.record")
    private var armed = false
    private var writer: AVAssetWriter?
    private var input: AVAssetWriterInput?

    func start() { queue.async { self.armed = true } }

    func append(_ sample: CMSampleBuffer) {
        queue.async {
            guard self.armed else { return }
            if self.writer == nil {
                // A file must start on a keyframe.
                guard Self.isSync(sample), let format = CMSampleBufferGetFormatDescription(sample) else { return }
                self.open(format: format, at: CMSampleBufferGetPresentationTimeStamp(sample))
            }
            if let input = self.input, input.isReadyForMoreMediaData { input.append(sample) }
        }
    }

    func finish() {
        queue.async {
            self.armed = false
            guard let writer = self.writer else { return }
            self.input?.markAsFinished()
            self.writer = nil
            self.input = nil
            writer.finishWriting {
                DispatchQueue.main.async {
                    Toaster.shared.show(writer.status == .completed ? "Saved \(writer.outputURL.lastPathComponent) to Movies" : "The recording could not be saved")
                }
            }
        }
    }

    private func open(format: CMFormatDescription, at start: CMTime) {
        guard let movies = FileManager.default.urls(for: .moviesDirectory, in: .userDomainMask).first else { return }
        let url = movies.appendingPathComponent("Wigly \(Int(Date().timeIntervalSince1970)).mov")
        guard let writer = try? AVAssetWriter(outputURL: url, fileType: .mov) else { return }
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: nil, sourceFormatHint: format)
        input.expectsMediaDataInRealTime = true
        guard writer.canAdd(input) else { return }
        writer.add(input)
        writer.startWriting()
        writer.startSession(atSourceTime: start)
        self.writer = writer
        self.input = input
    }

    private static func isSync(_ sample: CMSampleBuffer) -> Bool {
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]],
              let first = attachments.first else { return true }
        return first[kCMSampleAttachmentKey_NotSync] as? Bool != true
    }
}

/// The IPv4 address the phone should dial: Wi‑Fi or Ethernet first, never a
/// VPN, bridge, or AirDrop interface.
func lanIPv4() -> String? {
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
    defer { freeifaddrs(first) }
    var found: [(name: String, ip: String)] = []
    var ptr: UnsafeMutablePointer<ifaddrs>? = first
    while let current = ptr {
        ptr = current.pointee.ifa_next
        guard let addr = current.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
              current.pointee.ifa_flags & UInt32(IFF_UP | IFF_RUNNING) == UInt32(IFF_UP | IFF_RUNNING) else { continue }
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        guard getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
        let ip = String(cString: host)
        let name = String(cString: current.pointee.ifa_name)
        if ip.hasPrefix("127.") || ip.hasPrefix("169.254.") { continue }
        found.append((name, ip))
    }
    return (found.first { $0.name.hasPrefix("en") } ?? found.first { !$0.name.hasPrefix("utun") && !$0.name.hasPrefix("bridge") })?.ip
}

/// Global ⌘⇧M. Carbon hot keys need no Accessibility permission.
final class HotKey {
    static let shared = HotKey()
    private var hotKey: EventHotKeyRef?
    private var handler: EventHandlerRef?

    func install() {
        guard hotKey == nil else { return }
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        InstallEventHandler(GetApplicationEventTarget(), { _, _, _ in
            DispatchQueue.main.async { MirrorController.shared.toggle() }
            return noErr
        }, 1, &spec, nil, &handler)
        RegisterEventHotKey(UInt32(kVK_ANSI_M), UInt32(cmdKey | shiftKey), EventHotKeyID(signature: OSType(0x57474C59), id: 1),
                            GetApplicationEventTarget(), 0, &hotKey)
    }
}

final class MirrorWindow: NSWindowController, NSWindowDelegate {
    let displayLayer = AVSampleBufferDisplayLayer()
    private let host: MirrorVideoView
    private weak var controller: MirrorController?
    private var closingFromController = false

    init(controller: MirrorController) {
        self.controller = controller
        host = MirrorVideoView(frame: NSRect(x: 0, y: 0, width: 390, height: 844))
        let window = NSWindow(
            contentRect: host.frame,
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered, defer: false)
        window.title = CompanionConfig.shared.phoneName
        window.backgroundColor = NSColor(srgbRed: 0.11, green: 0.105, blue: 0.102, alpha: 1)
        window.isReleasedWhenClosed = false
        super.init(window: window)
        window.delegate = self
        window.contentView = host
        host.wantsLayer = true
        displayLayer.videoGravity = .resizeAspect
        displayLayer.frame = host.bounds
        host.layer?.addSublayer(displayLayer)
        host.onEvent = { [weak controller] data in controller?.sendControl(data) }
        host.onDropURL = { url in
            guard let peer = CoreBridge.shared.pairedPeer else {
                Toaster.shared.show("The phone is not on this network")
                return
            }
            MirrorController.shared.noteDroppedFile(url.lastPathComponent)
            CoreBridge.shared.enqueue([url], to: peer)
        }
        window.collectionBehavior = [.fullScreenAuxiliary]
    }

    required init?(coder: NSCoder) { nil }

    func show(aspect: CGFloat) {
        let width: CGFloat = 390
        let height = max(200, width / max(aspect, 0.2))
        window?.setContentSize(NSSize(width: width, height: height))
        window?.contentAspectRatio = NSSize(width: width, height: height)
        host.videoAspect = aspect
        window?.makeKeyAndOrderFront(nil)
        window?.makeFirstResponder(host)
        NSApp.activate(ignoringOtherApps: true)
    }

    /// Closed by the controller when the session ends.
    override func close() {
        closingFromController = true
        super.close()
    }

    func windowWillClose(_ notification: Notification) {
        if !closingFromController { controller?.windowClosed() }
    }
}

final class MirrorVideoView: NSView {
    var onEvent: ((Data) -> Void)?
    var onDropURL: ((URL) -> Void)?
    private var mapper = InputMapper(size: .zero)
    /// Width over height of the phone picture. The layer letterboxes it, so
    /// clicks are mapped inside the picture, not the whole view.
    var videoAspect: CGFloat = 1080.0 / 2400.0

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        registerForDraggedTypes([.fileURL])
    }
    required init?(coder: NSCoder) { nil }

    override var acceptsFirstResponder: Bool { true }
    override func mouseDown(with event: NSEvent) { send(event) }
    override func mouseDragged(with event: NSEvent) { send(event) }
    override func mouseUp(with event: NSEvent) { send(event) }
    override func rightMouseDown(with event: NSEvent) { send(event) }
    override func otherMouseDown(with event: NSEvent) { send(event) }
    override func scrollWheel(with event: NSEvent) { send(event) }
    override func keyDown(with event: NSEvent) { sendKey(event) }
    override func keyUp(with event: NSEvent) { sendKey(event) }

    override func layout() {
        super.layout()
        layer?.sublayers?.first?.frame = bounds
    }

    private var pictureRect: CGRect {
        AVMakeRect(aspectRatio: CGSize(width: videoAspect, height: 1), insideRect: bounds)
    }

    private func send(_ event: NSEvent) {
        let rect = pictureRect
        mapper.size = rect.size
        let point = convert(event.locationInWindow, from: nil)
        let local = CGPoint(x: point.x - rect.minX, y: point.y - rect.minY)
        if let data = mapper.mouse(event, at: local) { onEvent?(data) }
    }

    private func sendKey(_ event: NSEvent) {
        InputMapper.key(event, pasteboard: { NSPasteboard.general.string(forType: .string) }).forEach { onEvent?($0) }
    }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation { .copy }
    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        guard let url = sender.draggingPasteboard.readObjects(forClasses: [NSURL.self])?.first as? URL else { return false }
        onDropURL?(url)
        return true
    }
}
