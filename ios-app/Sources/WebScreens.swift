import SwiftUI
import UIKit
import WebKit

/// Общий WKWebView: JS-диалоги (alert/confirm/prompt), ссылки в новом окне, камера для QR.
@MainActor
class WebController: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate {
    let web: WKWebView
    @Published var title = "ProcessMaker"
    @Published var toast: String?

    override init() {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .default()
        cfg.allowsInlineMediaPlayback = true
        web = WKWebView(frame: .zero, configuration: cfg)
        super.init()
        web.navigationDelegate = self
        web.uiDelegate = self
        web.allowsBackForwardNavigationGestures = true
    }

    func load(_ url: String) {
        guard let u = URL(string: url) else { return }
        Task { @MainActor in
            await PmSession.shared.syncCookiesToWeb()
            self.web.load(URLRequest(url: u))
        }
    }

    func showToast(_ s: String) {
        toast = s
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.toast = nil }
    }

    // MARK: JS-диалоги
    private var topVC: UIViewController? {
        var vc = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.windows.first(where: \.isKeyWindow) }
            .first?.rootViewController
        while let p = vc?.presentedViewController { vc = p }
        return vc
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        let a = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler() })
        if let vc = topVC { vc.present(a, animated: true) } else { completionHandler() }
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let a = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "Отмена", style: .cancel) { _ in completionHandler(false) })
        a.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(true) })
        if let vc = topVC { vc.present(a, animated: true) } else { completionHandler(false) }
    }

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String,
                 defaultText: String?, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (String?) -> Void) {
        let a = UIAlertController(title: nil, message: prompt, preferredStyle: .alert)
        a.addTextField { $0.text = defaultText }
        a.addAction(UIAlertAction(title: "Отмена", style: .cancel) { _ in completionHandler(nil) })
        a.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(a.textFields?.first?.text) })
        if let vc = topVC { vc.present(a, animated: true) } else { completionHandler(nil) }
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil { webView.load(navigationAction.request) }
        return nil
    }

    func webView(_ webView: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin,
                 initiatedByFrame frame: WKFrameInfo, type: WKMediaCaptureType,
                 decisionHandler: @escaping (WKPermissionDecision) -> Void) {
        decisionHandler(.grant) // QR-сканер в формах PM
    }

    // MARK: навигация — подклассы переопределяют хуки
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { pageFinished(webView) }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        let isMain = navigationAction.targetFrame?.isMainFrame ?? true
        if isMain && intercept(navigationAction.request.url?.absoluteString) {
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didReceiveServerRedirectForProvisionalNavigation navigation: WKNavigation!) {
        if intercept(webView.url?.absoluteString) { webView.stopLoading() }
    }

    func pageFinished(_ webView: WKWebView) {}
    /// true — остановить переход (подкласс сам решает, что делать дальше).
    func intercept(_ url: String?) -> Bool { false }
}

struct WebViewHost: UIViewRepresentable {
    let web: WKWebView
    func makeUIView(context: Context) -> WKWebView { web }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

// MARK: - Сайт PM целиком

struct WebScreen: View {
    let url: String
    @Environment(\.dismiss) private var dismiss
    @StateObject private var ctl = WebController()

    var body: some View {
        NavigationStack {
            WebViewHost(web: ctl.web)
                .ignoresSafeArea(edges: .bottom)
                .navigationTitle("ProcessMaker")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() } }
                    ToolbarItem(placement: .primaryAction) {
                        Button { ctl.web.reload() } label: { Image(systemName: "arrow.clockwise") }
                    }
                }
        }
        .onAppear { if ctl.web.url == nil { ctl.load(url) } }
    }
}

// MARK: - Форма заявки на весь экран

final class FormController: WebController {
    var uid = ""
    var del = ""
    var action = "todo"
    var number = ""
    @Published var wide = false
    var onDone: ((String) -> Void)?
    private var checking = false

    func start(_ t: FormTarget) {
        uid = t.uid
        del = t.del
        action = t.action
        number = t.number
        updateTitle()
        load(PmCore.formUrl(PmSession.shared.config.base, uid: uid, del: del, action: action))
    }

    func updateTitle() { title = number.isEmpty ? "Новая заявка" : "Заявка #" + number }

    func reloadForm() {
        load(PmCore.formUrl(PmSession.shared.config.base, uid: uid, del: del, action: action))
    }

    override func pageFinished(_ webView: WKWebView) {
        if wide {
            webView.evaluateJavaScript(JS.wideViewport)
        } else {
            webView.evaluateJavaScript(JS.mobileViewport + JS.formFix)
        }
    }

    override func intercept(_ url: String?) -> Bool {
        guard PmCore.isCaseListUrl(url) else { return false }
        afterStepSent()
        return true
    }

    /// Этап отправлен (PM ушёл к списку): следующий этап снова ваш — открыть, иначе — готово.
    private func afterStepSent() {
        guard !checking else { return }
        checking = true
        title = "Проверяю следующий этап…"
        let prevDel = del
        let caseUid = uid
        Task { @MainActor in
            let mine = await PmSession.shared.findMine(uid: caseUid)
            self.checking = false
            if let m = mine, m.delIndex != prevDel {
                self.del = m.delIndex
                self.action = "todo"
                if !m.number.isEmpty { self.number = m.number }
                self.updateTitle()
                self.showToast("Следующий этап")
                self.reloadForm()
            } else {
                self.onDone?(mine == nil ? "Заявка отправлена ✓" : "Готово")
            }
        }
    }
}

struct FormScreen: View {
    let target: FormTarget
    @Environment(\.dismiss) private var dismiss
    @StateObject private var ctl = FormController()
    @State private var full: WebTarget?
    @State private var doneText: String?

    var body: some View {
        NavigationStack {
            WebViewHost(web: ctl.web)
                .ignoresSafeArea(edges: .bottom)
                .navigationTitle(ctl.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() } }
                    ToolbarItem(placement: .primaryAction) {
                        Menu {
                            Button { ctl.reloadForm() } label: { Label("Обновить", systemImage: "arrow.clockwise") }
                            Button {
                                ctl.wide.toggle()
                                ctl.web.reload()
                            } label: {
                                Label(ctl.wide ? "Мобильный вид" : "Широкий вид (как на компьютере)",
                                      systemImage: "arrow.left.and.right")
                            }
                            Button {
                                var c = PmCase()
                                c.uid = ctl.uid
                                c.delIndex = ctl.del
                                full = WebTarget(url: PmCore.openCaseUrl(PmSession.shared.config.base, c, ctl.action))
                            } label: { Label("Открыть в полном PM", systemImage: "macwindow") }
                        } label: { Image(systemName: "ellipsis.circle") }
                    }
                }
                .overlay(alignment: .bottom) {
                    if let t = ctl.toast {
                        Text(t).padding(.horizontal, 16).padding(.vertical, 10)
                            .background(Capsule().fill(Color.black.opacity(0.8))).foregroundColor(.white)
                            .padding(.bottom, 30)
                    }
                }
        }
        .onAppear {
            if ctl.web.url == nil {
                ctl.onDone = { text in doneText = text }
                ctl.start(target)
            }
        }
        .alert(doneText ?? "", isPresented: Binding(get: { doneText != nil }, set: { if !$0 { doneText = nil } })) {
            Button("OK") { dismiss() }
        }
        .fullScreenCover(item: $full) { WebScreen(url: $0.url) }
    }
}
