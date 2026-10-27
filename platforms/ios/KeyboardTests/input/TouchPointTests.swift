import UIKit
import XCTest

/// 触屏 26 键双拼纠错（#10018）：哪一键带着按下点、注册给引擎的键位几何、按键记下的按下点，以及会话和键盘怎么接起来。
@MainActor
final class TouchPointTests: XCTestCase {
  override func setUp() {
    super.setUp()
    enableAllInputSchemes()
  }

  // MARK: - 合成的键盘

  /// 手机 26 键的排法：30×40 的键、36pt 一格，第二行缩进半格，第三行缩进一格半；按 a..z 排列。
  private nonisolated static let frames: [CGRect] = {
    let rows: [(String, CGFloat)] = [("qwertyuiop", 0), ("asdfghjkl", 0.5), ("zxcvbnm", 1.5)]
    var frames = [CGRect](repeating: .null, count: 26)
    for (row, (letters, indent)) in rows.enumerated() {
      for (column, letter) in letters.unicodeScalars.enumerated() {
        let index = Int(letter.value - UnicodeScalar("a").value)
        frames[index] = CGRect(x: (CGFloat(column) + indent) * 36, y: CGFloat(row) * 48, width: 30, height: 40)
      }
    }
    return frames
  }()

  /// 第二行 l 右边一格的 `;`。
  private nonisolated static let semicolon = CGRect(x: 9.5 * 36, y: 48, width: 30, height: 40)

  private static func frame(_ letter: Character) -> CGRect {
    frames[GlideTyping.letters.firstIndex(of: letter)!]
  }

  private func decode(_ data: Data) throws -> [String: Any] {
    try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
  }

  // MARK: - 判定

  func testOnlyShuangpinLettersAndTheSemicolonCarryATouch() {
    for key in ["a", "q", "z", ";"] {
      XCTAssertTrue(TouchPointPolicy.sendsTouch(character: key, shuangpinLetters: true, hasTouchDown: true, voiceOver: false), key)
    }
    // 辅助码的大写、分词的 `'`、数字和标点照常走 msime_client_character。
    for key in ["A", "'", "1", ",", "", "ab"] {
      XCTAssertFalse(TouchPointPolicy.sendsTouch(character: key, shuangpinLetters: true, hasTouchDown: true, voiceOver: false), key)
    }
    XCTAssertFalse(TouchPointPolicy.sendsTouch(character: "a", shuangpinLetters: false, hasTouchDown: true, voiceOver: false),
                   "全拼、14/18 键、本地模式和英文不带落点")
    XCTAssertFalse(TouchPointPolicy.sendsTouch(character: "a", shuangpinLetters: true, hasTouchDown: false, voiceOver: false),
                   "sendActions 这类激活没有按下点")
    XCTAssertFalse(TouchPointPolicy.sendsTouch(character: "a", shuangpinLetters: true, hasTouchDown: true, voiceOver: true),
                   "读屏的双击不是手指的落点")
  }

  // MARK: - 几何

  func testTheGeometryListsTheLettersAlphabeticallyAndTheSemicolon() throws {
    let plain = try decode(try XCTUnwrap(TouchPointPolicy.request(id: 7, layout: .init(letters: Self.frames, semicolon: nil))))
    XCTAssertEqual(Set(plain.keys), ["id", "keys", "semicolon", "duplicates"], "引擎拒绝未知字段")
    XCTAssertEqual(plain["id"] as? Int, 7)
    let keys = try XCTUnwrap(plain["keys"] as? [[Double]])
    XCTAssertEqual(keys.count, 26)
    XCTAssertEqual(keys[0], [15.0 + 18, 68, 30, 40], "a 在最前，[cx, cy, w, h]")
    XCTAssertEqual(keys[25], [Double(Self.frame("z").midX), Double(Self.frame("z").midY), 30, 40], "z 在最后")
    XCTAssertTrue(plain["semicolon"] is NSNull)
    XCTAssertEqual((plain["duplicates"] as? [Any])?.count, 0, "iOS 的分离键盘不重复字母键")

    let microsoft = try decode(try XCTUnwrap(
      TouchPointPolicy.request(id: 8, layout: .init(letters: Self.frames, semicolon: Self.semicolon))))
    XCTAssertEqual(microsoft["semicolon"] as? [Double], [357, 68, 30, 40])
    // 坐标保留一位小数。
    var fractional = Self.frames
    fractional[0] = CGRect(x: 0.04, y: 0.26, width: 30.33, height: 40.07)
    let rounded = try decode(try XCTUnwrap(TouchPointPolicy.request(id: 1, layout: .init(letters: fractional, semicolon: nil))))
    XCTAssertEqual((rounded["keys"] as? [[Double]])?.first, [15.2, 20.3, 30.3, 40.1])
  }

  func testTheGeometryNeedsAllTwentySixLetterKeysAndANonZeroID() throws {
    let layout = TouchPointPolicy.Layout(letters: Self.frames, semicolon: nil)
    XCTAssertNil(TouchPointPolicy.request(id: 0, layout: layout), "0 是清除")
    XCTAssertNil(TouchPointPolicy.request(id: 1, layout: .init(letters: Array(Self.frames.dropLast()), semicolon: nil)))
    for broken in [CGRect.null, .zero, CGRect(x: 0, y: 0, width: 30, height: 0), CGRect(x: CGFloat.nan, y: 0, width: 30, height: 40),
                   CGRect(x: 0, y: CGFloat.infinity, width: 30, height: 40)] {
      var letters = Self.frames
      letters[3] = broken
      XCTAssertNil(TouchPointPolicy.request(id: 1, layout: .init(letters: letters, semicolon: nil)), "\(broken)")
    }
    // `;` 没量出来时写 null，字母照常带落点。
    let empty = try decode(try XCTUnwrap(TouchPointPolicy.request(id: 1, layout: .init(letters: Self.frames, semicolon: .zero))))
    XCTAssertTrue(empty["semicolon"] is NSNull)
    XCTAssertLessThanOrEqual(
      try XCTUnwrap(TouchPointPolicy.request(id: .max, layout: .init(letters: Self.frames, semicolon: Self.semicolon))).count,
      TouchPointPolicy.requestByteLimit)
  }

  func testTheGeometryIsRegisteredAgainOnlyWhenItChangesOrTheSessionLostIt() {
    let layout = TouchPointPolicy.Layout(letters: Self.frames, semicolon: nil)
    var registration = TouchPointPolicy.Registration()
    XCTAssertTrue(registration.needsRegistering(layout, sessionID: nil))
    XCTAssertEqual(registration.advance(to: layout), 1)
    XCTAssertFalse(registration.needsRegistering(layout, sessionID: 1), "布局没变、会话里还是这一份：不再发")
    XCTAssertTrue(registration.needsRegistering(layout, sessionID: nil), "会话重建后没有几何")
    XCTAssertTrue(registration.needsRegistering(layout, sessionID: 9), "会话里是别的代次")
    // 单手、分离、旋转都会挪动键：换一个代次。
    var narrower = Self.frames
    narrower[0].size.width -= 4
    let moved = TouchPointPolicy.Layout(letters: narrower, semicolon: nil)
    XCTAssertTrue(registration.needsRegistering(moved, sessionID: 1))
    XCTAssertTrue(registration.needsRegistering(.init(letters: Self.frames, semicolon: Self.semicolon), sessionID: 1),
                  "微软双拼的 `;` 显示出来")
    XCTAssertEqual(registration.advance(to: moved), 2)
    XCTAssertFalse(registration.needsRegistering(moved, sessionID: 2))
    registration.reset()
    XCTAssertTrue(registration.needsRegistering(moved, sessionID: 2), "注册失败后下一键再试")
    // 代次用到头以后从 1 重新数，跳过表示清除的 0。
    var wrapping = TouchPointPolicy.Registration(id: .max - 1)
    XCTAssertEqual(wrapping.advance(to: layout), .max)
    XCTAssertEqual(wrapping.advance(to: layout), 1)
  }

  // MARK: - 按键

  private func keyInArea(received: @escaping (CGPoint?) -> Void) -> (KeyAreaStackView, KeyboardKeyButton) {
    let area = KeyAreaStackView(frame: CGRect(x: 0, y: 0, width: 200, height: 60))
    let row = UIStackView()
    area.addArrangedSubview(row)
    let key = KeyboardKeyButton(configuration: .plain(), primaryAction: UIAction { action in
      received((action.sender as? KeyboardKeyButton)?.takeTouchDownLocation())
    })
    row.addArrangedSubview(key)
    area.layoutIfNeeded()
    return (area, key)
  }

  func testAKeyRemembersTheTouchDownOnlyUntilTheTouchIsOver() {
    var received: [CGPoint?] = []
    // 键区要留着：按钮只被它的父视图持有，丢掉键区就不在键区里了。
    let (area, key) = keyInArea { received.append($0) }
    let touch = UITouch()

    // 不记按下点的按钮（功能键、候选）什么都不记。
    XCTAssertTrue(key.beginTracking(touch, with: nil))
    XCTAssertNil(key.touchDownLocation)

    key.recordsTouchDown = true
    XCTAssertTrue(key.beginTracking(touch, with: nil))
    XCTAssertNotNil(key.touchDownLocation, "字母键记下按下点")
    key.touchesCancelled([touch], with: nil)
    XCTAssertNil(key.touchDownLocation, "触摸被取消（滑行、横滑光标、系统打断）")

    XCTAssertTrue(key.beginTracking(touch, with: nil))
    key.touchesEnded([touch], with: nil)
    XCTAssertNil(key.touchDownLocation, "触摸结束后不留给下一次没有触摸的激活")

    key.cornerHint = "1"
    XCTAssertTrue(key.beginTracking(touch, with: nil))
    XCTAssertNotNil(key.touchDownLocation)
    key.cornerHint = nil
    key.removeFromSuperview()
    XCTAssertTrue(key.beginTracking(touch, with: nil))
    XCTAssertNil(key.touchDownLocation, "不在键区里的按钮没有键区坐标")
    withExtendedLifetime(area) {}

    // 按键的动作经 UIAction.sender 取走按下点，取过就没了。
    let (_, other) = keyInArea { received.append($0) }
    received.removeAll()
    other.touchDownLocation = CGPoint(x: 12, y: 34)
    other.sendActions(for: .primaryActionTriggered)
    other.sendActions(for: .primaryActionTriggered)
    XCTAssertEqual(received, [CGPoint(x: 12, y: 34), nil])
    XCTAssertNil(other.touchDownLocation)
  }

  // MARK: - 会话

  private func shuangpinBridge() -> MetasequoiaInputSessionBridge {
    let state = FileManager.default.temporaryDirectory
      .appendingPathComponent("msime-touch-\(UUID().uuidString)", isDirectory: true)
    addTeardownBlock { try? FileManager.default.removeItem(at: state) }
    let bridge = MetasequoiaInputSessionBridge(stateRoot: state)
    _ = bridge.switch(toShuangpinProfile: "xiaohe")
    return bridge
  }

  private func candidateTexts(_ bridge: MetasequoiaInputSessionBridge) throws -> [String] {
    let rows = try XCTUnwrap(try bridge.allCandidates()["candidates"] as? [[String: Any]])
    return rows.compactMap { $0["text"] as? String }
  }

  /// 按下点：被按键的键心往 `toward` 那个键挪 `fraction` 个键宽（键高）。
  private static func point(on pressed: Character, toward intended: Character, fraction: CGFloat = 0.4) -> CGPoint {
    let from = frame(pressed), to = frame(intended)
    let dx = to.midX - from.midX, dy = to.midY - from.midY
    let length = max(hypot(dx, dy), 1)
    return CGPoint(x: from.midX + dx / length * from.width * fraction, y: from.midY + dy / length * from.height * fraction)
  }

  func testTheSessionKeepsTheRegisteredGeometryAndRefusesABrokenOne() throws {
    let bridge = shuangpinBridge()
    XCTAssertNil(bridge.keyGeometryID)
    let request = try XCTUnwrap(TouchPointPolicy.request(id: 3, layout: .init(letters: Self.frames, semicolon: nil)))
    XCTAssertTrue(bridge.setKeyGeometry(request, id: 3))
    XCTAssertEqual(bridge.keyGeometryID, 3)
    XCTAssertFalse(bridge.setKeyGeometry(Data("{\"id\":4,\"keys\":[]}".utf8), id: 4), "引擎拒绝不是 26 个字母键的几何")
    XCTAssertNil(bridge.keyGeometryID, "被拒之后不再按任何代次带落点")
    XCTAssertFalse(bridge.setKeyGeometry(Data(), id: 5))
    XCTAssertFalse(bridge.setKeyGeometry(request, id: 0))
    XCTAssertTrue(bridge.setKeyGeometry(request, id: 3))
    XCTAssertEqual(bridge.keyGeometryID, 3)
  }

  /// 代次不符、坐标不是有限数时，带落点的按键与不带落点的逐字相同。
  func testATouchWithoutAUsableGeometryTypesLikeAPlainCharacter() throws {
    let plain = shuangpinBridge()
    let touched = shuangpinBridge()
    let request = try XCTUnwrap(TouchPointPolicy.request(id: 2, layout: .init(letters: Self.frames, semicolon: nil)))
    XCTAssertTrue(touched.setKeyGeometry(request, id: 2))
    for (letter, id, x) in [("w", UInt32(9), Float(50)), ("o", 2, .nan), ("m", 2, .infinity), ("f", 1, 100)] {
      let expected = plain.handleCharacter(letter)
      let actual = touched.handleTouchCharacter(letter, geometryID: id, x: x, y: 20)
      XCTAssertEqual(actual.editingText, expected.editingText, letter)
      XCTAssertEqual(actual.candidates, expected.candidates, letter)
    }
    XCTAssertEqual(try candidateTexts(touched), try candidateTexts(plain))
  }

  /// 带着 `touches` 给出的按下点打 `code`（没给的位置按在键心），返回整张候选表；`touches` 为 nil 时不带落点。
  private func type(_ code: String, touches: [Int: CGPoint]?) throws -> [String] {
    let bridge = shuangpinBridge()
    guard let touches else {
      for letter in code { _ = bridge.handleCharacter(String(letter)) }
      return try candidateTexts(bridge)
    }
    let request = try XCTUnwrap(TouchPointPolicy.request(id: 1, layout: .init(letters: Self.frames, semicolon: nil)))
    XCTAssertTrue(bridge.setKeyGeometry(request, id: 1))
    for (index, letter) in code.enumerated() {
      let point = touches[index] ?? CGPoint(x: Self.frame(letter).midX, y: Self.frame(letter).midY)
      let snapshot = bridge.handleTouchCharacter(String(letter), geometryID: 1, x: Float(point.x), y: Float(point.y))
      XCTAssertTrue(snapshot.isHandled, String(describing: snapshot.diagnosticText))
    }
    return try candidateTexts(bridge)
  }

  /// 「我们今天去公园」小鹤是 wo mf jb tm qu gs yr。把一个键按成它的邻键、落点在靠原键的那一侧时，纠错整句进第一页；不带落点时同一串输入它沉在表尾或根本不出现，落点按在被按键正中时也不进第一页。
  func testATouchNearTheIntendedKeyBringsTheCorrectedSentenceForward() throws {
    let gold = "我们今天去公园"
    // m 按成左边的 n（tn 是「条」），y 按成左边的 t（tr 是「团」）。
    for (code, index, intended) in [("womfjbtnqugsyr", 7, Character("m")), ("womfjbtmqugstr", 12, Character("y"))] {
      let pressed = Array(code)[index]
      let edge = try type(code, touches: [index: Self.point(on: pressed, toward: intended)])
      let touchedRank = try XCTUnwrap(edge.firstIndex(of: gold), "\(code): \(edge.prefix(12))")
      XCTAssertLessThan(touchedRank, 9, "\(code): 带着落点的纠错整句在第一页")
      let plain = try type(code, touches: nil)
      if let plainRank = plain.firstIndex(of: gold) { XCTAssertGreaterThanOrEqual(plainRank, 9, "\(code): 不带落点时沉底") }
      let center = try type(code, touches: [:])
      if let centerRank = center.firstIndex(of: gold) {
        XCTAssertGreaterThanOrEqual(centerRank, 9, "\(code): 按在键心的那一键改过去太贵，纠错整句不留在座位上")
      }
    }
  }

  // MARK: - 键盘

  private func nodes(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(nodes) }

  private func keyboard(_ scheme: ChineseInputScheme, width: CGFloat? = nil) -> KeyboardViewController {
    InputSchemePreference.scheme = scheme
    let controller = KeyboardViewController()
    controller.loadViewIfNeeded()
    controller.view.frame = CGRect(x: 0, y: 0, width: width ?? UIScreen.main.bounds.width,
                                   height: KeyboardViewController.defaultKeyboardHeight)
    controller.viewWillAppear(false)
    controller.view.layoutIfNeeded()
    return controller
  }

  private func keyArea(_ controller: KeyboardViewController) throws -> KeyAreaStackView {
    try XCTUnwrap(nodes(controller.view).first { $0 is KeyAreaStackView } as? KeyAreaStackView)
  }

  private func letterKey(_ letter: Character, in controller: KeyboardViewController) throws -> KeyboardKeyButton {
    try XCTUnwrap(nodes(controller.view).first { $0.accessibilityLabel == "字母 \(letter.uppercased())" } as? KeyboardKeyButton,
                  "\(letter)")
  }

  /// 设了 `MSIME_KEY_GEOMETRY_EXPORT`（xcodebuild 前加 `TEST_RUNNER_`）时，把键盘此刻会注册的几何写进那个目录，评测拿它替换参考布局。
  private func export(_ data: Data, as name: String) throws {
    guard let directory = ProcessInfo.processInfo.environment["MSIME_KEY_GEOMETRY_EXPORT"], !directory.isEmpty else { return }
    let url = URL(fileURLWithPath: directory, isDirectory: true)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    try data.write(to: url.appendingPathComponent("\(name).json"))
  }

  func testTheKeyboardExportsTheGeometryOfItsLetterKeys() throws {
    let previous = InputSchemePreference.scheme
    let previousOneHanded = KeyboardLayoutPreference.oneHanded
    defer {
      InputSchemePreference.scheme = previous
      KeyboardLayoutPreference.oneHanded = previousOneHanded
    }
    KeyboardLayoutPreference.oneHanded = .off
    let xiaohe = keyboard(.shuangpin)
    let area = try keyArea(xiaohe)
    let data = try XCTUnwrap(xiaohe.keyGeometryExport)
    let geometry = try decode(data)
    let keys = try XCTUnwrap(geometry["keys"] as? [[Double]])
    XCTAssertEqual(keys.count, 26)
    for (index, letter) in GlideTyping.letters.enumerated() {
      let frame = KeyAreaStackView.layoutFrame(of: try letterKey(letter, in: xiaohe), in: area)
      XCTAssertEqual(keys[index][0], Double(frame.midX), accuracy: 0.051, "\(letter)")
      XCTAssertEqual(keys[index][1], Double(frame.midY), accuracy: 0.051, "\(letter)")
      XCTAssertEqual(keys[index][2], Double(frame.width), accuracy: 0.051, "\(letter)")
    }
    XCTAssertTrue(geometry["semicolon"] is NSNull, "小鹤不显示 `;`")
    try export(data, as: "ios-iphone-portrait")

    let microsoft = keyboard(.microsoft)
    let microsoftData = try XCTUnwrap(microsoft.keyGeometryExport)
    let semicolonKey = try XCTUnwrap(nodes(microsoft.view).first { $0.accessibilityIdentifier == "microsoftFinalKey" })
    let semicolonFrame = KeyAreaStackView.layoutFrame(of: semicolonKey, in: try keyArea(microsoft))
    let semicolon = try XCTUnwrap(try decode(microsoftData)["semicolon"] as? [Double])
    XCTAssertEqual(semicolon[0], Double(semicolonFrame.midX), accuracy: 0.051)
    XCTAssertNotEqual(try decode(microsoftData)["keys"] as? [[Double]], keys, "第二行多一个 `;`，字母键变窄")
    try export(microsoftData, as: "ios-iphone-portrait-microsoft")

    // 单手模式把键收窄靠到一侧，几何随之改变。
    KeyboardLayoutPreference.oneHanded = .left
    let oneHanded = keyboard(.shuangpin)
    let narrow = try XCTUnwrap(try decode(try XCTUnwrap(oneHanded.keyGeometryExport))["keys"] as? [[Double]])
    XCTAssertLessThan(narrow[0][2], keys[0][2])
    KeyboardLayoutPreference.oneHanded = .off

    // 14 键不画 26 键的字母行，没有几何可导出。
    let fourteen = keyboard(.fourteenKey)
    XCTAssertNil(fourteen.keyGeometryExport)
  }

  /// iPad 横屏分离式键盘：T 和 Y 分在两半，中间隔着四分之一个键区。测试里的控制器没有窗口场景，横屏用 compact 的竖直 size class 模拟。
  func testTheSplitTabletKeyboardExportsBothHalves() throws {
    let defaults = KeyboardLayoutPreference.defaults
    let stored = defaults.object(forKey: KeyboardLayoutPreference.tabletSplitKey)
    let previous = InputSchemePreference.scheme
    defer {
      defaults.set(stored, forKey: KeyboardLayoutPreference.tabletSplitKey)
      InputSchemePreference.scheme = previous
    }
    KeyboardLayoutPreference.tabletSplit = true
    InputSchemePreference.scheme = .shuangpin
    let controller = KeyboardViewController()
    controller.traitOverrides.userInterfaceIdiom = .pad
    controller.traitOverrides.horizontalSizeClass = .regular
    controller.traitOverrides.verticalSizeClass = .compact
    controller.loadViewIfNeeded()
    controller.view.frame = CGRect(
      x: 0, y: 0, width: 1194, height: KeyboardViewController.keyboardHeight(.tablet, landscape: true, numberRow: true))
    controller.view.layoutIfNeeded()
    let data = try XCTUnwrap(controller.keyGeometryExport)
    let keys = try XCTUnwrap(try decode(data)["keys"] as? [[Double]])
    let t = keys[Int(UnicodeScalar("t").value - UnicodeScalar("a").value)]
    let y = keys[Int(UnicodeScalar("y").value - UnicodeScalar("a").value)]
    let r = keys[Int(UnicodeScalar("r").value - UnicodeScalar("a").value)]
    XCTAssertGreaterThan(y[0] - t[0], 3 * (t[0] - r[0]), "T 和 Y 之间隔着中缝")
    try export(data, as: "ios-ipad-split")
  }

  /// 26 键双拼的字母键取走按下点后照常组字；`sendActions` 没有按下点，走原来的 msime_client_character，两条路组出同样的字母。
  func testLetterKeysTypeWithTheirTouchDownAndDropIt() throws {
    let previous = InputSchemePreference.scheme
    defer { InputSchemePreference.scheme = previous }
    let touched = keyboard(.shuangpin)
    let plain = keyboard(.shuangpin)
    let area = try keyArea(touched)
    for letter in "womf" {
      let key = try letterKey(letter, in: touched)
      let frame = KeyAreaStackView.layoutFrame(of: key, in: area)
      key.touchDownLocation = CGPoint(x: frame.midX + frame.width * 0.3, y: frame.midY)
      key.sendActions(for: .primaryActionTriggered)
      XCTAssertNil(key.touchDownLocation, "\(letter)")
      try letterKey(letter, in: plain).sendActions(for: .primaryActionTriggered)
    }
    let preedit = { (controller: KeyboardViewController) in
      (self.nodes(controller.view).first { $0.accessibilityIdentifier == "preeditButton" } as? UIButton)?
        .accessibilityValue?.filter(\.isLetter)
    }
    XCTAssertEqual(preedit(touched), "womf")
    XCTAssertEqual(preedit(plain), "womf")
  }
}
