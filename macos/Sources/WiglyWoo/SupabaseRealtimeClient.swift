import Foundation
import WiglyExceptionGuard

final class SupabaseRealtimeClient {
    enum State: String {
        case off, connecting, connected, error
    }

    var onState: ((State) -> Void)?
    var onEnvelope: (([String: Any]) -> Void)?

    private let config: CompanionConfig
    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private var heartbeat: Timer?
    private var reconnectWork: DispatchWorkItem?
    private var ref = 0
    private var joinRef = ""
    private var attempt = 0
    private var stopped = false
    private var topic: String { "realtime:wigly:\(config.channelID)" }

    init(config: CompanionConfig) { self.config = config }

    func connect() {
        onMain { self.connectOnMain() }
    }

    private func connectOnMain() {
        teardownConnection()
        stopped = false
        emit(.connecting)
        guard var components = URLComponents(string: config.normalizedURL.replacingOccurrences(of: "https://", with: "wss://") + "/realtime/v1/websocket") else {
            emit(.error); return
        }
        components.queryItems = [
            URLQueryItem(name: "apikey", value: config.publishableKey),
            URLQueryItem(name: "vsn", value: "1.0.0")
        ]
        guard let url = components.url, let scheme = url.scheme, scheme == "wss" || scheme == "ws" else {
            emit(.error); return
        }
        // A fresh session per connection: a stale/invalidated session is one of
        // the states that makes webSocketTask(with:) raise instead of fail.
        let liveSession = URLSession(configuration: .default)
        session = liveSession
        var socket: URLSessionWebSocketTask?
        let exception = WiglyCatchException { socket = liveSession.webSocketTask(with: url) }
        guard exception == nil, let socket else {
            // Foundation refused the task (seen after sleep/network flaps as an
            // NSException). Treat it as a connection failure and retry.
            emit(.error)
            scheduleReconnect()
            return
        }
        task = socket
        socket.resume()
        join()
        receive()
        let timer = Timer(timeInterval: 25, repeats: true) { [weak self] _ in self?.sendHeartbeat() }
        RunLoop.main.add(timer, forMode: .common)
        heartbeat = timer
    }

    func disconnect() {
        onMain {
            self.stopped = true
            self.teardownConnection()
            self.emit(.off)
        }
    }

    func broadcast(_ envelope: [String: Any]) {
        send([
            "topic": topic,
            "event": "broadcast",
            "payload": ["type": "broadcast", "event": "companion", "payload": envelope],
            "ref": nextRef(),
            "join_ref": joinRef
        ])
    }

    private func join() {
        joinRef = nextRef()
        send([
            "topic": topic,
            "event": "phx_join",
            "payload": ["config": [
                "broadcast": ["ack": false, "self": false],
                "presence": ["enabled": false, "key": ""],
                "postgres_changes": [],
                "private": false
            ]],
            "ref": joinRef,
            "join_ref": joinRef
        ])
    }

    private func receive() {
        guard let socket = task else { return }
        socket.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let message):
                let data: Data?
                switch message {
                case .string(let string): data = string.data(using: .utf8)
                case .data(let value): data = value
                @unknown default: data = nil
                }
                if let data, let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    self.handle(object)
                }
                self.receive()
            case .failure:
                self.onMain {
                    // Cancelled/replaced sockets also land here; only the live
                    // connection's failure should trigger a reconnect.
                    guard !self.stopped, self.task === socket else { return }
                    self.emit(.error)
                    self.scheduleReconnect()
                }
            }
        }
    }

    private func handle(_ object: [String: Any]) {
        let event = object["event"] as? String
        if event == "phx_reply",
           let payload = object["payload"] as? [String: Any], payload["status"] as? String == "ok" {
            onMain { self.attempt = 0 }
            emit(.connected)
        } else if event == "broadcast",
                  let payload = object["payload"] as? [String: Any],
                  payload["event"] as? String == "companion",
                  let envelope = payload["payload"] as? [String: Any] {
            onEnvelope?(envelope)
        }
    }

    private func sendHeartbeat() {
        send(["topic": "phoenix", "event": "heartbeat", "payload": [:], "ref": nextRef(), "join_ref": NSNull()])
    }

    private func send(_ object: [String: Any]) {
        guard let socket = task,
              let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8) else { return }
        socket.send(.string(text)) { [weak self] error in
            if error != nil {
                self?.onMain {
                    guard let self, !self.stopped, self.task === socket else { return }
                    self.scheduleReconnect()
                }
            }
        }
    }

    /// Main thread only.
    private func scheduleReconnect() {
        guard !stopped, reconnectWork == nil else { return }
        heartbeat?.invalidate(); heartbeat = nil
        let delay = min(pow(2.0, Double(attempt)), 30)
        attempt += 1
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.reconnectWork = nil
            if !self.stopped { self.connectOnMain() }
        }
        reconnectWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    /// Main thread only.
    private func teardownConnection() {
        heartbeat?.invalidate(); heartbeat = nil
        reconnectWork?.cancel(); reconnectWork = nil
        task?.cancel(with: .normalClosure, reason: nil); task = nil
        session?.invalidateAndCancel(); session = nil
    }

    private func onMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread { block() } else { DispatchQueue.main.async(execute: block) }
    }

    private func nextRef() -> String { ref += 1; return String(ref) }
    private func emit(_ state: State) { onMain { self.onState?(state) } }
}
