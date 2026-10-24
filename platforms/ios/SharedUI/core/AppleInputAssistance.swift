import Foundation

@_silgen_name("msime_client_input_assistance")
private func msimeAppleInputAssistance(_ bytes: UnsafePointer<UInt8>?, _ count: UInt) -> UnsafeMutablePointer<CChar>?
@_silgen_name("msime_client_string_free")
private func msimeAppleInputAssistanceFree(_ value: UnsafeMutablePointer<CChar>?)

/// 只处理用户点按交来的文字；不主动访问短信或剪贴板。
enum AppleInputAssistance {
  static func verificationCode(_ text: String, explicit: Bool = false) -> String? {
    guard let input = try? JSONSerialization.data(withJSONObject: ["text": text, "allow_plain_digits": explicit]), input.count <= 32768 else { return nil }
    let raw = input.withUnsafeBytes { msimeAppleInputAssistance($0.bindMemory(to: UInt8.self).baseAddress, UInt(input.count)) }
    guard let raw else { return nil }
    defer { msimeAppleInputAssistanceFree(raw) }
    let data = Data(bytes: raw, count: strlen(raw))
    guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          root["ok"] as? Bool == true, let value = root["value"] as? [String: Any] else { return nil }
    return value["code"] as? String
  }

  struct Code {
    let value: String
    let received: TimeInterval
    func live(at uptime: TimeInterval) -> Bool { uptime >= received && uptime - received < 300 }
  }
}
