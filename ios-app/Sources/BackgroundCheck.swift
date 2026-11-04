import BackgroundTasks
import Foundation
import UserNotifications

/// Фоновая проверка Unassigned. Когда её запустить — решает iOS (обычно раз в 15 мин – несколько часов).
enum BackgroundCheck {
    static let id = "md.cityblock.processmaker.refresh"
    private static let d = UserDefaults.standard

    static var enabled: Bool {
        get { d.bool(forKey: "bgEnabled") }
        set { d.set(newValue, forKey: "bgEnabled") }
    }

    /// Последняя фоновая проверка: когда и чем закончилась.
    static var lastStatus: String { d.string(forKey: "bgStatus") ?? "ещё не было" }

    private static var seen: Set<String>? {
        get { (d.array(forKey: "bgSeen") as? [String]).map(Set.init) }
        set { d.set(newValue.map(Array.init), forKey: "bgSeen") }
    }

    static func schedule() {
        guard enabled else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: id)
            return
        }
        let r = BGAppRefreshTaskRequest(identifier: id)
        r.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(r)
    }

    static func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    private static var maxNumber: Int {
        get { d.object(forKey: "bgMaxNum") as? Int ?? -1 }
        set { d.set(newValue, forKey: "bgMaxNum") }
    }

    /// Открыли приложение и увидели список — эти заявки уже не «новые».
    static func markSeen(_ cases: [PmCase]) {
        seen = Set(cases.map(\.key))
        maxNumber = max(maxNumber, cases.map(PmCore.num).max() ?? -1)
    }

    private static func setStatus(_ s: String) {
        let f = DateFormatter()
        f.dateFormat = "dd.MM HH:mm"
        d.set(f.string(from: Date()) + " — " + s, forKey: "bgStatus")
    }

    @MainActor
    static func run() async {
        schedule() // следующая проверка
        guard enabled, PmSession.shared.config.configured else { return }
        do {
            let cases = try await PmSession.shared.cases("unassigned")
            guard let before = seen else {
                markSeen(cases)
                setStatus("проверено, заявок: \(cases.count)")
                return
            }
            // новая = номер больше всех прежних (а не «всплывшая» снизу в первую сотню)
            let prevMax = maxNumber
            let fresh = cases.filter { !before.contains($0.key) && PmCore.num($0) > prevMax }
            for c in fresh.reversed() {
                let full = c.hasDetails ? c : await PmSession.shared.details(c, action: "unassigned")
                await notify(full)
            }
            markSeen(cases)
            setStatus("проверено, заявок: \(cases.count), новых: \(fresh.count)")
        } catch {
            setStatus("ошибка: " + error.localizedDescription)
        }
    }

    private static func notify(_ c: PmCase) async {
        let t = PmCore.title(c.title)
        let content = UNMutableNotificationContent()
        content.title = "Новая заявка #" + c.number + (t.slot.isEmpty ? "" : "  🕒 " + t.slot)
        var lines: [String] = [t.address]
        if !c.client.isEmpty { lines.append("👤 " + c.client) }
        if let ph = c.phones.first { lines.append("📞 " + CaseText.pretty(ph)) }
        if let k = CaseText.comments(c.description).first { lines.append("📋 " + k.text) }
        content.body = lines.joined(separator: "\n")
        content.sound = .default
        let req = UNNotificationRequest(identifier: c.key, content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(req)
    }
}
