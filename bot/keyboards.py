"""Telegram keyboards — compatible with pyTelegramBotAPI 4.x."""

from telebot.types import InlineKeyboardMarkup, InlineKeyboardButton, ReplyKeyboardMarkup, KeyboardButton


def quality_keyboard() -> InlineKeyboardMarkup:
    ikm = InlineKeyboardMarkup()
    ikm.row(InlineKeyboardButton("480p", callback_data="q:480"),
            InlineKeyboardButton("720p", callback_data="q:720"))
    ikm.row(InlineKeyboardButton("1080p", callback_data="q:1080"),
            InlineKeyboardButton("4K", callback_data="q:4k"))
    ikm.row(InlineKeyboardButton("Отмена", callback_data="cancel"))
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


def main_menu_keyboard() -> ReplyKeyboardMarkup:
    kb = ReplyKeyboardMarkup(resize_keyboard=True, row_width=2)
    kb.add(KeyboardButton("/subscribe"), KeyboardButton("/dl"))
    kb.add(KeyboardButton("/list"), KeyboardButton("/playlists"))
    kb.add(KeyboardButton("/status"), KeyboardButton("/help"))
    return kb