import BackgroundTasks
import UIKit

/// 系统决定实际运行时刻；只请求充电后台窗口，不在键盘扩展起常驻任务。
@MainActor enum IOSNightLearning {
  static let identifier = "app.msime.ios.night-learning"
  private static let completedKey = "night-learning.completed-day"
  private static var registered = false

  static func register() {
    guard !registered else { return }
    registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
      guard let processing = task as? BGProcessingTask else { task.setTaskCompleted(success: false); return }
      Task { @MainActor in run(processing) }
    }
  }

  static func schedule() {
    guard registered else { return }
    guard MetasequoiaInputSessionBridge.loadSharedPreferences()?["charging_night_learning"] as? Bool ?? true else {
      BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier); return
    }
    let request = BGProcessingTaskRequest(identifier: identifier)
    request.requiresExternalPower = true
    request.requiresNetworkConnectivity = false
    let calendar = Calendar.current
    let now = Date()
    let hour = calendar.component(.hour, from: now)
    request.earliestBeginDate = hour < 6 ? now.addingTimeInterval(60) : calendar.startOfDay(for: now).addingTimeInterval(24 * 3600)
    try? BGTaskScheduler.shared.submit(request)
  }

  private static func run(_ task: BGProcessingTask) {
    schedule()
    UIDevice.current.isBatteryMonitoringEnabled = true
    let charging = [.charging, .full].contains(UIDevice.current.batteryState)
    let hour = Calendar.current.component(.hour, from: Date())
    let day = Calendar.current.startOfDay(for: Date()).timeIntervalSince1970
    let defaults = InputSchemePreference.appGroupDefaults
    guard charging, hour < 6, UIApplication.shared.applicationState != .active,
          !ProcessInfo.processInfo.isLowPowerModeEnabled,
          defaults.double(forKey: completedKey) != day,
          !KeyboardPrivacyPreference.incognito else { task.setTaskCompleted(success: true); return }
    let work = Task.detached(priority: .utility) {
      try? MetasequoiaInputSessionBridge.nightLearning(charging: charging, idle: true, hour: hour)
    }
    task.expirationHandler = {
      work.cancel()
      DispatchQueue.global(qos: .utility).async { _ = try? MetasequoiaInputSessionBridge.nightLearning(charging: false, idle: false, hour: 0, cancel: true) }
    }
    Task {
      let result = await work.value
      if result?["status"] as? String == "trained" { defaults.set(day, forKey: completedKey) }
      task.setTaskCompleted(success: result != nil && !work.isCancelled)
    }
  }
}
