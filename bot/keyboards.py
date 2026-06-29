"""Telegram keyboards — compatible with pyTelegramBotAPI 4.x."""

from telebot.types import InlineKeyboardMarkup, InlineKeyboardButton, ReplyKeyboardMarkup, KeyboardButton


def quality_keyboard() -> InlineKeyboardMarkup:
    ikm = InlineKeyboardMarkup()
    ikm.row(InlineKeyboardButton("📹 480p", callback_data="q:480"),
            InlineKeyboardButton("📹 720p", callback_data="q:720"))
    ikm.row(InlineKeyboardButton("📹 1080p", callback_data="q:1080"),
            InlineKeyboardButton("📹 4K", callback_data="q:4k"))
    ikm.row(InlineKeyboardButton("❌ Отмена", callback_data="cancel"))
    return ikm


def yes_no_keyboard() -> InlineKeyboardMarkup:
    ikm = InlineKeyboardMarkup()
    ikm.row(InlineKeyboardButton("✅ Да", callback_data="yes"),
            InlineKeyboardButton("❌ Нет", callback_data="no"))
    return ikm


def cancel_keyboard() -> InlineKeyboardMarkup:
    ikm = InlineKeyboardMarkup()
    ikm.row(InlineKeyboardButton("❌ Отмена", callback_data="cancel"))
    return ikm


def playlists_keyboard(playlists: list[dict], with_skip: bool = True) -> InlineKeyboardMarkup:
    ikm = InlineKeyboardMarkup()
    for p in playlists:
        ikm.row(InlineKeyboardButton(f"📁 {p['name']}", callback_data=f"pl:{p['id']}"))
    if with_skip:
        ikm.row(InlineKeyboardButton("⏭ Без плейлиста", callback_data="pl:skip"))
    ikm.row(InlineKeyboardButton("❌ Отмена", callback_data="cancel"))
    return ikm


def subscriptions_keyboard(subscriptions: list[dict]) -> InlineKeyboardMarkup:
    if not subscriptions:
        return None
    ikm = InlineKeyboardMarkup()
    for s in subscriptions:
        status = "🟢" if s["active"] else "🔴"
        title = s.get("channel_title", s["channel_id"])
        q = s.get("quality", "720")
        ikm.row(InlineKeyboardButton(
            f"{status} {title} [{q}p] #{s['id']}",
            callback_data=f"sub:{s['id']}"
        ))
    return ikm


def backfill_period_keyboard() -> InlineKeyboardMarkup:
    """Period choices for backfill (days back from now)."""
    ikm = InlineKeyboardMarkup()
    ikm.row(
        InlineKeyboardButton("📅 7 дней", callback_data="bp:7"),
        InlineKeyboardButton("📅 30 дней", callback_data="bp:30"),
    )
    ikm.row(
        InlineKeyboardButton("📅 90 дней", callback_data="bp:90"),
        InlineKeyboardButton("📅 180 дней", callback_data="bp:180"),
    )
    ikm.row(
        InlineKeyboardButton("📅 1 год", callback_data="bp:365"),
        InlineKeyboardButton("♾ Всё время", callback_data="bp:all"),
    )
    ikm.row(InlineKeyboardButton("❌ Отмена", callback_data="cancel"))
    return ikm


def filters_menu_keyboard() -> InlineKeyboardMarkup:
    """Menu for editing a subscription's filters."""
    ikm = InlineKeyboardMarkup()
    ikm.row(InlineKeyboardButton("✏️ Изменить белый список", callback_data="fw:edit"))
    ikm.row(InlineKeyboardButton("✏️ Изменить чёрный список", callback_data="fb:edit"))
    ikm.row(InlineKeyboardButton("🧹 Очистить оба", callback_data="fc:clear"))
    ikm.row(InlineKeyboardButton("✅ Готово", callback_data="cancel"))
    return ikm


def manage_menu_keyboard() -> InlineKeyboardMarkup:
    """Unified action menu for a single subscription (nested inline menus)."""
    ikm = InlineKeyboardMarkup()
    ikm.row(InlineKeyboardButton("🗑 Отписаться", callback_data="mm:unsub"))
    ikm.row(
        InlineKeyboardButton("🔍 Фильтры", callback_data="mm:filters"),
        InlineKeyboardButton("📦 Архив", callback_data="mm:backfill"),
    )
    ikm.row(
        InlineKeyboardButton("🎚 Качество", callback_data="mm:quality"),
        InlineKeyboardButton("⏱ Время жизни", callback_data="mm:lifetime"),
    )
    ikm.row(InlineKeyboardButton("✅ Готово", callback_data="cancel"))
    return ikm


def main_menu_keyboard() -> ReplyKeyboardMarkup:
    kb = ReplyKeyboardMarkup(resize_keyboard=True, row_width=2)
    kb.add(
        KeyboardButton("🔔 Подписка"),
        KeyboardButton("⬇ Скачать видео"),
    )
    kb.add(
        KeyboardButton("📂 YouTube плейлист"),
        KeyboardButton("📦 Архив за период"),
    )
    kb.add(
        KeyboardButton("📋 Мои подписки"),
        KeyboardButton("🎚 Плейлисты"),
    )
    kb.add(
        KeyboardButton("🔍 Фильтры"),
        KeyboardButton("📊 Статус"),
    )
    kb.add(
        KeyboardButton("⏹ Отменить"),
        KeyboardButton("❓ Помощь"),
    )
    return kb
