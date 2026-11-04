import SwiftUI
import UIKit

// MARK: - Оформление

enum Theme {
    static let accent = Color(red: 0.08, green: 0.40, blue: 0.75)
    static let accentBg = Color(red: 0.89, green: 0.93, blue: 0.98)
    static let green = Color(red: 0.18, green: 0.49, blue: 0.20)
    static let greenBg = Color(red: 0.90, green: 0.96, blue: 0.92)
    static let orange = Color(red: 0.71, green: 0.33, blue: 0.04)
    static let orangeBg = Color(red: 0.99, green: 0.94, blue: 0.86)
}

struct Chip: View {
    let text: String
    let fg: Color
    let bg: Color
    var body: some View {
        Text(text).font(.footnote.bold()).foregroundColor(fg)
            .padding(.horizontal, 10).padding(.vertical, 3)
            .background(Capsule().fill(bg))
    }
}

struct CardBox<Content: View>: View {
    var border: Color = Color(.separator)
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 6) { content }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemGroupedBackground)))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(border, lineWidth: 1))
    }
}

func call(_ phone: String) {
    if let u = URL(string: "tel:" + CaseText.international(phone)) { UIApplication.shared.open(u) }
}

// MARK: - Главный экран

struct RootView: View {
    @EnvironmentObject var store: Store
    @State private var path: [PmCase] = []
    @State private var showSettings = false
    @State private var showNew = false
    @State private var formTarget: FormTarget?
    @State private var webUrl: WebTarget?

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                HStack(spacing: 6) {
                    ForEach(Store.tabs, id: \.0) { t in
                        let on = store.tab == t.0
                        Button {
                            store.switchTab(t.0)
                        } label: {
                            Text(t.1 + (store.counts[t.0].map { "  \($0)" } ?? ""))
                                .font(.subheadline.bold()).lineLimit(1).minimumScaleFactor(0.7)
                                .frame(maxWidth: .infinity, minHeight: 40)
                                .foregroundColor(on ? .white : .primary)
                                .background(RoundedRectangle(cornerRadius: 10).fill(on ? Theme.accent : Color(.secondarySystemGroupedBackground)))
                        }
                    }
                    Button { showNew = true } label: {
                        Image(systemName: "plus").font(.title3.bold()).foregroundColor(.white)
                            .frame(width: 46, height: 40)
                            .background(RoundedRectangle(cornerRadius: 10).fill(Theme.green))
                    }
                }
                .padding(.horizontal, 12).padding(.top, 8)
                Text(store.status).font(.footnote).foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.vertical, 4)
                ScrollView {
                    LazyVStack(spacing: 10) {
                        if store.cases.isEmpty && store.status.hasPrefix("Обновлено") {
                            Text("Заявок нет 🎉").foregroundColor(.secondary).padding(.top, 48)
                        }
                        ForEach(store.cases) { c in
                            CaseCard(c: c, tab: store.tab, isNew: store.fresh.contains(c.key))
                                .contentShape(Rectangle())
                                .onTapGesture { path.append(c) }
                        }
                    }
                    .padding(.horizontal, 12).padding(.bottom, 24)
                }
                .refreshable { await store.refresh() }
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Заявки")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button { webUrl = WebTarget(url: PmCore.homeUrl(store.config.base)) } label: {
                        Image(systemName: "globe")
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                }
            }
            .navigationDestination(for: PmCase.self) { c in
                CaseDetailView(c: c, action: store.tab)
            }
        }
        .sheet(isPresented: $showSettings) { SettingsView() }
        .sheet(isPresented: $showNew) {
            NewCaseView { target in
                showNew = false
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { formTarget = target }
            }
        }
        .fullScreenCover(item: $formTarget, onDismiss: { Task { await store.refresh() } }) { t in
            FormScreen(target: t)
        }
        .fullScreenCover(item: $webUrl) { w in WebScreen(url: w.url) }
        .task {
            if !store.config.configured { showSettings = true }
            while !Task.isCancelled {
                await store.refresh()
                try? await Task.sleep(nanoseconds: 60_000_000_000)
            }
        }
    }
}

struct WebTarget: Identifiable {
    let id = UUID()
    let url: String
}

// MARK: - Карточка

struct CaseCard: View {
    let c: PmCase
    let tab: String
    let isNew: Bool

    var body: some View {
        let t = PmCore.title(c.title)
        let draftLike = (tab == "draft" || PmCore.isBareTitle(c.title)) && !c.process.isEmpty
        CardBox(border: isNew ? Theme.green : Color(.separator)) {
            HStack(spacing: 8) {
                Text("#" + c.number).font(.footnote).foregroundColor(.secondary)
                if isNew { Chip(text: "новая", fg: Theme.green, bg: Theme.greenBg) }
                if !t.slot.isEmpty { Chip(text: "🕒 " + t.slot, fg: Theme.accent, bg: Theme.accentBg) }
                if !t.type.isEmpty { Chip(text: t.type, fg: Theme.orange, bg: Theme.orangeBg) }
            }
            if draftLike {
                Text(c.process).font(.headline)
                if !c.task.isEmpty { Text("Этап: " + c.task).font(.subheadline).foregroundColor(.secondary) }
                if !PmCore.isBareTitle(c.title) { Text(c.title).font(.subheadline).lineLimit(2) }
            } else {
                Text(t.address).font(.headline)
                if !t.date.isEmpty { Text(t.date).font(.footnote).foregroundColor(.secondary) }
            }
            if !c.client.isEmpty { Text("👤 " + c.client).lineLimit(1) }
            if let latest = CaseText.comments(c.description).first {
                Text("💬 " + latest.text).lineLimit(2)
            } else if !c.hasDetails && !draftLike {
                Text("загрузка подробностей…").font(.footnote).foregroundColor(.secondary)
            }
            if let ph = c.phones.first {
                Button { call(ph) } label: {
                    Label(CaseText.pretty(ph), systemImage: "phone.fill")
                        .font(.body.bold()).foregroundColor(Theme.green)
                        .frame(maxWidth: .infinity, minHeight: 42)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Theme.greenBg))
                }
                .buttonStyle(.plain)
                .padding(.top, 4)
            }
        }
    }
}

// MARK: - Экран заявки

struct CaseDetailView: View {
    @EnvironmentObject var store: Store
    @State var c: PmCase
    @State var action: String
    @State private var claiming = false
    @State private var confirmClaim = false
    @State private var error: String?
    @State private var formTarget: FormTarget?
    @State private var webUrl: WebTarget?
    @State private var toast: String?

    var body: some View {
        let t = PmCore.title(c.title)
        let bare = (action == "draft" || PmCore.isBareTitle(c.title)) && !c.process.isEmpty
        ScrollView {
            VStack(spacing: 10) {
                CardBox {
                    HStack(spacing: 8) {
                        if !t.slot.isEmpty { Chip(text: "🕒 " + t.slot, fg: Theme.accent, bg: Theme.accentBg) }
                        if !t.type.isEmpty { Chip(text: t.type, fg: Theme.orange, bg: Theme.orangeBg) }
                    }
                    Text(bare ? c.process : t.address).font(.title3.bold()).textSelection(.enabled)
                    if bare && !c.task.isEmpty { Text("Этап: " + c.task).foregroundColor(.secondary) }
                    if bare && !PmCore.isBareTitle(c.title) { Text(c.title) }
                    if !bare && !t.date.isEmpty { Text(t.date).foregroundColor(.secondary) }
                    let svc = CaseText.service(c.description)
                    if !svc.isEmpty { Text(svc).font(.subheadline).foregroundColor(.secondary) }
                    if !t.account.isEmpty { Text("Аккаунт: " + t.account).font(.subheadline).foregroundColor(.secondary) }
                }

                if !c.client.isEmpty || !c.phones.isEmpty {
                    section("Клиент") {
                        if !c.client.isEmpty { Text(c.client).font(.headline) }
                        ForEach(c.phones, id: \.self) { ph in
                            Button { call(ph) } label: {
                                Label("Позвонить  " + CaseText.pretty(ph), systemImage: "phone.fill")
                                    .font(.body.bold()).foregroundColor(Theme.green)
                                    .frame(maxWidth: .infinity, minHeight: 46)
                                    .background(RoundedRectangle(cornerRadius: 10).fill(Theme.greenBg))
                            }
                            .buttonStyle(.plain)
                            .contextMenu {
                                Button("Скопировать номер") { UIPasteboard.general.string = CaseText.international(ph) }
                            }
                        }
                    }
                }

                let comments = CaseText.comments(c.description)
                if !comments.isEmpty {
                    section("Комментарии") {
                        ForEach(comments, id: \.self) { k in
                            VStack(alignment: .leading, spacing: 1) {
                                Text(k.text)
                                Text((k.author.isEmpty ? "" : k.author + " · ") + k.when)
                                    .font(.caption).foregroundColor(.secondary)
                            }.padding(.top, 4)
                        }
                    }
                }

                let tech = CaseText.techLines(c.description)
                if !tech.isEmpty {
                    section("Подключение") {
                        ForEach(tech, id: \.self) { Text("• " + $0).textSelection(.enabled) }
                    }
                }

                if !c.description.isEmpty && comments.isEmpty && tech.isEmpty {
                    section("Описание") { Text(c.description).textSelection(.enabled) }
                }

                if !c.notes.isEmpty {
                    section("Case Notes") {
                        ForEach(Array(c.notes.reversed().enumerated()), id: \.offset) { Text($0.element).font(.subheadline) }
                    }
                }

                if !c.hasDetails {
                    ProgressView("Загрузка подробностей…").padding(.top, 8)
                }

                section("Процесс") {
                    Text(c.process + (c.task.isEmpty ? "" : "\n" + c.task)).font(.subheadline).foregroundColor(.secondary)
                    if !c.sentBy.isEmpty { Text("Отправил: " + c.sentBy).font(.subheadline).foregroundColor(.secondary) }
                    if !c.due.isEmpty { Text("Срок: " + c.due).font(.subheadline).foregroundColor(.secondary) }
                }

                Button {
                    if action == "unassigned" { confirmClaim = true }
                    else { formTarget = FormTarget(uid: c.uid, del: c.delIndex, action: action == "draft" ? "draft" : "todo", number: c.number) }
                } label: {
                    Text(claiming ? "Беру заявку…"
                         : action == "unassigned" ? "✋  Взять заявку"
                         : action == "draft" ? "📝  Продолжить заполнение" : "📝  Заполнить заявку")
                        .font(.headline).foregroundColor(.white)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(RoundedRectangle(cornerRadius: 12).fill(Theme.accent))
                }
                .disabled(claiming)
                .padding(.top, 8)

                Button {
                    webUrl = WebTarget(url: PmCore.openCaseUrl(store.config.base, c, action))
                } label: {
                    Text("Открыть в полном PM").foregroundColor(Theme.accent)
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(.separator)))
                }
            }
            .padding(12)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(c.number.isEmpty ? "Заявка" : "Заявка #" + c.number)
        .navigationBarTitleDisplayMode(.inline)
        .overlay(alignment: .bottom) {
            if let toast = toast {
                Text(toast).padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Capsule().fill(Color.black.opacity(0.8))).foregroundColor(.white)
                    .padding(.bottom, 30)
            }
        }
        .task {
            if !c.hasDetails {
                c = await PmSession.shared.details(c, action: action)
                store.remember(c)
            }
        }
        .alert("Взять заявку #\(c.number)?", isPresented: $confirmClaim) {
            Button("Взять") { Task { await claim() } }
            Button("Отмена", role: .cancel) {}
        } message: { Text(t.address) }
        .alert("Не получилось взять", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("Открыть в PM") {
                webUrl = WebTarget(url: PmCore.openCaseUrl(store.config.base, c, "unassigned"))
            }
            Button("OK", role: .cancel) {}
        } message: { Text(error ?? "") }
        .fullScreenCover(item: $formTarget) { FormScreen(target: $0) }
        .fullScreenCover(item: $webUrl) { WebScreen(url: $0.url) }
    }

    private func claim() async {
        claiming = true
        defer { claiming = false }
        do {
            let mine = try await PmSession.shared.claim(c)
            c = mine
            action = "todo"
            showToast("Заявка ваша ✓")
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func showToast(_ s: String) {
        toast = s
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { toast = nil }
    }

    @ViewBuilder
    private func section<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        CardBox {
            Text(title.uppercased()).font(.caption.bold()).foregroundColor(Theme.accent)
            content()
        }
    }
}

// MARK: - New case

struct NewCaseView: View {
    let onStarted: (FormTarget) -> Void
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var store: Store
    @State private var items: [PmCore.StartItem] = []
    @State private var loading = true
    @State private var error: String?
    @State private var confirm: PmCore.StartItem?
    @State private var starting = false
    @State private var webUrl: WebTarget?

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView("Загружаю список процессов…")
                } else if let error = error {
                    VStack(spacing: 16) {
                        Text(error).multilineTextAlignment(.center)
                        Button("Открыть «New case» в PM") {
                            webUrl = WebTarget(url: PmCore.startPageUrl(store.config.base))
                        }
                    }.padding()
                } else {
                    List {
                        ForEach(categories, id: \.self) { cat in
                            Section(cat.isEmpty ? "Процессы" : cat) {
                                ForEach(items.filter { $0.category == cat }) { it in
                                    Button(it.title) { confirm = it }.foregroundColor(.primary)
                                }
                            }
                        }
                    }
                }
            }
            .overlay { if starting { ProgressView("Создаю заявку…").padding().background(.regularMaterial).cornerRadius(12) } }
            .navigationTitle("Какую заявку создать?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } } }
        }
        .task { await load() }
        .alert("Создать заявку?", isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } })) {
            Button("Start case") { if let it = confirm { Task { await start(it) } } }
            Button("Отмена", role: .cancel) {}
        } message: { Text(confirm?.title ?? "") }
        .fullScreenCover(item: $webUrl) { WebScreen(url: $0.url) }
    }

    private var categories: [String] {
        var out: [String] = []
        for it in items where !out.contains(it.category) { out.append(it.category) }
        return out
    }

    private func load() async {
        do {
            items = try await PmSession.shared.startList()
        } catch {
            self.error = error.localizedDescription
        }
        loading = false
    }

    private func start(_ it: PmCore.StartItem) async {
        starting = true
        defer { starting = false }
        do {
            let (uid, del) = try await PmSession.shared.startCase(it)
            onStarted(FormTarget(uid: uid, del: del, action: "draft", number: ""))
        } catch {
            self.error = error.localizedDescription
        }
    }
}

// MARK: - Настройки

struct SettingsView: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var c = Config()
    @State private var bg = BackgroundCheck.enabled

    var body: some View {
        NavigationStack {
            Form {
                Section("Вход в ProcessMaker") {
                    TextField("Логин", text: $c.user).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Пароль", text: $c.password)
                }
                Section("Сайт") {
                    TextField("Адрес сайта", text: $c.host).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .keyboardType(.URL)
                    TextField("Workspace", text: $c.workspace).textInputAutocapitalization(.never).autocorrectionDisabled()
                }
                Section {
                    Toggle("Уведомления в фоне (Unassigned)", isOn: $bg)
                        .onChange(of: bg) { on in
                            BackgroundCheck.enabled = on
                            if on { Task { _ = await BackgroundCheck.requestPermission() } }
                            BackgroundCheck.schedule()
                        }
                    if bg {
                        Text("Последняя проверка: " + BackgroundCheck.lastStatus)
                            .font(.footnote).foregroundColor(.secondary)
                    }
                } header: {
                    Text("Уведомления")
                } footer: {
                    Text("Когда проверять, решает iOS: иногда раз в 15–30 минут, иногда раз в несколько часов. Если смахнуть приложение из списка открытых, проверок не будет. В Настройках iPhone → ProcessMaker должно быть включено «Обновление контента». Для надёжных уведомлений используйте Telegram или письма от PM.")
                }
                Section {
                    Button("Сохранить") {
                        let cfg = c
                        Task { await store.saveConfig(cfg) }
                        dismiss()
                    }
                    .disabled(c.user.isEmpty || c.password.isEmpty)
                } footer: {
                    Text("Уведомления о новых заявках на iPhone приходят в Telegram — iOS не даёт приложению проверять сайт в фоне.\n\nVPN: в WireGuard откройте туннель → «Изменить» → «Включать по запросу»: отметьте «Сотовая связь», снимите «Wi-Fi».")
                }
                Section {
                    VStack(spacing: 6) {
                        Image("Logo").resizable().scaledToFit().frame(width: 110)
                        Text("ProcessMaker для iPhone, версия " + version).font(.caption).foregroundColor(.secondary)
                    }.frame(maxWidth: .infinity)
                }
            }
            .navigationTitle("Настройки")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() } } }
        }
        .onAppear { c = store.config }
    }

    private var version: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
    }
}
