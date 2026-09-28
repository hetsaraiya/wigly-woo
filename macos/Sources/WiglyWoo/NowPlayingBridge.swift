import Foundation
import MediaPlayer

/// Publishes the phone's media session as this Mac's Now Playing info.
final class NowPlayingBridge {
    static let shared = NowPlayingBridge()
    private var installed = false

    func apply(_ message: [String: Any]) {
        install()
        var info: [String: Any] = [:]
        info[MPMediaItemPropertyTitle] = message["title"] as? String ?? ""
        info[MPMediaItemPropertyArtist] = message["artist"] as? String ?? ""
        info[MPNowPlayingInfoPropertyPlaybackRate] = (message["playing"] as? Bool == true) ? 1.0 : 0.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        MPNowPlayingInfoCenter.default().playbackState = (message["playing"] as? Bool == true) ? .playing : .paused
    }

    private func install() {
        guard !installed else { return }
        installed = true
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { _ in Self.send("play"); return .success }
        center.pauseCommand.addTarget { _ in Self.send("pause"); return .success }
        center.nextTrackCommand.addTarget { _ in Self.send("next"); return .success }
        center.previousTrackCommand.addTarget { _ in Self.send("previous"); return .success }
    }

    private static func send(_ command: String) {
        CompanionBridge.shared.send(["type": "media", "command": command])
    }
}
