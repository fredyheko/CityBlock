import Foundation

/// Заявка ProcessMaker.
struct PmCase: Identifiable, Hashable {
    var key = "", uid = "", delIndex = "1", number = "", title = "", process = "", task = "", sentBy = "", due = ""
    var description = "", client = ""
    var phones: [String] = []
    var notes: [String] = []

    var id: String { key }
    var hasDetails: Bool { !description.isEmpty || !phones.isEmpty || !notes.isEmpty }
}

struct SessionExpired: Error {}

struct PmError: LocalizedError {
    let message: String
    init(_ m: String) { message = m }
    var errorDescription: String? { message }
}

/// Логика ProcessMaker без интерфейса (перенесена из Android-версии).
enum PmCore {
    // MARK: адреса
    static func base(host: String, workspace: String) -> String {
        var h = host.trimmingCharacters(in: .whitespacesAndNewlines)
        while h.hasSuffix("/") { h.removeLast() }
        if !h.hasPrefix("http://") && !h.hasPrefix("https://") { h = "https://" + h }
        return h + "/sys" + workspace.trimmingCharacters(in: .whitespacesAndNewlines) + "/en/neoclassic"
    }

    static func loginUrl(_ b: String) -> String { b + "/login/login" }
    static func homeUrl(_ b: String) -> String { b + "/cases/main" }
    static func listUrl(_ b: String, _ action: String) -> String {
        b + "/cases/proxyCasesList?action=\(action)&start=0&limit=100&sort=APP_NUMBER&dir=DESC"
    }
    static func isLoginUrl(_ u: String?) -> Bool { u?.contains("/login/") ?? false }
    static func openCaseUrl(_ b: String, _ c: PmCase, _ action: String) -> String {
        b + "/cases/open?APP_UID=\(c.uid)&DEL_INDEX=\(c.delIndex)&action=\(action)"
    }
    static func catchOpenUrl(_ b: String, _ c: PmCase) -> String {
        b + "/cases/cases_Open?APP_UID=\(c.uid)&DEL_INDEX=\(c.delIndex)&action=unassigned"
    }
    static let catchExecutePath = "/cases/cases_CatchExecute"
    static func claimPost(_ c: PmCase) -> String {
        "notValidateThisFields=\(enc("[]"))&DynaformRequiredFields=\(enc("[]"))&DynaformName=cases_Resume"
            + "&\(enc("form[APP_UID]"))=\(enc(c.uid))&\(enc("form[APP_NUMBER]"))=\(enc(c.number))"
            + "&\(enc("form[BTN_CATCH]"))=\(enc("Claim this case"))"
    }
    static func formUrl(_ b: String, uid: String, del: String, action: String) -> String {
        b + "/cases/cases_Open?APP_UID=\(uid)&DEL_INDEX=\(del)&action=\(action)"
    }
    static func isCaseListUrl(_ u: String?) -> Bool {
        guard let u = u else { return false }
        return u.contains("/cases/casesListExtJs") || u.contains("/cases/main") || u.contains("/cases/casesList")
    }

    static let startAjaxPath = "/cases/casesStartPage_Ajax"
    static func startPageUrl(_ b: String) -> String { b + "/cases/casesStartPage?action=startCase" }
    static let startListPost = "action=getProcessList&node=root"
    static func startCasePost(_ it: StartItem) -> String {
        "action=startCase&processId=\(enc(it.proUid))&taskId=\(enc(it.tasUid))"
    }

    private static let formChars = CharacterSet(charactersIn:
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._*")
    static func enc(_ s: String) -> String {
        (s.addingPercentEncoding(withAllowedCharacters: formChars) ?? s).replacingOccurrences(of: "%20", with: "+")
    }

    // MARK: JSON-помощники
    static func str(_ v: Any?) -> String {
        switch v {
        case let s as String: return s.trimmingCharacters(in: .whitespacesAndNewlines)
        case let n as NSNumber: return n.stringValue
        default: return ""
        }
    }

    static func pick(_ d: [String: Any], _ keys: String...) -> String {
        for k in keys {
            let s = str(d[k])
            if !s.isEmpty && s != "null" { return s }
        }
        return ""
    }

    static func json(_ s: String) -> Any? {
        guard let d = s.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed])
    }

    // MARK: список заявок
    static func parse(_ body: String, finalUrl: String) throws -> [PmCase] {
        if isLoginUrl(finalUrl) { throw SessionExpired() }
        let t = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if !t.hasPrefix("{") && !t.hasPrefix("[") {
            let low = t.lowercased()
            if low.contains("login") || low.contains("usr_username") || low.contains("password") { throw SessionExpired() }
            throw PmError("Неожиданный ответ сервера: " + String(t.prefix(150)))
        }
        var rows: [Any] = []
        if let a = json(t) as? [Any] {
            rows = a
        } else if let o = json(t) as? [String: Any] {
            if let a = o["data"] as? [Any] {
                rows = a
            } else {
                let low = (str(o["message"]) + " " + t).lowercased()
                if low.contains("session") || low.contains("login") { throw SessionExpired() }
                throw PmError("В ответе нет списка заявок: " + String(t.prefix(150)))
            }
        } else {
            throw PmError("Не удалось разобрать ответ сервера")
        }
        return rows.compactMap { $0 as? [String: Any] }.map(normalize)
    }

    static func normalize(_ r: [String: Any]) -> PmCase {
        var c = PmCase()
        c.number = pick(r, "APP_NUMBER", "app_number")
        c.uid = pick(r, "APP_UID", "app_uid")
        let del = pick(r, "DEL_INDEX", "del_index")
        c.delIndex = del.isEmpty ? "1" : del
        c.title = pick(r, "APP_TITLE", "CASE_TITLE", "APP_TITLE_SHORT")
        c.process = pick(r, "APP_PRO_TITLE", "PRO_TITLE")
        c.task = pick(r, "APP_TAS_TITLE", "TAS_TITLE")
        c.sentBy = pick(r, "APP_DEL_PREVIOUS_USER", "PREVIOUS_USR_NAME", "PREVIOUS_USR_USERNAME")
        c.due = pick(r, "DEL_TASK_DUE_DATE", "DEL_TASK_DUE_DATE_LABEL")
        c.description = pick(r, "APP_DESCRIPTION", "CASE_DESCRIPTION", "DESCRIPTION")
        c.key = (c.uid.isEmpty ? c.number : c.uid) + ":" + del
        if !c.description.isEmpty {
            c.client = CaseText.clientName(c.description)
            c.phones = CaseText.findPhones(c.description)
        }
        return c
    }

    static func find(_ cases: [PmCase], uid: String) -> PmCase? { cases.first { $0.uid == uid } }

    // MARK: заголовок «account : адрес : тип : дата | слот»
    struct Title { var account = "", address = "", type = "", date = "", slot = "" }

    static func title(_ t: String) -> Title {
        var r = Title()
        let seg = split(t, by: #"\s+:\s+"#)
        if seg.count < 2 { r.address = t.trimmingCharacters(in: .whitespaces); return r }
        r.account = seg[0].trimmingCharacters(in: .whitespaces)
        r.address = seg[1].trimmingCharacters(in: .whitespaces)
        if seg.count > 2 { r.type = seg[2].trimmingCharacters(in: .whitespaces) }
        if seg.count > 3 {
            let rest = seg[3...].joined(separator: " : ")
            let ds = rest.components(separatedBy: "|")
            r.date = ds[0].trimmingCharacters(in: .whitespaces)
            if ds.count > 1 { r.slot = ds[1].trimmingCharacters(in: .whitespaces) }
        }
        return r
    }

    /// Заголовок — просто номер («#1669021» или пусто).
    static func isBareTitle(_ t: String) -> Bool {
        let s = t.trimmingCharacters(in: .whitespaces)
        return s.isEmpty || s.range(of: #"^#?\s*\d+$"#, options: .regularExpression) != nil
    }

    static func split(_ s: String, by pattern: String) -> [String] {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return [s] }
        var out: [String] = []
        var last = s.startIndex
        for m in re.matches(in: s, range: NSRange(s.startIndex..., in: s)) {
            guard let r = Range(m.range, in: s) else { continue }
            out.append(String(s[last..<r.lowerBound]))
            last = r.upperBound
        }
        out.append(String(s[last...]))
        return out
    }

    // MARK: New case
    struct StartItem: Identifiable, Hashable {
        var category = "", title = "", proUid = "", tasUid = ""
        var id: String { proUid + "/" + tasUid }
    }

    static func parseStartList(_ body: String) throws -> [StartItem] {
        let t = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.hasPrefix("[") || t.hasPrefix("{"), let root = json(t) else {
            throw PmError("Неожиданный ответ: " + String(t.prefix(120)))
        }
        var out: [StartItem] = []
        walkStart(root, category: "", into: &out)
        return out
    }

    private static func walkStart(_ o: Any, category: String, into out: inout [StartItem]) {
        if let a = o as? [Any] {
            for x in a { walkStart(x, category: category, into: &out) }
            return
        }
        guard let j = o as? [String: Any] else { return }
        let text = pick(j, "text", "label", "title", "PRO_TITLE", "name")
        let tas = pick(j, "tas_uid", "TAS_UID", "taskId", "task_uid")
        let pro = pick(j, "pro_uid", "PRO_UID", "processId", "process_uid")
        if !tas.isEmpty && !pro.isEmpty {
            out.append(StartItem(category: category, title: text, proUid: pro, tasUid: tas))
        }
        let childCat = (tas.isEmpty && !text.isEmpty) ? text : category
        for k in ["children", "data", "processes", "items"] {
            if let ch = j[k] { walkStart(ch, category: childCat, into: &out) }
        }
    }

    /// Из ответа startCase: (APP_UID, DEL_INDEX).
    static func parseStarted(_ body: String) throws -> (String, String) {
        let t = body.replacingOccurrences(of: "\\/", with: "/")
        if let uid = firstGroup(#"APP_UID["'=:\s]+([0-9a-fA-F]{32})"#, in: t) {
            return (uid, firstGroup(#"DEL_INDEX["'=:\s]+(\d+)"#, in: t) ?? "1")
        }
        var msg = ""
        if let o = json(t.trimmingCharacters(in: .whitespacesAndNewlines)) as? [String: Any] {
            msg = pick(o, "message", "msg")
        }
        throw PmError(msg.isEmpty ? "Сайт не вернул номер новой заявки" : msg)
    }

    static func firstGroup(_ pattern: String, in s: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]),
              let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)),
              m.numberOfRanges > 1, let r = Range(m.range(at: 1), in: s) else { return nil }
        return String(s[r])
    }
}
