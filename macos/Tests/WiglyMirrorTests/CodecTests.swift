import XCTest
@testable import WiglyMirror

final class CodecTests: XCTestCase {
    func testTouchLayoutMatchesCore() {
        let data = ControlCodec.touch(action: 1, pointer: 2, x: 0x0102, y: 0x0304, pressure: 0x0506)
        XCTAssertEqual(Array(data), [1, 1, 2, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06])
    }

    func testRightClickIsBack() {
        let mapper = InputMapper(size: CGSize(width: 200, height: 400))
        let event = NSEvent.mouseEvent(
            with: .rightMouseDown, location: CGPoint(x: 10, y: 10),
            modifierFlags: [], timestamp: 0, windowNumber: 0, context: nil,
            eventNumber: 0, clickCount: 1, pressure: 1)!
        XCTAssertEqual(mapper.mouse(event, at: CGPoint(x: 10, y: 10)), ControlCodec.button(ControlCodec.back))
    }

    func testClickMapsFromViewPoint() {
        let mapper = InputMapper(size: CGSize(width: 200, height: 400))
        let event = NSEvent.mouseEvent(
            with: .leftMouseDown, location: .zero,
            modifierFlags: [], timestamp: 0, windowNumber: 0, context: nil,
            eventNumber: 0, clickCount: 1, pressure: 1)!
        // Top-left of the view is the phone's origin.
        XCTAssertEqual(mapper.mouse(event, at: CGPoint(x: 0, y: 400)), ControlCodec.touch(action: 0, pointer: 0, x: 0, y: 0))
    }

    func testPunctuationIsTypedAsText() {
        let event = NSEvent.keyEvent(
            with: .keyDown, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: 0, context: nil,
            characters: ".", charactersIgnoringModifiers: ".", isARepeat: false, keyCode: 47)!
        XCTAssertEqual(InputMapper.key(event), [ControlCodec.text(".")])
    }

    func testReturnIsAKeyCode() {
        let event = NSEvent.keyEvent(
            with: .keyDown, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: 0, context: nil,
            characters: "\r", charactersIgnoringModifiers: "\r", isARepeat: false, keyCode: 36)!
        XCTAssertEqual(InputMapper.key(event), [ControlCodec.key(action: 0, code: 66, meta: 0)])
    }

    func testCommandVPastesTheMacClipboard() {
        let event = NSEvent.keyEvent(
            with: .keyDown, location: .zero, modifierFlags: [.command], timestamp: 0, windowNumber: 0, context: nil,
            characters: "v", charactersIgnoringModifiers: "v", isARepeat: false, keyCode: 9)!
        let out = InputMapper.key(event, pasteboard: { "hi" })
        XCTAssertEqual(out.first, ControlCodec.clipboard("hi"))
        XCTAssertEqual(out.count, 3)
    }

    func testMediaPayloadStartsAtZero() {
        let packet = Data([1, 1, 0, 0, 0, 0, 0, 0, 0, 5, 0, 0, 0, 1, 0x26])
        let media = Datagram.media(packet)!
        XCTAssertEqual(media.pts, 5)
        XCTAssertEqual(media.payload.startIndex, 0)
        XCTAssertEqual(AnnexB.split(media.payload).count, 1)
    }

    func testAnnexBStartCodes() {
        var data = Data([0, 0, 0, 1, 0x40, 0x01, 0, 0, 0, 1, 0x42, 0x02])
        let nals = AnnexB.split(data)
        XCTAssertEqual(nals.count, 2)
        XCTAssertEqual(AnnexB.hevcType(nals[0]), 32)
        XCTAssertEqual(AnnexB.hevcType(nals[1]), 33)
        let prefixed = AnnexB.lengthPrefixed(nals)
        XCTAssertEqual(prefixed.count, 4 + 2 + 4 + 2)
        data.removeAll()
    }

    func testNormClamps() {
        XCTAssertEqual(ControlCodec.norm(0, span: 100), 0)
        XCTAssertEqual(ControlCodec.norm(100, span: 100), 65535)
        XCTAssertEqual(ControlCodec.norm(-4, span: 100), 0)
    }
}
