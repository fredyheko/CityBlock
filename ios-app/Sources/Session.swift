import Foundation
import Security
import UIKit
import WebKit

// MARK: - Настройки

struct Config: Equatable {
    var host = "https://pm1.starnet.md"
    var workspace = "starnet"
    var user = ""
    var password = ""

    var base: String { PmCore.base(host: host, workspace: workspace) }
    var configured: Bool { !user.isEmpty && !password.isEmpty }

    static func load() -> Config {
        let d = UserDefaults.standard
        var c = Config()
        c.host = d.string(forKey: "host") ?? c.host
        c.workspace = d.string(forKey: "workspace") ?? c.workspace
        c.user = d.string(forKey: "user") ?? ""
        c.password = Keychain.get("pass") ?? d.string(forKey: "pass_fallback") ?? ""
        return c
    }

    func save() {
        let d = UserDefaults.standard
        d.set(host, forKey: "host")
        d.set(workspace, forKey: "workspace")
        d.set(user, forKey: "user")
        if Keychain.set("pass", password) {
            d.removeObject(forKey: "pass_fallback")
        } else {
            d.set(password, forKey: "pass_fallback")
        }
    }
}

enum Keychain {
    private static let service = "md.cityblock.processmaker"

    @discardableResult
    static func set(_ key: String, _ value: String) -> Bool {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrService as String: service,
                                kSecAttrAccount as String: key]
        SecItemDelete(q as CFDictionary)
        var add = q
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    static func get(_ key: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrService as String: service,
                                kSecAttrAccount as String: key,
                                kSecReturnData as String: true,
                                kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }
}

// MARK: - Сеть

private final class NoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil) // редирект на страницу входа = сессия истекла
    }
}

struct Resp {
    let code: Int
    let body: String
    let location: String?
    let url: String
}

/// Работа с сайтом PM. Куки общие с WKWebView (синхронизируются).
@MainActor
final class PmSession {
    static let shared = PmSession()

    private let http: URLSession
    private var loginTask: Task<Void, Error>?
    var config = Config.load()

    private init() {
        let cfg = URLSessionConfiguration.default
        cfg.httpCookieStorage = .shared
        cfg.httpShouldSetCookies = true
        cfg.httpCookieAcceptPolicy = .always
        cfg.timeoutIntervalForRequest = 30
        http = URLSession(configuration: cfg, delegate: NoRedirect(), delegateQueue: nil)
    }

    func request(_ pathOrUrl: String, post: String? = nil) async throws -> Resp {
        let s = pathOrUrl.hasPrefix("http") ? pathOrUrl : config.base + pathOrUrl
        guard let url = URL(string: s) else { throw PmError("Неверный адрес: \(s)") }
        var req = URLRequest(url: url)
        req.setValue("XMLHttpRequest", forHTTPHeaderField: "X-Requested-With")
        req.setValue("application/json, text/javascript, text/html, */*", forHTTPHeaderField: "Accept")
        if let post = post {
            req.httpMethod = "POST"
            req.httpBody = Data(post.utf8)
            req.setValue("application/x-www-form-urlencoded; charset=UTF-8", forHTTPHeaderField: "Content-Type")
        }
        let (data, resp) = try await http.data(for: req)
        guard let h = resp as? HTTPURLResponse else { throw PmError("Нет ответа от сайта") }
        return Resp(code: h.statusCode, body: String(decoding: data, as: UTF8.self),
                    location: h.value(forHTTPHeaderField: "Location"), url: s)
    }

    private var hasCookies: Bool {
        guard let u = URL(string: config.base) else { return false }
        return !(HTTPCookieStorage.shared.cookies(for: u) ?? []).isEmpty
    }

    func fetchCases(_ action: String) async throws -> [PmCase] {
        if !hasCookies { throw SessionExpired() }
        let r = try await request(PmCore.listUrl(config.base, action))
        if (300..<400).contains(r.code) {
            guard let loc = r.location, !loc.contains("login") else { throw SessionExpired() }
            throw PmError("Сайт перенаправил на \(loc)")
        }
        if r.code == 401 { throw SessionExpired() }
        if r.code >= 400 {
            if r.body.lowercased().contains("login") { throw SessionExpired() }
            throw PmError("Сайт ответил ошибкой \(r.code)" + (r.code == 403 ? " (нет VPN?)" : ""))
        }
        return try PmCore.parse(r.body, finalUrl: r.url)
    }

    /// Список заявок; при истёкшей сессии входит заново.
    func cases(_ action: String) async throws -> [PmCase] {
        guard config.configured else { throw PmError("Укажите логин и пароль в настройках") }
        do {
            return try await fetchCases(action)
        } catch is SessionExpired {
            try await login()
            return try await fetchCases(action)
        } catch let e as URLError {
            throw PmError(Self.netMessage(e))
        }
    }

    static func netMessage(_ e: URLError) -> String {
        switch e.code {
        case .notConnectedToInternet: return "Нет интернета"
        case .timedOut, .cannotConnectToHost, .cannotFindHost:
            return "Сайт не отвечает — включён ли VPN?"
        default: return e.localizedDescription
        }
    }

    func login() async throws {
        if let t = loginTask { return try await t.value }
        let cfg = config
        let t = Task { @MainActor in
            try await WebLogin.run(url: PmCore.loginUrl(cfg.base), user: cfg.user, pass: cfg.password)
            await self.syncCookiesFromWeb()
        }
        loginTask = t
        defer { loginTask = nil }
        try await t.value
    }

    func syncCookiesFromWeb() async {
        let cookies = await WKWebsiteDataStore.default().httpCookieStore.allCookies()
        for c in cookies { HTTPCookieStorage.shared.setCookie(c) }
    }

    func syncCookiesToWeb() async {
        let store = WKWebsiteDataStore.default().httpCookieStore
        for c in HTTPCookieStorage.shared.cookies ?? [] { await store.setCookie(c) }
    }

    func clearCookies() async {
        for c in HTTPCookieStorage.shared.cookies ?? [] { HTTPCookieStorage.shared.deleteCookie(c) }
        await WKWebsiteDataStore.default().removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(),
                                                      modifiedSince: .distantPast)
    }

    /// Клиент, телефон, описание, заметки. Ошибки игнорируются.
    func details(_ c: PmCase, action: String) async -> PmCase {
        var out = c
        let q = "APP_UID=\(c.uid)&DEL_INDEX=\(c.delIndex)"
        let p = "appUid=\(c.uid)&delIndex=\(c.delIndex)&\(q)"
        var reqs: [(String, String, String?)] = [
            ("getSummary", "/appProxy/getSummary", p + "&action=\(action)"),
            ("summaryAjax", "/cases/summaryAjax", "action=getDataSummary&" + q),
            ("notes", "/appProxy/getNotesList", "appUid=\(c.uid)&start=0&limit=50"),
        ]
        if !c.description.isEmpty { reqs.removeAll { $0.0 != "notes" } }
        var responses: [(name: String, body: String)] = []
        var haveDesc = !c.description.isEmpty
        for (name, path, post) in reqs {
            if haveDesc && name != "notes" { continue }
            guard let r = try? await request(path, post: post), r.code == 200 else { continue }
            responses.append((name, r.body))
            if name != "notes" && !CaseText.caseDescription(r.body).isEmpty { haveDesc = true }
        }
        CaseText.fill(&out, responses: responses)
        return out
    }

    /// Взять заявку (Claim) — как кнопка «Claim this case» на сайте. Проверяется по Inbox.
    func claim(_ c: PmCase) async throws -> PmCase {
        var c = c
        let unassigned = try await cases("unassigned")
        guard let fresh = PmCore.find(unassigned, uid: c.uid) else {
            let todo = try await fetchCases("todo")
            if let mine = PmCore.find(todo, uid: c.uid) { return mine }
            throw PmError("Заявки уже нет в Unassigned — похоже, её взял кто-то другой.")
        }
        c.delIndex = fresh.delIndex
        let open = try await request(PmCore.catchOpenUrl(config.base, c))
        if open.code >= 400 { throw PmError("Сайт не открыл заявку (ошибка \(open.code))") }
        let r = try await request(PmCore.catchExecutePath, post: PmCore.claimPost(c))
        if r.code >= 400 { throw PmError("Сайт ответил ошибкой \(r.code)") }
        let todoAfter = try await fetchCases("todo")
        guard var mine = PmCore.find(todoAfter, uid: c.uid) else {
            throw PmError("Заявка не появилась в вашем Inbox. Возможно, её уже взял кто-то другой — откройте её в PM.")
        }
        mine.description = c.description
        mine.client = c.client
        mine.phones = c.phones
        mine.notes = c.notes
        return mine
    }

    func findMine(uid: String) async -> PmCase? {
        await syncCookiesFromWeb()
        let todo = (try? await cases("todo")) ?? []
        return PmCore.find(todo, uid: uid)
    }

    func startList() async throws -> [PmCore.StartItem] {
        _ = try await cases("draft")
        let r = try await request(PmCore.startAjaxPath, post: PmCore.startListPost)
        if r.code != 200 { throw PmError("Сайт ответил ошибкой \(r.code)") }
        let items = try PmCore.parseStartList(r.body)
        if items.isEmpty { throw PmError("Список процессов пуст") }
        return items
    }

    func startCase(_ it: PmCore.StartItem) async throws -> (String, String) {
        let r = try await request(PmCore.startAjaxPath, post: PmCore.startCasePost(it))
        if (300..<400).contains(r.code), let loc = r.location { return try PmCore.parseStarted(loc) }
        if r.code != 200 { throw PmError("Сайт ответил ошибкой \(r.code)") }
        return try PmCore.parseStarted(r.body)
    }
}

// MARK: - Вход через невидимый браузер

/// Открывает настоящую страницу входа, вписывает логин/пароль и нажимает кнопку.
@MainActor
final class WebLogin: NSObject, WKNavigationDelegate {
    private let user: String
    private let pass: String
    private var web: WKWebView?
    private var cont: CheckedContinuation<Void, Error>?
    private var state = 0 // 0 — ждём форму, 1 — пароль отправлен
    private var fillTries = 0
    private var finished = false

    private init(user: String, pass: String) {
        self.user = user
        self.pass = pass
    }

    static func run(url: String, user: String, pass: String) async throws {
        let l = WebLogin(user: user, pass: pass)
        try await l.start(url)
    }

    private func start(_ url: String) async throws {
        guard let u = URL(string: url) else { throw PmError("Неверный адрес сайта") }
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            cont = c
            let cfg = WKWebViewConfiguration()
            cfg.websiteDataStore = .default()
            let w = WKWebView(frame: CGRect(x: -2000, y: 0, width: 390, height: 800), configuration: cfg)
            w.navigationDelegate = self
            w.alpha = 0.01
            w.isUserInteractionEnabled = false
            if let win = UIApplication.shared.connectedScenes
                .compactMap({ ($0 as? UIWindowScene)?.windows.first }).first {
                win.addSubview(w)
            }
            web = w
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                self?.finish(PmError(self?.state == 0 ? "Страница входа не загрузилась — включён ли VPN?"
                                                     : "Сайт не ответил после ввода пароля"))
            }
            w.load(URLRequest(url: u))
        }
    }

    private func finish(_ error: Error?) {
        guard !finished else { return }
        finished = true
        web?.stopLoading()
        web?.navigationDelegate = nil
        web?.removeFromSuperview()
        web = nil
        if let e = error { cont?.resume(throwing: e) } else { cont?.resume() }
        cont = nil
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard !finished else { return }
        if state == 0 { tryFill(webView) } else { afterSubmit(webView) }
    }

    private func tryFill(_ w: WKWebView) {
        w.evaluateJavaScript(JS.fill(user: user, pass: pass)) { [weak self] res, _ in
            guard let self = self, !self.finished else { return }
            let r = res as? String ?? ""
            if r == "submitted" {
                self.state = 1
            } else if !PmCore.isLoginUrl(w.url?.absoluteString) {
                self.finish(nil) // уже вошли
            } else if self.fillTries < 6 {
                self.fillTries += 1
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
                    guard let self = self, !self.finished, let w = self.web else { return }
                    self.tryFill(w)
                }
            } else {
                self.finish(PmError("На странице не найдены поля логина/пароля"))
            }
        }
    }

    private func afterSubmit(_ w: WKWebView) {
        if !PmCore.isLoginUrl(w.url?.absoluteString) { finish(nil); return }
        w.evaluateJavaScript(JS.hasPassword) { [weak self] has, _ in
            guard let self = self, !self.finished, (has as? String) == "yes" else { return }
            w.evaluateJavaScript(JS.loginError) { [weak self] msg, _ in
                let m = msg as? String ?? ""
                self?.finish(PmError("Неверный логин или пароль" + (m.isEmpty ? "" : " (\(m))")))
            }
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        failed(error)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        failed(error)
    }

    private func failed(_ error: Error) {
        let ns = error as NSError
        if ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled { return }
        if let u = error as? URLError { finish(PmError(PmSession.netMessage(u))); return }
        finish(PmError("Ошибка сети: " + error.localizedDescription))
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        if navigationResponse.isForMainFrame, let h = navigationResponse.response as? HTTPURLResponse,
           h.statusCode >= 400, state == 0 {
            decisionHandler(.cancel)
            finish(PmError("Сайт ответил ошибкой \(h.statusCode)" + (h.statusCode == 403 ? " (нет VPN?)" : "")))
            return
        }
        decisionHandler(.allow)
    }
}
