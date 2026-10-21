import XCTest
import UIKit

final class InputAssistanceTests: XCTestCase {
  func testCodeRecognitionAndMonotonicExpiry() {
    XCTAssertEqual(AppleInputAssistance.verificationCode("合成验证码 482615"), "482615")
    XCTAssertNil(AppleInputAssistance.verificationCode("482615"))
    XCTAssertEqual(AppleInputAssistance.verificationCode("482615", explicit: true), "482615")
    let code = AppleInputAssistance.Code(value: "482615", received: 100)
    XCTAssertTrue(code.live(at: 399))
    XCTAssertFalse(code.live(at: 400))
    XCTAssertFalse(code.live(at: 99))
  }

  @MainActor func testNativePanelOnlyReadsOnTapAndFillsOnceWithoutSaving() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let store = ClipboardHistoryStore(directory: root)
    var reads = 0
    var inserted: [String] = []
    var systemRequested = false
    let panel = KeyboardClipboardView(hasFullAccess: true, store: store, readCodeText: {
      reads += 1; return "合成验证码 482615"
    }, allowsCode: { true }, onCode: { inserted.append($0) }, onSystemCode: { systemRequested = true }, onInsert: { _ in XCTFail() }, onClose: {})
    panel.frame = CGRect(x: 0, y: 0, width: 320, height: 260)
    panel.layoutIfNeeded()
    func button(_ identifier: String) throws -> UIButton {
      func find(_ view: UIView) -> UIButton? {
        if view.accessibilityIdentifier == identifier { return view as? UIButton }
        return view.subviews.lazy.compactMap(find).first
      }
      return try XCTUnwrap(find(panel))
    }
    XCTAssertEqual(reads, 0)
    try button("pasteVerificationCode").sendActions(for: .primaryActionTriggered)
    XCTAssertEqual(reads, 1)
    XCTAssertTrue(inserted.isEmpty)
    let fill = try button("fillVerificationCode")
    XCTAssertFalse(fill.isHidden)
    fill.sendActions(for: .primaryActionTriggered)
    fill.sendActions(for: .primaryActionTriggered)
    XCTAssertEqual(inserted, ["482615"])
    XCTAssertTrue(try store.load().isEmpty)
    try button("systemVerificationCode").sendActions(for: .primaryActionTriggered)
    XCTAssertTrue(systemRequested)
  }

  @MainActor func testPrivacyGatePreventsEvenTheExplicitRead() throws {
    var reads = 0
    let panel = KeyboardClipboardView(hasFullAccess: true, readCodeText: { reads += 1; return "482615" },
      allowsCode: { false }, onInsert: { _ in XCTFail() }, onClose: {})
    let actionRow = try XCTUnwrap(panel.subviews.first { $0.accessibilityIdentifier == "verificationCodeActions" })
    let read = try XCTUnwrap(actionRow.subviews.first { $0.accessibilityIdentifier == "pasteVerificationCode" } as? UIButton)
    read.sendActions(for: .primaryActionTriggered)
    XCTAssertEqual(reads, 0)
  }
}
