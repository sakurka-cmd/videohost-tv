"""Telegram bot handlers — commands, callbacks, FSM flows."""

import asyncio
import logging

from telebot.async_telebot import AsyncTeleBot
from telebot.types import Message, CallbackQuery

from bot import database as db
from bot.states import States
from bot.downloader import (
    download_video, extract_video_id, extract_channel_id,
    get_video_info, get_channel_info, cleanup_file, current_status,
    extract_playlist_id, get_youtube_playlist_info,
)
from bot.uploader import (
    upload_video, list_playlists, find_or_create_playlist, sort_playlist, video_exists,
)
from bot.keyboards import (
    quality_keyboard, yes_no_keyboard, cancel_keyboard, playlists_keyboard,
    subscriptions_keyboard, main_menu_keyboard,
)
from bot.config import ADMIN_IDS, QUALITY_LABELS, DEFAULT_QUALITY

logger = logging.getLogger(__name__)


def is_admin(user_id: int) -> bool:
    if not ADMIN_IDS:
        return True
    return user_id in ADMIN_IDS


def register_handlers(bot: AsyncTeleBot):

    # ── /start ──────────────────────────────────────────────
    @bot.message_handler(commands=["start"])
    async def cmd_start(msg: Message):
        await bot.reply_to(
            msg,
            "🎬 UTube Bot — сохранение видео с YouTube в VideoHost\n\n"
            "Выберите действие в меню ниже 👇",
            reply_markup=main_menu_keyboard(),
        )

    # ── Text button aliases (from ReplyKeyboard) ─────────────
    BUTTON_ALIASES = {
        "🔔 Подписка": "/subscribe",
        "⬇ Скачать видео": "/dl",
        "📂 YouTube плейлист": "/dl_playlist",
        "📦 Архив за период": "/backfill",
        "📋 Мои подписки": "/list",
        "🎚 Плейлисты": "/playlists",
        "📊 Статус": "/status",
        "⏹ Отменить": "/cancel",
        "❓ Помощь": "/help",
    }

    @bot.message_handler(func=lambda m: m.text in BUTTON_ALIASES)
    async def handle_menu_button(msg: Message):
        """Convert reply keyboard button text to the corresponding command."""
        msg.text = BUTTON_ALIASES[msg.text]
        cmd = BUTTON_ALIASES.get(msg.text, "")
        if cmd == "/subscribe":
            await cmd_subscribe(msg)
        elif cmd == "/dl":
            await cmd_dl(msg)
        elif cmd == "/dl_playlist":
            await cmd_dl_playlist(msg)
        elif cmd == "/backfill":
            await cmd_backfill(msg)
        elif cmd == "/list":
            await cmd_list(msg)
        elif cmd == "/playlists":
            await cmd_playlists(msg)
        elif cmd == "/status":
            await cmd_status(msg)
        elif cmd == "/cancel":
            await cmd_cancel(msg)
        elif cmd == "/help":
            await cmd_help(msg)

    # ── /help ────────────────────────────────────────────────
    @bot.message_handler(commands=["help"])
    async def cmd_help(msg: Message):
        await bot.reply_to(
            msg,
            "🎬 UTube Bot — сохранение видео с YouTube в VideoHost\n\n"
            "🔔 Подписка — авто-загрузка новых видео с канала\n"
            "⬇ Скачать видео — разовая загрузка по ссылке\n"
            "📂 YouTube плейлист — скачать весь плейлист\n"
            "📦 Архив за период — скачать старые видео (7/30/90/180/365 дней)\n"
            "📋 Мои подписки — список подписок\n"
            "🎚 Плейлисты — плейлисты VideoHost\n"
            "📊 Статус — статус текущей загрузки\n"
            "⏹ Отменить — остановить текущую загрузку\n\n"
            "Качество: 480p, 720p (по умолчанию), 1080p, 4K\n\n"
            "Команды тоже работают:\n"
            "  /subscribe, /dl, /dl_playlist, /backfill, /list, /playlists,\n"
            "  /status, /cancel, /unsub, /quality",
            reply_markup=main_menu_keyboard(),
        )

    # ── /playlists ──────────────────────────────────────────
    @bot.message_handler(commands=["playlists"])
    async def cmd_playlists(msg: Message):
        try:
            playlists = await list_playlists()
            if not playlists:
                await bot.reply_to(msg, "В VideoHost нет плейлистов.\nСоздайте через веб-интерфейс.")
                return
            lines = [f"Плейлисты VideoHost ({len(playlists)}):"]
            for p in playlists:
                lines.append(f"  {p['name']} (id: {p['id']})")
            await bot.reply_to(msg, "\n".join(lines))
        except Exception as e:
            logger.exception("cmd_playlists error")
            await bot.reply_to(msg, f"Ошибка получения плейлистов: {e}")

    # ── /status ─────────────────────────────────────────────
    @bot.message_handler(commands=["status"])
    async def cmd_status(msg: Message):
        s = current_status
        if s["task"]:
            text = f"Текущая задача: {s['task']}\n"
            if s["title"]:
                text += f"{s['title']}\n"
            if s["url"]:
                text += f"{s['url']}\n"
            if s["progress"]:
                text += f"{s['progress']}\n"
            if s["error"]:
                text += f"Ошибка: {s['error']}\n"
        else:
            text = "Нет активных задач."
        await bot.reply_to(msg, text)

    # ═══════════════════════════════════════════════════════
    #  /subscribe — FSM: URL -> quality -> playlist -> confirm
    # ═══════════════════════════════════════════════════════
    @bot.message_handler(commands=["subscribe"])
    async def cmd_subscribe(msg: Message):
        if not is_admin(msg.from_user.id):
            await bot.reply_to(msg, "У вас нет доступа.")
            return
        await db.save_fsm_state(msg.from_user.id, States.SUB_ASK_URL, {})
        await bot.reply_to(
            msg,
            "Отправьте ссылку на YouTube-канал:\n\n"
            "Примеры:\n"
            "  https://www.youtube.com/@channelName\n"
            "  https://www.youtube.com/channel/UC...",
            reply_markup=cancel_keyboard(),
        )

    # ═══════════════════════════════════════════════════════
    #  /dl — one-off download
    # ═══════════════════════════════════════════════════════
    @bot.message_handler(commands=["dl"])
    async def cmd_dl(msg: Message):
        if not is_admin(msg.from_user.id):
            await bot.reply_to(msg, "У вас нет доступа.")
            return
        await db.save_fsm_state(msg.from_user.id, States.DL_ASK_URL, {})
        await bot.reply_to(
            msg,
            "Отправьте ссылку на YouTube-видео:",
            reply_markup=cancel_keyboard(),
        )

    # ═══════════════════════════════════════════════════════
    #  /dl_playlist — download YouTube playlist
    # ═══════════════════════════════════════════════════════
    @bot.message_handler(commands=["dl_playlist"])
    async def cmd_dl_playlist(msg: Message):
        if not is_admin(msg.from_user.id):
            await bot.reply_to(msg, "У вас нет доступа.")
            return
        await db.save_fsm_state(msg.from_user.id, States.DLPL_ASK_URL, {})
        await bot.reply_to(
            msg,
            "📂 Отправьте ссылку на YouTube-плейлист:\n\n"
            "Пример:\n"
            "  https://www.youtube.com/playlist?list=PLxxxxxxx\n"
            "  https://www.youtube.com/watch?v=XXX&list=PLxxxxxxx",
            reply_markup=cancel_keyboard(),
        )

    # ═══════════════════════════════════════════════════════
    #  /list — subscriptions
    # ═══════════════════════════════════════════════════════
    @bot.message_handler(commands=["list"])
    async def cmd_list(msg: Message):
        if not is_admin(msg.from_user.id):
            await bot.reply_to(msg, "У вас нет доступа.")
            return
        subs = await db.list_subscriptions()
        if not subs:
            await bot.reply_to(msg, "Нет подписок.\nДобавьте: /subscribe")
            return
        lines = [f"Подписки ({len(subs)}):"]
        for s in subs:
            status = "+" if s["active"] else "-"
            q = s.get("quality", "720")
            lines.append(
                f"  {status} #{s['id']} {s.get('channel_title', s['channel_id'])} [{q}p]"
            )
        lines.append("\n/unsub — удалить подписку")
        await bot.reply_to(msg, "\n".join(lines))

    # ═══════════════════════════════════════════════════════
    #  /unsub — unsubscribe
    # ═══════════════════════════════════════════════════════
    @bot.message_handler(commands=["unsub"])
    async def cmd_unsub(msg: Message):
        if not is_admin(msg.from_user.id):
            await bot.reply_to(msg, "У вас нет доступа.")
            return
        subs = await db.list_subscriptions()
        if not subs:
            await bot.reply_to(msg, "Нет подписок.")
            return
        kb = subscriptions_keyboard(subs)
        if not kb:
            await bot.reply_to(msg, "Нет подписок.")
            return
        await db.save_fsm_state(msg.from_user.id, States.UNSUB_SELECT, {})
        await bot.reply_to(msg, "Выберите подписку для удаления:", reply_markup=kb)

    # ═══════════════════════════════════════════════════════
    #  /quality — change subscription quality
    # ═══════════════════════════════════════════════════════
    @bot.message_handler(commands=["quality"])
    async def cmd_quality(msg: Message):
        if not is_admin(msg.from_user.id):
            await bot.reply_to(msg, "У вас нет доступа.")
            return
        subs = await db.list_subscriptions()
        if not subs:
            await bot.reply_to(msg, "Нет подписок.")
            return
        kb = subscriptions_keyboard(subs)
        if not kb:
            await bot.reply_to(msg, "Нет подписок.")
            return
        await db.save_fsm_state(msg.from_user.id, States.QUALITY_SELECT, {})
        await bot.reply_to(msg, "Выберите подписку:", reply_markup=kb)

    # ── Callback query handler ──────────────────────────────
    @bot.callback_query_handler(func=lambda call: True)
    async def handle_callback(call: CallbackQuery):
        try:
            uid = call.from_user.id
            data_str = call.data
            state, data = await db.get_fsm_state(uid)
            logger.info("Callback from %d, state=%s, data=%s", uid, state, data_str)

            # ── Cancel ──
            if data_str == "cancel":
                await db.clear_fsm_state(uid)
                await bot.edit_message_reply_markup(
                    call.message.chat.id, call.message.message_id, reply_markup=None
                )
                await bot.answer_callback_query(call.id, "Отменено")
                return

            # ── Quality selection (shared for subscribe and dl) ──
            # NOTE: only handle SUB_ASK_QUALITY / DL_ASK_QUALITY here.
            # QUALITY_VALUE (changing existing sub quality) is handled below.
            if data_str.startswith("q:") and state in (States.SUB_ASK_QUALITY, States.DL_ASK_QUALITY, States.DLPL_ASK_QUALITY):
                quality = data_str.split(":")[1]
                if quality not in QUALITY_LABELS:
                    await bot.answer_callback_query(call.id, "Неизвестное качество")
                    return
                data["quality"] = quality

                if state == States.SUB_ASK_QUALITY:
                    # Skip playlist selection — playlist will be auto-created on confirm
                    channel_handle = data.get("channel_handle") or data.get("channel_id", "Канал")
                    channel_title = data.get("channel_title", channel_handle)
                    await bot.edit_message_text(
                        f"Качество: {QUALITY_LABELS[quality]}\n\n"
                        f"Канал: {channel_title}\n"
                        f"Handle: @{channel_handle}\n\n"
                        f"Плейлист «{channel_handle}» будет создан автоматически.\n"
                        f"Подтвердите подписку:",
                        chat_id=call.message.chat.id,
                        message_id=call.message.message_id,
                        reply_markup=yes_no_keyboard(),
                    )
                    await db.save_fsm_state(uid, States.SUB_CONFIRM, data)
                elif state == States.DL_ASK_QUALITY:
                    title = data.get("title", "Видео")
                    await bot.edit_message_text(
                        f"Качество: {QUALITY_LABELS[quality]}\n\n"
                        f"Начинаю загрузку:\n{title}\n\n"
                        f"Плейлист будет создан по имени канала автоматически.",
                        chat_id=call.message.chat.id,
                        message_id=call.message.message_id,
                    )
                    await db.clear_fsm_state(uid)
                    asyncio.create_task(_process_oneoff(uid, data))
                elif state == States.DLPL_ASK_QUALITY:
                    pl_title = data.get("playlist_title", "YouTube Playlist")
                    await bot.edit_message_text(
                        f"Качество: {QUALITY_LABELS[quality]}\n"
                        f"Плейлист: {pl_title}\n"
                        f"Видео: {data.get('video_count', '?')}\n\n"
                        f"Выберите период загрузки:",
                        chat_id=call.message.chat.id,
                        message_id=call.message.message_id,
                        reply_markup=backfill_period_keyboard(),
                    )
                    await db.save_fsm_state(uid, States.DLPL_ASK_PERIOD, data)
                return

            # ── Playlist selection ──
            if data_str.startswith("pl:"):
                playlist_id = data_str.split(":")[1]

                if state == States.SUB_ASK_PLAYLIST:
                    if playlist_id == "skip":
                        await bot.answer_callback_query(call.id, "Выберите плейлист!")
                        return
                    data["playlist_id"] = playlist_id
                    await bot.edit_message_text(
                        f"Подтвердите подписку:\n\n"
                        f"Канал: {data.get('channel_title', '?')}\n"
                        f"Плейлист: {playlist_id}\n"
                        f"Качество: {QUALITY_LABELS.get(data.get('quality', '720'), '?')}\n\n"
                        f"Новые видео будут автоматически загружаться.",
                        chat_id=call.message.chat.id,
                        message_id=call.message.message_id,
                        reply_markup=yes_no_keyboard(),
                    )
                    await db.save_fsm_state(uid, States.SUB_CONFIRM, data)

                elif state == States.DL_ASK_PLAYLIST:
                    data["playlist_id"] = "" if playlist_id == "skip" else playlist_id
                    await db.clear_fsm_state(uid)
                    await bot.edit_message_reply_markup(
                        call.message.chat.id, call.message.message_id, reply_markup=None
                    )
                    pl_info = (
                        f"\nПлейлист: {playlist_id}"
                        if playlist_id != "skip"
                        else "\nБез плейлиста"
                    )
                    await bot.send_message(
                        call.message.chat.id,
                        f"Начинаю загрузку:\n"
                        f"{data.get('title', '?')}\n"
                        f"Качество: {QUALITY_LABELS.get(data.get('quality', '720'), '?')}{pl_info}",
                    )
                    asyncio.create_task(_process_oneoff(uid, data))
                return

            # ── Subscribe confirmation ──
            if data_str == "yes" and state == States.SUB_CONFIRM:
                # Use the channel handle (channel_handle, extracted from yt-dlp's
                # uploader_id, which is the canonical handle YouTube uses) as the
                # playlist name — this matches the /dl flow exactly.
                channel_handle = data.get("channel_handle") or data.get("channel_id") or data.get("channel_title") or "Канал"
                channel_title = data.get("channel_title", channel_handle)
                yt_channel_id = data.get("youtube_channel_id", "")
                # Find or create playlist named after the channel handle
                pl = await find_or_create_playlist(channel_handle)
                if not pl or not pl.get("id"):
                    await bot.answer_callback_query(
                        call.id, f"Не удалось создать плейлист «{channel_handle}»"
                    )
                    return
                playlist_id = pl["id"]
                sub_id = await db.add_subscription(
                    channel_handle,                # channel_id (handle, used by RSS)
                    channel_title,                  # display name
                    playlist_id,
                    data.get("quality", DEFAULT_QUALITY),
                    youtube_channel_id=yt_channel_id,  # UCxxxxx (used by /dl matching)
                )
                await db.clear_fsm_state(uid)
                msg_lines = [
                    f"Подписка оформлена! (#{sub_id})",
                    f"Канал: {channel_title}",
                    f"Handle: @{channel_handle}",
                    f"Плейлист: «{channel_handle}» (id: {playlist_id})",
                ]
                if yt_channel_id:
                    msg_lines.append(f"YouTube ID: {yt_channel_id}")
                await bot.edit_message_text(
                    "\n".join(msg_lines),
                    chat_id=call.message.chat.id,
                    message_id=call.message.message_id,
                )
                await bot.answer_callback_query(call.id, "Готово!")
                return

            if data_str == "no" and state == States.SUB_CONFIRM:
                await db.clear_fsm_state(uid)
                await bot.edit_message_text(
                    "Отменено.",
                    chat_id=call.message.chat.id,
                    message_id=call.message.message_id,
                )
                return

            # ── Unsubscribe selection ──
            if data_str.startswith("sub:") and state == States.UNSUB_SELECT:
                sub_id = int(data_str.split(":")[1])
                sub = await db.get_subscription(sub_id)
                if sub:
                    await db.delete_subscription(sub_id)
                    await bot.edit_message_text(
                        f"Удалена подписка: {sub.get('channel_title', sub['channel_id'])}",
                        chat_id=call.message.chat.id,
                        message_id=call.message.message_id,
                    )
                await bot.answer_callback_query(call.id, "Удалено")
                return

            # ── Quality change selection ──
            if data_str.startswith("sub:") and state == States.QUALITY_SELECT:
                sub_id = int(data_str.split(":")[1])
                data["sub_id"] = sub_id
                sub = await db.get_subscription(sub_id)
                cur_q = sub.get("quality", "720") if sub else "720"
                await bot.edit_message_text(
                    f"Текущее качество: {QUALITY_LABELS.get(cur_q, cur_q)}\nВыберите новое:",
                    chat_id=call.message.chat.id,
                    message_id=call.message.message_id,
                    reply_markup=quality_keyboard(),
                )
                await db.save_fsm_state(uid, States.QUALITY_VALUE, data)
                return

            if data_str.startswith("q:") and state == States.QUALITY_VALUE:
                quality = data_str.split(":")[1]
                if quality not in QUALITY_LABELS:
                    await bot.answer_callback_query(call.id, "?")
                    return
                sub_id = data.get("sub_id")
                if sub_id:
                    await db.update_subscription_quality(sub_id, quality)
                    await db.clear_fsm_state(uid)
                    await bot.edit_message_text(
                        f"Качество изменено на {QUALITY_LABELS[quality]}",
                        chat_id=call.message.chat.id,
                        message_id=call.message.message_id,
                    )
                await bot.answer_callback_query(call.id, "Готово!")
                return

            # ═══════════════════════════════════════════════════════
            #  Backfill: period selection (bp:) for BACKFILL_ASK_PERIOD
            # ═══════════════════════════════════════════════════════
            if data_str.startswith("bp:") and state == States.BACKFILL_ASK_PERIOD:
                period = data_str.split(":")[1]
                sub_id = data.get("sub_id")
                if not sub_id:
                    await bot.answer_callback_query(call.id, "Сессия устарела")
                    return
                period_name = {
                    "7": "7 дней", "30": "30 дней", "90": "90 дней",
                    "180": "180 дней", "365": "1 год", "all": "всё время",
                }.get(period, period)
                await db.clear_fsm_state(uid)
                await bot.edit_message_text(
                    f"🚀 Запускаю загрузку архива за {period_name}...\n"
                    f"Это может занять несколько минут. Я сообщу о результате.\n"
                    f"Чтобы отменить — /cancel",
                    chat_id=call.message.chat.id,
                    message_id=call.message.message_id,
                )
                asyncio.create_task(_process_backfill(uid, sub_id, period))
                return

            # ═══════════════════════════════════════════════════════
            #  YouTube playlist download: period selection (bp:) for DLPL_ASK_PERIOD
            # ═══════════════════════════════════════════════════════
            if data_str.startswith("bp:") and state == States.DLPL_ASK_PERIOD:
                period = data_str.split(":")[1]
                period_name = {
                    "7": "7 дней", "30": "30 дней", "90": "90 дней",
                    "180": "180 дней", "365": "1 год", "all": "всё время",
                }.get(period, period)
                await db.clear_fsm_state(uid)
                await bot.edit_message_text(
                    f"🚀 Запускаю загрузку плейлиста за {period_name}...\n"
                    f"Это может занять несколько минут. Я сообщу о результате.\n"
                    f"Чтобы отменить — /cancel",
                    chat_id=call.message.chat.id,
                    message_id=call.message.message_id,
                )
                asyncio.create_task(_process_dl_playlist(uid, data, period))
                return

            await bot.answer_callback_query(call.id, "")

        except Exception as e:
            logger.exception("handle_callback error: %s", e)
            try:
                await bot.answer_callback_query(call.id, f"Ошибка: {e}")
            except Exception:
                pass

    # ═══════════════════════════════════════════════════════
    #  Text handler (MUST be last — catch-all for FSM states)
    # ═══════════════════════════════════════════════════════
    @bot.message_handler(func=lambda m: True, content_types=["text"])
    async def handle_text(msg: Message):
        try:
            state, data = await db.get_fsm_state(msg.from_user.id)
            text = msg.text.strip()
            uid = msg.from_user.id
            logger.info("Text from %d, state=%s, text=%s", uid, state, text[:100])

            if state == States.SUB_ASK_URL:
                ch_id = extract_channel_id(text)
                if not ch_id:
                    await bot.reply_to(msg, "Не удалось распознать канал. Попробуйте другую ссылку.")
                    return
                await bot.reply_to(msg, "Получаю информацию о канале...")
                try:
                    info = await get_channel_info(text)
                except Exception as e:
                    logger.error("get_channel_info error: %s", e)
                    info = None
                # info from yt-dlp has: channel_id (UCxxxxx), channel_handle (without @),
                # title (display name). Fallback to extract_channel_id if yt-dlp failed.
                yt_channel_id = (info.get("channel_id") if info else "") or ""
                channel_handle = (info.get("channel_handle") if info else "") or ch_id
                title = (info.get("title") if info else "") or channel_handle
                data["channel_id"] = channel_handle  # used as subscription channel_id (handle)
                data["channel_handle"] = channel_handle  # used as playlist name
                data["channel_title"] = title  # display name (for UI messages)
                data["youtube_channel_id"] = yt_channel_id  # UCxxxxx (for /dl matching)
                data["original_url"] = text
                await db.save_fsm_state(uid, States.SUB_ASK_QUALITY, data)
                await bot.reply_to(
                    msg, f"Канал: {title}\nHandle: @{channel_handle}\nВыберите качество:",
                    reply_markup=quality_keyboard(),
                )

            elif state == States.DL_ASK_URL:
                yt_id = extract_video_id(text)
                if not yt_id:
                    await bot.reply_to(msg, "Не удалось распознать ссылку на видео.")
                    return
                await bot.reply_to(msg, "Получаю информацию о видео...")
                try:
                    info = await get_video_info(text)
                except Exception as e:
                    logger.error("get_video_info error: %s", e)
                    info = None
                title = info["title"] if info else yt_id
                data["url"] = text
                data["youtube_id"] = yt_id
                data["title"] = title
                await db.save_fsm_state(uid, States.DL_ASK_QUALITY, data)
                await bot.reply_to(
                    msg, f"Видео: {title}\nВыберите качество:",
                    reply_markup=quality_keyboard(),
                )

            elif state == States.DLPL_ASK_URL:
                pl_id = extract_playlist_id(text)
                if not pl_id:
                    await bot.reply_to(msg, "Не удалось распознать ссылку на плейлист. Ищите параметр ?list=PL...")
                    return
                await bot.reply_to(msg, "📂 Получаю информацию о плейлисте...")
                try:
                    pl_info = await get_youtube_playlist_info(text)
                except Exception as e:
                    logger.error("get_youtube_playlist_info error: %s", e)
                    pl_info = None
                if not pl_info or not pl_info.get("videos"):
                    await bot.reply_to(msg, "Не удалось получить список видео из плейлиста.")
                    return
                pl_title = pl_info["title"]
                data["playlist_url"] = text
                data["playlist_title"] = pl_title
                data["video_count"] = len(pl_info["videos"])
                await db.save_fsm_state(uid, States.DLPL_ASK_QUALITY, data)
                await bot.reply_to(
                    msg,
                    f"📂 Плейлист: {pl_title}\n"
                    f"Видео в плейлисте: {len(pl_info['videos'])}\n\n"
                    f"Выберите качество:",
                    reply_markup=quality_keyboard(),
                )

            elif state == States.PLAYLIST_ASK_NAME:
                data["new_playlist_name"] = text
                await db.save_fsm_state(uid, state, data)
                await bot.reply_to(
                    msg,
                    f"Плейлист \"{text}\" будет создан через веб-интерфейс.\n"
                    f"Пожалуйста, создайте плейлист \"{text}\" в VideoHost и вернитесь.",
                    reply_markup=cancel_keyboard(),
                )

        except Exception as e:
            logger.exception("handle_text unhandled error: %s", e)
            try:
                await bot.reply_to(msg, f"Ошибка: {e}")
            except Exception:
                pass

    # ── Background task: one-off download ────────────────────────
    async def _process_oneoff(user_id: int, data: dict):
        url = data["url"]
        yt_id = data.get("youtube_id", "")
        title = data.get("title", yt_id)
        quality = data.get("quality", DEFAULT_QUALITY)

        try:
            # Check if already processed — but verify the video still exists on VideoHost.
            # If it was deleted from VideoHost, drop the cached record so we can re-upload.
            existing = await db.get_processed_video(yt_id)
            if existing:
                vh_id_old = existing.get("videohost_id", "") or ""
                if vh_id_old and not await video_exists(vh_id_old):
                    logger.info("Video %s (%s) was deleted on VideoHost — re-uploading",
                                yt_id, vh_id_old)
                    await db.unmark_video_processed(yt_id)
                    existing = None
            if existing:
                current_status.update({"task": "", "progress": "", "error": ""})
                await bot.send_message(user_id, f"Видео уже загружено ранее: {title}")
                return

            # Get channel handle + upload_date + thumbnail + UC channel_id from video info
            info = await get_video_info(url)
            yt_channel_id = (info.get("channel_id") if info else "") or ""
            # channel_handle = canonical handle from uploader_id (without @, may
            # have a numeric suffix like "russiancrashchannel6171" if YouTube
            # added one when the channel was created). This matches what
            # /subscribe would have stored.
            from bot.downloader import clean_handle
            channel_handle = clean_handle(info.get("uploader_id") if info else "") \
                or clean_handle(info.get("uploader_url") if info else "") \
                or (info.get("uploader") if info else "") \
                or data.get("channel_id") or data.get("channel_title") or "YouTube"
            published_at = (info.get("upload_date") if info else "") or ""
            thumbnail_url = (info.get("thumbnail") if info else "") or ""
            # Re-fetch title from info (more accurate than what user passed in /dl url)
            if info and info.get("title"):
                title = info["title"]
            current_status.update({"task": "download", "url": url, "title": title,
                                   "progress": "0%", "error": ""})

            # Check if user is already subscribed to this channel (by UCxxxxx).
            # If yes, reuse the existing playlist instead of creating a new one.
            playlist_id = ""
            reused_sub = False
            if yt_channel_id:
                sub = await db.find_subscription_by_youtube_channel_id(yt_channel_id)
                if sub:
                    playlist_id = sub.get("playlist_id", "") or ""
                    if playlist_id:
                        reused_sub = True
                        logger.info("Video %s belongs to subscribed channel UC=%s → reusing playlist %s",
                                    yt_id, yt_channel_id, playlist_id)
                        await bot.send_message(
                            user_id,
                            f"📡 Канал: @{channel_handle} (подписка #{sub['id']})\n"
                            f"Использую существующий плейлист...",
                        )

            if not playlist_id:
                await bot.send_message(
                    user_id,
                    f"📡 Канал: @{channel_handle}\nСоздаю плейлист...",
                )
                pl = await find_or_create_playlist(channel_handle)
                playlist_id = pl.get("id", "") if pl else ""

            file_path = await download_video(url, quality)
            if not file_path or file_path == "TOO_LARGE":
                err_msg = "Файл слишком большой" if file_path == "TOO_LARGE" else "Ошибка скачивания"
                await bot.send_message(user_id, f"{err_msg}: {title}")
                return

            result = await upload_video(
                file_path, title, playlist_id or None,
                published_at=published_at,
                thumbnail_url=thumbnail_url,
                youtube_id=yt_id,
            )
            cleanup_file(file_path)

            vh_id = result.get("id", "") if result else ""
            if vh_id:
                # Re-sort the playlist chronologically by publishedAt
                if playlist_id:
                    await sort_playlist(playlist_id)
                await db.mark_video_processed(yt_id, None, title, quality, vh_id)
                msg_text = f"✅ Загружено: {title}\nКанал: @{channel_handle}"
                if playlist_id:
                    if reused_sub:
                        msg_text += f"\nПлейлист: существующий (id: {playlist_id})"
                    else:
                        msg_text += f"\nПлейлист: «{channel_handle}» (id: {playlist_id})"
                msg_text += f"\nID: {vh_id}"
                await bot.send_message(user_id, msg_text)
            else:
                await bot.send_message(user_id, f"Ошибка загрузки на сервер: {title}")
        except Exception as e:
            logger.exception("_process_oneoff error: %s", e)
            try:
                await bot.send_message(user_id, f"Ошибка при загрузке: {e}")
            except Exception:
                pass
        finally:
            current_status.update({
                "task": "", "progress": "", "error": "", "url": "", "title": ""
            })
    # ── Background task: download YouTube playlist ────────────
    async def _process_dl_playlist(user_id: int, data: dict, period: str):
        """Download all videos from a YouTube playlist into a VideoHost playlist
        named 'ytpls_<playlist_title>'.
        """
        from datetime import datetime, timedelta, timezone

        backfill_tasks[user_id] = {"sub_id": 0, "period": period, "cancel": False}

        try:
            playlist_url = data["playlist_url"]
            pl_title = data.get("playlist_title", "YouTube Playlist")
            quality = data.get("quality", DEFAULT_QUALITY)
            vh_playlist_name = f"ytpls_{pl_title}"

            # Create VideoHost playlist
            pl = await find_or_create_playlist(vh_playlist_name)
            if not pl or not pl.get("id"):
                await bot.send_message(user_id, f"❌ Не удалось создать плейлист «{vh_playlist_name}»")
                return
            playlist_id = pl["id"]

            await bot.send_message(
                user_id,
                f"📡 Получаю список видео из плейлиста «{pl_title}»...\n"
                f"Плейлист на VideoHost: «{vh_playlist_name}»\n"
                f"Чтобы отменить — /cancel",
            )

            # Get all videos from YouTube playlist
            pl_info = await get_youtube_playlist_info(playlist_url)
            if not pl_info or not pl_info.get("videos"):
                await bot.send_message(user_id, "❌ Не удалось получить видео из плейлиста.")
                return

            all_videos = pl_info["videos"]

            # Filter by period
            if period == "all":
                cutoff = None
                period_label = "всё время"
            else:
                days = int(period)
                cutoff = datetime.now(tz=timezone.utc) - timedelta(days=days)
                period_label = f"{days} дн."

            in_period = []
            skipped_no_date = 0
            too_old = 0
            for v in all_videos:
                upload_date_str = v.get("upload_date", "")
                if not upload_date_str or len(upload_date_str) != 8:
                    # For playlists, include videos without date (unlike backfill)
                    in_period.append(v)
                    continue
                try:
                    pub_dt = datetime.strptime(upload_date_str, "%Y%m%d").replace(tzinfo=timezone.utc)
                    if cutoff and pub_dt < cutoff:
                        too_old += 1
                        continue
                    v["_pub_dt"] = pub_dt
                    in_period.append(v)
                except Exception:
                    in_period.append(v)

            # Filter out already processed
            to_download = []
            already_done = 0
            for v in in_period:
                existing = await db.get_processed_video(v["id"])
                if existing:
                    vh_id_old = existing.get("videohost_id", "") or ""
                    if vh_id_old and not await video_exists(vh_id_old):
                        await db.unmark_video_processed(v["id"])
                        to_download.append(v)
                    else:
                        already_done += 1
                else:
                    to_download.append(v)

            to_download.sort(key=lambda x: x.get("_pub_dt") or datetime.min.replace(tzinfo=timezone.utc))

            summary = (
                f"📊 Плейлист: {pl_title}\n"
                f"Всего видео: {len(all_videos)}\n"
                f"В периоде «{period_label}»: {len(in_period)}\n"
            )
            if too_old > 0:
                summary += f"Старше периода: {too_old}\n"
            summary += f"Уже загружено: {already_done}\n"
            summary += f"К загрузке: {len(to_download)}"
            await bot.send_message(user_id, summary)

            if not to_download:
                await bot.send_message(user_id, "✅ Нечего загружать — все видео уже есть.")
                return

            uploaded_count = 0
            failed_count = 0
            for i, v in enumerate(to_download, 1):
                if backfill_tasks.get(user_id, {}).get("cancel"):
                    await bot.send_message(
                        user_id,
                        f"⏹ Загрузка отменена.\nЗагружено: {uploaded_count}, ошибок: {failed_count}",
                    )
                    break

                yt_id = v["id"]
                title = v.get("title", yt_id)
                url = v.get("url") or f"https://www.youtube.com/watch?v={yt_id}"
                pub_dt = v.get("_pub_dt")
                published_at = pub_dt.strftime("%Y%m%d") if pub_dt else ""

                try:
                    await bot.send_message(user_id, f"[{i}/{len(to_download)}] ⬇ {title}")

                    file_path = await download_video(url, quality)
                    if not file_path or file_path == "TOO_LARGE":
                        if file_path == "TOO_LARGE":
                            await db.mark_video_processed(yt_id, None, title, quality, "")
                        failed_count += 1
                        continue

                    yt_thumb = f"https://img.youtube.com/vi/{yt_id}/hqdefault.jpg"
                    result = await upload_video(
                        file_path, title, playlist_id,
                        published_at=published_at,
                        thumbnail_url=yt_thumb,
                        youtube_id=yt_id,
                    )
                    cleanup_file(file_path)

                    if result:
                        vh_id = result.get("id", "")
                        await db.mark_video_processed(yt_id, None, title, quality, vh_id)
                        uploaded_count += 1
                        await sort_playlist(playlist_id)
                    else:
                        failed_count += 1
                except Exception as e:
                    logger.exception("dl_playlist error on %s: %s", yt_id, e)
                    failed_count += 1

            await bot.send_message(
                user_id,
                f"🏁 Загрузка плейлиста завершена!\n"
                f"Плейлист: «{vh_playlist_name}»\n"
                f"Загружено: {uploaded_count}\n"
                f"Ошибок: {failed_count}\n"
                f"Уже было: {already_done}",
            )

        except Exception as e:
            logger.exception("_process_dl_playlist error: %s", e)
            try:
                await bot.send_message(user_id, f"Ошибка: {e}")
            except Exception:
                pass
        finally:
            backfill_tasks.pop(user_id, None)
            current_status.update({"task": "", "progress": "", "error": "", "url": "", "title": ""})
