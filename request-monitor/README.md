# Монитор заявок ProcessMaker → Telegram

Программа сама входит на https://pm1.starnet.md под вашим логином, раз в минуту
проверяет список **Unassigned** и присылает в Telegram сообщение о каждой новой заявке:

```
🔔 Новая заявка #16601
📍 client01 : RISCANI, STR. EXEMPLU, 16
⚙️ Conectare Client Home (Epicor)
📝 Selectare Operatiune
👤 Dispecer, Test
⏰ Срок: 2026-11-04 17:00:00
```

> ⚠️ Запускать нужно на компьютере, с которого открывается pm1.starnet.md
> (рабочий ПК или ПК в Молдове). С других серверов сайт отвечает 403.

## 1. Создать Telegram-бота

1. В Telegram откройте **@BotFather** → `/newbot` → скопируйте **токен**.
2. Напишите своему боту любое сообщение (например, `/start`).
3. Откройте в браузере `https://api.telegram.org/bot<ТОКЕН>/getUpdates`
   и найдите `"chat":{"id": 123456789` — это ваш **chat id**.

## 2. Установка

Нужен Python 3.9+ (https://www.python.org/downloads/, при установке на Windows
отметьте «Add Python to PATH»).

```bash
cd request-monitor
python -m pip install -r requirements.txt
python -m playwright install chromium
```

## 3. Настройка

Скопируйте `.env.example` в `.env` и заполните:

| Параметр | Что это |
|---|---|
| `PM_USERNAME`, `PM_PASSWORD` | логин и пароль от ProcessMaker |
| `TELEGRAM_BOT_TOKEN` | токен от @BotFather |
| `TELEGRAM_CHAT_ID` | ваш chat id (можно несколько через запятую) |
| `PM_LIST` | `unassigned` (по умолчанию) или `todo` = Inbox |
| `CHECK_INTERVAL` | как часто проверять, в секундах (по умолчанию 60) |

Файл `.env` содержит пароль — никому его не отправляйте, в git он не попадает.

## 4. Запуск

```bash
python monitor.py --test-telegram   # проверить, что бот пишет вам
python monitor.py --once            # одна проверка сайта
python monitor.py                   # работать постоянно (Ctrl+C — остановить)
```

На Windows можно просто дважды кликнуть `run.bat`.

Если что-то не работает:
- `python monitor.py --once --show --verbose` — откроется окно браузера, видно, что происходит;
- при ошибке входа скриншот сохраняется в `debug/login_failed.png`;
- лог пишется в `monitor.log`.

### Автозапуск на Windows
Планировщик заданий → «Создать задачу» → Триггер «При входе в систему» →
Действие «Запуск программы» → `run.bat` из этой папки.

### Автозапуск на Linux (cron)
```
@reboot cd /путь/к/request-monitor && python3 monitor.py >> /dev/null 2>&1
```

## Как это работает

- Вход выполняется через настоящий браузер (Chromium в фоне), как будто вы входите вручную.
- Список заявок берётся из того же запроса, которым его загружает сам сайт
  (`cases/proxyCasesList?action=unassigned`).
- Уже отправленные заявки запоминаются в `seen_cases.json`, повторов не будет.
  Если заявку заберут, а потом вернут в Unassigned, придёт новое уведомление.
- Если сессия истекла, программа входит заново сама. После 5 ошибок подряд
  (сайт недоступен и т. п.) в Telegram приходит предупреждение.
