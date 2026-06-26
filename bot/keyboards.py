"""Telegram keyboards."""

from telebot.types import InlineKeyboardMarkup, InlineKeyboardButton, ReplyKeyboardMarkup, KeyboardButton


def quality_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton("480p", callback_data="q:480"),
         InlineKeyboardButton("720p", callback_data="q:720")],
        [InlineKeyboardButton("1080p", callback_data="q:1080"),
         InlineKeyboardButton("4K", callback_data="q:4k")],
        [InlineKeyboardButton("Отмена", callback_data="cancel")],
    ])


def yes_no_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton("✅ Да", callback_data="yes"),
         InlineKeyboardButton("❌ Нет", callback_data="no")],
    ])


def cancel_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton("❌ Отмена", callback_data="cancel")],
    ])


def playlists_keyboard(playlists: list[dict], with_skip: bool = True) -> InlineKeyboardMarkup:
    buttons = []
    for p in playlists:
        buttons.append([InlineKeyboardButton(
            f"📁 {p['name']}", callback_data=f"pl:{p['id']}"
        )])
    if with_skip:
        buttons.append([InlineKeyboardButton("⏭ Без плейлиста", callback_data="pl:skip")])
    buttons.append([InlineKeyboardButton("❌ Отмена", callback_data="cancel")])
    return InlineKeyboardMarkup(inline_keyboard=buttons)


def subscriptions_keyboard(subscriptions: list[dict]) -> InlineKeyboardMarkup:
    buttons = []
    for s in subscriptions:
        status = "🟢" if s["active"] else "🔴"
        title = s.get("channel_title", s["channel_id"])
        q = s.get("quality", "720")
        buttons.append([InlineKeyboardButton(
            f"{status} {title} [{q}p] #{s['id']}",
            callback_data=f"sub:{s['id']}"
        )])
    if not buttons:
        return None
    return InlineKeyboardMarkup(inline_keyboard=buttons)


def main_menu_keyboard() -> ReplyKeyboardMarkup:
    return ReplyKeyboardMarkup(
        [KeyboardButton("/subscribe"), KeyboardButton("/dl")],
        [KeyboardButton("/list"), KeyboardButton("/playlists")],
        [KeyboardButton("/status"), KeyboardButton("/help")],
        resize_keyboard=True,
    )