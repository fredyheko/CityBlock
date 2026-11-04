import Foundation

/// Разбор Case Description: «Имя | телефон | услуга и адрес | техданные … дата время логин комментарий».
enum CaseText {
    // Молдавские номера: +373 / 373 / 0, затем 8 цифр.
    private static let phoneRe = try! NSRegularExpression(
        pattern: #"(?<![\w+])(?:\+?373|0)[\s\-.]?\(?([2-9]\d)\)?((?:[\s\-.]?\d){6})(?!\w)"#)
    private static let phoneShortRe = try! NSRegularExpression(
        pattern: #"(?<![\w+\-.])([67]\d{7})(?!\w|[\-.]\d)"#)
    private static let stampRe = try! NSRegularExpression(
        pattern: #"\d{4}-\d{2}-\d{2}[ T]\d{1,2}:\d{2}(?::\d{2})?"#)
    private static let loginRe = try! NSRegularExpression(
        pattern: #"^([a-z][a-z0-9._]{3,})\s+(\S.*)$"#, options: [.dotMatchesLineSeparators])
    private static let descLabelRe = try! NSRegularExpression(
        pattern: #"^(case\s*)?descri|^описание"#, options: [.caseInsensitive])
    private static let descHtmlRe = try! NSRegularExpression(
        pattern: #">\s*(?:Case\s*)?Description\s*:?\s*</t[dh]>\s*<td[^>]*>(.*?)</td>"#,
        options: [.caseInsensitive, .dotMatchesLineSeparators])

    static func digits(_ s: String) -> String { s.filter(\.isNumber) }

    static func findPhones(_ text: String) -> [String] {
        var out: [String] = []
        let ns = NSRange(text.startIndex..., in: text)
        for m in phoneRe.matches(in: text, range: ns) {
            if let a = Range(m.range(at: 1), in: text), let b = Range(m.range(at: 2), in: text) {
                let p = "0" + text[a] + digits(String(text[b]))
                if !out.contains(p) { out.append(p) }
            }
        }
        for m in phoneShortRe.matches(in: text, range: ns) {
            if let a = Range(m.range(at: 1), in: text) {
                let p = "0" + text[a]
                if !out.contains(p) { out.append(p) }
            }
        }
        return out
    }

    /// 069123456 → +373 69 123 456
    static func pretty(_ phone: String) -> String {
        var d = digits(phone)
        if d.hasPrefix("0") && d.count == 9 { d.removeFirst() }
        guard d.count == 8 else { return phone }
        let a = Array(d)
        return "+373 \(String(a[0..<2])) \(String(a[2..<5])) \(String(a[5..<8]))"
    }

    /// 069123456 → +37369123456
    static func international(_ phone: String) -> String {
        var d = digits(phone)
        if d.hasPrefix("0") && d.count == 9 { d.removeFirst() }
        return d.count == 8 ? "+373" + d : phone
    }

    static func clientName(_ description: String) -> String {
        let head = description.components(separatedBy: "|")[0].trimmingCharacters(in: .whitespaces)
        if head.isEmpty || head.count > 60 || head.contains(where: \.isNumber) { return "" }
        return head
    }

    struct Comment: Hashable { var when = "", author = "", text = "" }

    /// Комментарии: текст после каждой метки времени (новые записи идут первыми).
    static func comments(_ description: String) -> [Comment] {
        let s = description
        let marks = stampRe.matches(in: s, range: NSRange(s.startIndex..., in: s)).compactMap { Range($0.range, in: s) }
        var out: [Comment] = []
        for (i, m) in marks.enumerated() {
            let end = i + 1 < marks.count ? marks[i + 1].lowerBound : s.endIndex
            let t = s[m.upperBound..<end].replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
                .trimmingCharacters(in: .whitespaces)
            if t.isEmpty { continue }
            var c = Comment(when: String(s[m]), author: "", text: t)
            if let lm = loginRe.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)),
               let a = Range(lm.range(at: 1), in: t), let b = Range(lm.range(at: 2), in: t) {
                c.author = String(t[a])
                c.text = String(t[b]).trimmingCharacters(in: .whitespaces)
            }
            out.append(c)
        }
        return out
    }

    private static func segments(_ d: String) -> [String] {
        guard d.contains("|") else { return [] }
        return d.components(separatedBy: "|").map { $0.trimmingCharacters(in: .whitespaces) }
    }

    /// «FTTH RISCANI, STR. …, 13, 1»
    static func service(_ d: String) -> String {
        let seg = segments(d)
        return seg.count > 2 ? seg[2] : ""
    }

    /// Порт, свитч, ODF, кабель, шкаф… — по строке на элемент.
    static func techLines(_ d: String) -> [String] {
        let seg = segments(d)
        guard seg.count >= 4 else { return [] }
        var tech = seg[3...].joined(separator: ", ")
        if let m = stampRe.firstMatch(in: tech, range: NSRange(tech.startIndex..., in: tech)),
           let r = Range(m.range, in: tech) {
            tech = String(tech[..<r.lowerBound])
        }
        return tech.components(separatedBy: ", ").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    // MARK: ответы сайта

    /// Поле «Case Description» из окна Summary (JSON {label,value}/APP_DESCRIPTION или HTML-таблица).
    static func caseDescription(_ body: String) -> String {
        let t = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.hasPrefix("{") || t.hasPrefix("["), let root = PmCore.json(t) {
            let v = walk(root)
            if !v.isEmpty { return v }
        }
        if let m = descHtmlRe.firstMatch(in: body, range: NSRange(body.startIndex..., in: body)),
           let r = Range(m.range(at: 1), in: body) {
            let v = toText(String(body[r]))
            if !v.isEmpty { return v }
        }
        return ""
    }

    private static func walk(_ o: Any) -> String {
        if let j = o as? [String: Any] {
            let label = PmCore.pick(j, "label", "fieldLabel", "name", "title")
            let value = PmCore.pick(j, "value", "text")
            let l = toText(label)
            if !label.isEmpty && !value.isEmpty,
               descLabelRe.firstMatch(in: l, range: NSRange(l.startIndex..., in: l)) != nil {
                return toText(value)
            }
            let direct = PmCore.pick(j, "APP_DESCRIPTION", "CASE_DESCRIPTION", "DESCRIPTION")
            if !direct.isEmpty { return toText(direct) }
            for (_, v) in j {
                let r = walk(v)
                if !r.isEmpty { return r }
            }
        } else if let a = o as? [Any] {
            for v in a {
                let r = walk(v)
                if !r.isEmpty { return r }
            }
        }
        return ""
    }

    static func parseNotes(_ body: String) -> [String] {
        let t = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, let root = PmCore.json(t) else { return [] }
        var arr: [Any] = []
        if let a = root as? [Any] { arr = a }
        else if let o = root as? [String: Any] { arr = (o["notes"] as? [Any]) ?? (o["data"] as? [Any]) ?? [] }
        var out: [String] = []
        for case let n as [String: Any] in arr {
            let content = PmCore.pick(n, "NOTE_CONTENT", "note_content", "content")
            if content.isEmpty { continue }
            var who = (PmCore.pick(n, "USR_FIRSTNAME") + " " + PmCore.pick(n, "USR_LASTNAME")).trimmingCharacters(in: .whitespaces)
            if who.isEmpty { who = PmCore.pick(n, "USR_USERNAME", "USR_NAME") }
            let date = PmCore.pick(n, "NOTE_DATE", "note_date")
            let head = (who + (date.isEmpty ? "" : ", " + date)).trimmingCharacters(in: .whitespaces)
            out.append((head.isEmpty ? "" : head + ": ") + toText(content))
        }
        return out
    }

    static func toText(_ s: String) -> String {
        var t = s.replacingOccurrences(of: #"(?is)<(script|style)[^>]*>.*?</\1>"#, with: " ", options: .regularExpression)
        t = t.replacingOccurrences(of: #"(?i)<br\s*/?>|</(p|div|tr|li|h\d)>"#, with: "\n", options: .regularExpression)
        t = t.replacingOccurrences(of: #"(?i)</t[dh]>"#, with: " ", options: .regularExpression)
        t = t.replacingOccurrences(of: #"<[^>]+>"#, with: " ", options: .regularExpression)
        for (a, b) in [("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", "\""), ("&#39;", "'")] {
            t = t.replacingOccurrences(of: a, with: b)
        }
        t = t.replacingOccurrences(of: #"[ \t\f\r]+"#, with: " ", options: .regularExpression)
        t = t.replacingOccurrences(of: #" *\n[ \n]*"#, with: "\n", options: .regularExpression)
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Заполнить клиента/телефон/описание/заметки из ответов сайта.
    static func fill(_ c: inout PmCase, responses: [(name: String, body: String)]) {
        var desc = c.description
        var notes: [String] = []
        for r in responses where r.name == "notes" { notes += parseNotes(r.body) }
        if desc.isEmpty {
            for r in responses where r.name != "notes" {
                desc = caseDescription(r.body)
                if !desc.isEmpty { break }
            }
        }
        c.notes = notes
        if !desc.isEmpty {
            c.description = desc
            c.client = clientName(desc)
            c.phones = findPhones(desc)
        } else {
            var phones: [String] = []
            for r in responses where r.name != "notes" {
                for p in findPhones(toText(r.body)) where !phones.contains(p) { phones.append(p) }
            }
            c.phones = phones
        }
    }
}
