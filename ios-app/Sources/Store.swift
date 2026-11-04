import Foundation
import SwiftUI

/// Что открыть в форме на весь экран.
struct FormTarget: Identifiable {
    let id = UUID()
    var uid: String
    var del: String
    var action: String
    var number: String
}

@MainActor
final class Store: ObservableObject {
    static let tabs = [("unassigned", "Unassigned"), ("todo", "Inbox"), ("draft", "Draft")]

    @Published var tab = "unassigned"
    @Published var cases: [PmCase] = []
    @Published var counts: [String: Int] = [:]
    @Published var status = ""
    @Published var fresh: Set<String> = []
    @Published var config = Config.load()

    @Published var total = 0
    @Published var loadingMore = false
    static let page = 100
    private var requested: Set<String> = []
    private var details: [String: PmCase] = [:]
    private var shown: [String: Set<String>] = [:]
    private var generation = 0

    func switchTab(_ t: String) {
        guard t != tab else { return }
        tab = t
        cases = []
        total = 0
        requested = []
        Task { await self.refresh() }
    }

    func saveConfig(_ c: Config) async {
        let accountChanged = c.user != config.user || c.password != config.password
            || c.host != config.host || c.workspace != config.workspace
        c.save()
        config = c
        PmSession.shared.config = c
        if accountChanged {
            await PmSession.shared.clearCookies()
            details = [:]
            shown = [:]
            cases = []
        }
        await refresh()
    }

    func refresh() async {
        guard config.configured else {
            status = "Укажите логин и пароль: ⚙️ вверху справа"
            return
        }
        generation += 1
        let gen = generation
        let act = tab
        status = cases.isEmpty ? "Загрузка…" : "Обновление…"
        let limit = min(500, max(Self.page, cases.count))
        do {
            let pg = try await PmSession.shared.page(act, start: 0, limit: limit)
            guard gen == generation else { return }
            let cs = pg.cases.map(withCachedDetails)
            let now = Set(cs.map(\.key))
            let prevMax = cases.map(PmCore.num).max() ?? -1
            if let before = shown[act] {
                fresh = Set(cs.filter { !before.contains($0.key) && PmCore.num($0) > prevMax }.map(\.key))
            } else {
                fresh = []
            }
            shown[act] = now
            cases = cs
            total = pg.total
            counts[act] = pg.total
            if act == "unassigned" { BackgroundCheck.markSeen(cs) }
            let f = DateFormatter()
            f.dateFormat = "HH:mm"
            status = "Обновлено в " + f.string(from: Date())
        } catch {
            guard gen == generation else { return }
            status = "⚠️ " + error.localizedDescription
        }
    }

    /// Следующие 100 заявок, когда долистали до конца.
    func loadMore() async {
        guard !loadingMore, !cases.isEmpty, cases.count < total else { return }
        loadingMore = true
        defer { loadingMore = false }
        let gen = generation
        let act = tab
        guard let pg = try? await PmSession.shared.page(act, start: cases.count, limit: Self.page),
              gen == generation else { return }
        let have = Set(cases.map(\.key))
        cases += pg.cases.filter { !have.contains($0.key) }.map(withCachedDetails)
        total = pg.cases.isEmpty ? cases.count : max(pg.total, cases.count)
    }

    /// Подробности для карточки, которая появилась на экране.
    func ensureDetails(_ c: PmCase) {
        guard !c.hasDetails, !requested.contains(c.key) else { return }
        requested.insert(c.key)
        let gen = generation
        let act = tab
        Task {
            let d = await PmSession.shared.details(c, action: act)
            self.details[d.key] = d
            if gen == self.generation, let i = self.cases.firstIndex(where: { $0.key == d.key }) {
                self.cases[i] = d
            }
        }
    }

    private func withCachedDetails(_ c: PmCase) -> PmCase {
        guard let d = details[c.key] else { return c }
        var c = c
        c.description = d.description
        c.client = d.client
        c.phones = d.phones
        c.notes = d.notes
        return c
    }

    func remember(_ c: PmCase) { details[c.key] = c }
}
