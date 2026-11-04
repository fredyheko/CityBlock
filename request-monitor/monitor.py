#!/usr/bin/env python3
"""
Монитор заявок ProcessMaker (pm1.starnet.md) -> уведомления в Telegram.

Что делает:
  1. Открывает сайт в фоновом браузере (Chromium через Playwright).
  2. Входит под вашим логином/паролем.
  3. Раз в N секунд проверяет список "Unassigned" (неназначенные заявки).
  4. О каждой НОВОЙ заявке отправляет сообщение в Telegram.

Запуск:
  python monitor.py                 # работать постоянно
  python monitor.py --once          # одна проверка и выход
  python monitor.py --test-telegram # отправить тестовое сообщение в Telegram
  python monitor.py --show          # показать окно браузера (для отладки)
"""

import argparse
import html
import json
import logging
import os
import sys
import time
from pathlib import Path

import requests
from dotenv import load_dotenv
from playwright.sync_api import TimeoutError as PWTimeout
from playwright.sync_api import sync_playwright

HERE = Path(__file__).resolve().parent
load_dotenv(HERE / ".env")

log = logging.getLogger("monitor")


# ---------------------------------------------------------------- настройки
def env(name, default=None, required=False):
    value = os.getenv(name, default)
    if required and not value:
        sys.exit(f"Не задана настройка {name} в файле .env (см. .env.example)")
    return value


PM_HOST = env("PM_HOST", "https://pm1.starnet.md").rstrip("/")
PM_WORKSPACE = env("PM_WORKSPACE", "starnet")
PM_LANG = env("PM_LANG", "en")
PM_SKIN = env("PM_SKIN", "neoclassic")
PM_USERNAME = env("PM_USERNAME")
PM_PASSWORD = env("PM_PASSWORD")
# Какой список проверять: unassigned (Unassigned), todo (Inbox), draft, paused
PM_LIST = env("PM_LIST", "unassigned")

TG_TOKEN = env("TELEGRAM_BOT_TOKEN")
TG_CHAT_IDS = [c.strip() for c in env("TELEGRAM_CHAT_ID", "").split(",") if c.strip()]
TG_API = env("TELEGRAM_API_BASE", "https://api.telegram.org").rstrip("/")

# Необязательно: путь к уже установленному Chrome/Chromium/Edge
BROWSER_PATH = env("BROWSER_PATH") or None

CHECK_INTERVAL = int(env("CHECK_INTERVAL", "60"))  # секунд между проверками
# Если 1 — при первом запуске сообщить и о заявках, которые уже висят в списке
NOTIFY_EXISTING_ON_START = env("NOTIFY_EXISTING_ON_START", "1") == "1"
# Через сколько неудачных проверок подряд прислать в Telegram сообщение об ошибке
ERROR_ALERT_AFTER = int(env("ERROR_ALERT_AFTER", "5"))

STATE_FILE = HERE / "seen_cases.json"
DEBUG_DIR = HERE / "debug"

BASE = f"{PM_HOST}/sys{PM_WORKSPACE}/{PM_LANG}/{PM_SKIN}"
LOGIN_URL = f"{BASE}/login/login"
HOME_URL = f"{BASE}/cases/main"
LIST_API = f"{BASE}/cases/proxyCasesList"

ACTION_BY_LIST = {"unassigned": "unassigned", "todo": "todo", "inbox": "todo",
                  "draft": "draft", "paused": "paused"}


# ---------------------------------------------------------------- Telegram
def send_telegram(text):
    if not TG_TOKEN or not TG_CHAT_IDS:
        log.warning("Telegram не настроен, сообщение не отправлено:\n%s", text)
        return False
    ok = True
    for chat_id in TG_CHAT_IDS:
        try:
            r = requests.post(
                f"{TG_API}/bot{TG_TOKEN}/sendMessage",
                json={"chat_id": chat_id, "text": text, "parse_mode": "HTML",
                      "disable_web_page_preview": True},
                timeout=20,
            )
            if not r.ok:
                ok = False
                log.error("Telegram ответил %s: %s", r.status_code, r.text[:300])
        except requests.RequestException as e:
            ok = False
            log.error("Не удалось отправить в Telegram: %s", e)
    return ok


def format_case(case):
    e = lambda s: html.escape(str(s or "").strip())
    lines = [f"🔔 <b>Новая заявка #{e(case['number'])}</b>"]
    if case.get("title"):
        lines.append(f"📍 {e(case['title'])}")
    if case.get("process"):
        lines.append(f"⚙️ {e(case['process'])}")
    if case.get("task"):
        lines.append(f"📝 {e(case['task'])}")
    if case.get("sent_by"):
        lines.append(f"👤 {e(case['sent_by'])}")
    if case.get("due"):
        lines.append(f"⏰ Срок: {e(case['due'])}")
    lines.append(f'\n<a href="{e(HOME_URL)}">Открыть ProcessMaker</a>')
    return "\n".join(lines)


# ---------------------------------------------------------------- состояние
def load_seen():
    try:
        return set(json.loads(STATE_FILE.read_text(encoding="utf-8")))
    except (FileNotFoundError, ValueError):
        return None  # первый запуск


def save_seen(keys):
    STATE_FILE.write_text(json.dumps(sorted(keys), ensure_ascii=False, indent=1),
                          encoding="utf-8")


# ---------------------------------------------------------------- ProcessMaker
class LoginError(Exception):
    pass


class ProcessMaker:
    def __init__(self, pw, show=False):
        self.browser = pw.chromium.launch(headless=not show,
                                           executable_path=BROWSER_PATH)
        self.context = self.browser.new_context(locale="en-US")
        self.page = self.context.new_page()
        self.page.set_default_timeout(30_000)

    def close(self):
        self.browser.close()

    def screenshot(self, name):
        DEBUG_DIR.mkdir(exist_ok=True)
        path = DEBUG_DIR / f"{name}.png"
        try:
            self.page.screenshot(path=str(path), full_page=True)
            log.info("Скриншот сохранён: %s", path)
        except Exception:
            pass

    # --- вход
    def login(self):
        if not PM_USERNAME or not PM_PASSWORD:
            sys.exit("Укажите PM_USERNAME и PM_PASSWORD в файле .env")
        log.info("Вход на %s ...", LOGIN_URL)
        page = self.page
        page.goto(LOGIN_URL, wait_until="domcontentloaded")

        user = page.locator(
            'input[name="form[USR_USERNAME]"], input[id="form[USR_USERNAME]"], '
            'input[name*="USERNAME" i], input[name="username"], input[type="email"]'
        ).first
        pwd = page.locator('input[type="password"]:visible').first
        user.wait_for(state="visible")
        user.fill(PM_USERNAME)
        pwd.fill(PM_PASSWORD)

        submit = page.locator(
            'button[type="submit"], input[type="submit"], '
            'input[id="form[BSUBMIT]"], button[id="form[BSUBMIT]"]'
        ).first
        if submit.count():
            submit.click()
        else:
            pwd.press("Enter")

        try:
            page.wait_for_url(lambda url: "/login/" not in url, timeout=30_000)
        except PWTimeout:
            self.screenshot("login_failed")
            raise LoginError("Не удалось войти: проверьте логин/пароль "
                             "(скриншот в папке debug/)")
        log.info("Вход выполнен")

    # --- получение списка заявок
    def fetch_cases(self):
        """Вернуть список заявок. Перелогинивается, если сессия истекла."""
        try:
            return self._fetch_api()
        except LoginError:
            raise
        except _SessionExpired:
            log.info("Сессия истекла, вхожу заново")
            self.login()
            return self._fetch_api()

    def _fetch_api(self):
        action = ACTION_BY_LIST.get(PM_LIST.lower(), PM_LIST)
        resp = self.page.request.get(LIST_API, params={
            "action": action, "start": "0", "limit": "100",
            "sort": "APP_NUMBER", "dir": "DESC",
        })
        text = resp.text()
        if resp.status in (401, 403) or "/login/" in resp.url:
            raise _SessionExpired()
        try:
            data = json.loads(text)
        except ValueError:
            # ProcessMaker при истёкшей сессии отдаёт HTML-страницу входа
            if "login" in text.lower() or "USR_USERNAME" in text:
                raise _SessionExpired()
            log.debug("Ответ сервера: %s", text[:500])
            raise RuntimeError(f"Неожиданный ответ сервера ({resp.status})")
        rows = data.get("data") if isinstance(data, dict) else data
        if rows is None:
            raise RuntimeError(f"В ответе нет списка заявок: {str(data)[:300]}")
        return [_normalize(r) for r in rows]


class _SessionExpired(Exception):
    pass


def _pick(row, *keys):
    for k in keys:
        if row.get(k):
            return row[k]
    return ""


def _normalize(row):
    number = _pick(row, "APP_NUMBER", "app_number")
    uid = _pick(row, "APP_UID", "app_uid")
    del_index = _pick(row, "DEL_INDEX", "del_index")
    sent_by = _pick(row, "APP_DEL_PREVIOUS_USER", "PREVIOUS_USR_NAME",
                    "PREVIOUS_USR_USERNAME")
    return {
        "key": f"{uid or number}:{del_index}",
        "number": number,
        "title": _pick(row, "APP_TITLE", "CASE_TITLE", "APP_TITLE_SHORT"),
        "process": _pick(row, "APP_PRO_TITLE", "PRO_TITLE"),
        "task": _pick(row, "APP_TAS_TITLE", "TAS_TITLE"),
        "sent_by": sent_by,
        "due": _pick(row, "DEL_TASK_DUE_DATE", "DEL_TASK_DUE_DATE_LABEL"),
    }


# ---------------------------------------------------------------- основной цикл
def check_once(pm, seen, first_run):
    cases = pm.fetch_cases()
    log.info("Заявок в списке '%s': %d", PM_LIST, len(cases))
    current = {c["key"] for c in cases}

    new = [c for c in cases if c["key"] not in seen]
    if first_run and not NOTIFY_EXISTING_ON_START:
        new = []
    for case in reversed(new):  # от старых к новым
        log.info("Новая заявка #%s: %s", case["number"], case["title"])
        send_telegram(format_case(case))

    # помним только то, что сейчас в списке: если заявку вернут снова — сообщим
    return current


def main():
    ap = argparse.ArgumentParser(description="Монитор заявок ProcessMaker -> Telegram")
    ap.add_argument("--once", action="store_true", help="одна проверка и выход")
    ap.add_argument("--show", action="store_true", help="показывать окно браузера")
    ap.add_argument("--test-telegram", action="store_true",
                    help="отправить тестовое сообщение и выйти")
    ap.add_argument("--verbose", action="store_true", help="подробный лог")
    args = ap.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s  %(levelname)-7s %(message)s", datefmt="%Y-%m-%d %H:%M:%S",
        handlers=[logging.StreamHandler(),
                  logging.FileHandler(HERE / "monitor.log", encoding="utf-8")],
    )

    if args.test_telegram:
        ok = send_telegram("✅ Монитор заявок ProcessMaker: тестовое сообщение")
        print("Отправлено" if ok else "Ошибка — см. лог выше")
        return 0 if ok else 1

    seen = load_seen()
    first_run = seen is None
    seen = seen or set()
    failures = 0

    with sync_playwright() as pw:
        pm = ProcessMaker(pw, show=args.show)
        try:
            pm.login()
            if not args.once:
                send_telegram("▶️ Монитор заявок запущен")
            while True:
                try:
                    seen = check_once(pm, seen, first_run)
                    save_seen(seen)
                    first_run = False
                    if failures >= ERROR_ALERT_AFTER:
                        send_telegram("✅ Связь с ProcessMaker восстановлена")
                    failures = 0
                except LoginError:
                    raise
                except Exception as e:
                    failures += 1
                    log.exception("Ошибка проверки (%d подряд): %s", failures, e)
                    if failures == ERROR_ALERT_AFTER:
                        send_telegram(f"⚠️ Монитор заявок: {failures} ошибок подряд.\n"
                                      f"{html.escape(str(e))[:300]}")
                    # пересоздаём сессию на всякий случай
                    try:
                        pm.login()
                    except Exception as e2:
                        log.error("Повторный вход не удался: %s", e2)
                if args.once:
                    break
                time.sleep(CHECK_INTERVAL)
        except LoginError as e:
            log.error("%s", e)
            send_telegram(f"⛔️ Монитор заявок: {html.escape(str(e))}")
            return 2
        except KeyboardInterrupt:
            log.info("Остановлено пользователем")
        finally:
            pm.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
