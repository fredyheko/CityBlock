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

    private var details: [String: PmCase] = [:]
    private var shown: [String: Set<String>] = [:]
    private var generation = 0

    func switchTab(_ t: String) {
        guard t != tab else { return }
        tab = t
        cases = []
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
        do {
            var cs = try await PmSession.shared.cases(act)
            guard gen == generation else { return }
            for i in cs.indices {
                if let d = details[cs[i].key] {
                    cs[i].description = d.description
                    cs[i].client = d.client
                    cs[i].phones = d.phones
                    cs[i].notes = d.notes
                }
            }
            let now = Set(cs.map(\.key))
            fresh = shown[act].map { now.subtracting($0) } ?? []
            shown[act] = now
            cases = cs
            counts[act] = cs.count
            if act == "unassigned" { BackgroundCheck.markSeen(cs) }
            let f = DateFormatter()
            f.dateFormat = "HH:mm"
            status = "Обновлено в " + f.string(from: Date())
            for c in cs where !c.hasDetails {
                Task {
                    let d = await PmSession.shared.details(c, action: act)
                    self.details[d.key] = d
                    if gen == self.generation, let i = self.cases.firstIndex(where: { $0.key == d.key }) {
                        self.cases[i] = d
                    }
                }
            }
        } catch {
            guard gen == generation else { return }
            status = "⚠️ " + error.localizedDescription
        }
    }

    func remember(_ c: PmCase) { details[c.key] = c }
}
