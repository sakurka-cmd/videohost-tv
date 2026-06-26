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
)
from bot.uploader import upload_video, list_playlists
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
            "Бот для сохранения видео с YouTube в VideoHost.\n\n"
            "Команды:\n"
            "/subscribe — подписаться на YouTube-канал\n"
            "/dl — скачать видео по ссылке\n"
            "/list — список подписок\n"
            "/playlists — плейлисты VideoHost\n"
            "/status — статус загрузки\n"
            "/help — помощь",
            reply_markup=main_menu_keyboard(),
        )

    # ── /help ────────────────────────────────────────────────
    @bot.message_handler(commands=["help"])
    async def cmd_help(msg: Message):
        await bot.reply_to(
            msg,
            "Бот сохраняет видео с YouTube в VideoHost.\n\n"
            "Подписка на канал:\n"
            "  /subscribe — авто-загрузка новых видео в плейлист\n\n"
            "Разовая загрузка:\n"
            "  /dl https://youtube.com/watch?v=XXXXX\n\n"
            "Качество: 480p, 720p (по умолчанию), 1080p, 4K\n\n"
            "Управление:\n"
            "  /list — подписки\n"
            "  /unsub — отписаться\n"
            "  /playlists — плейлисты\n"
            "  /status — статус загрузки",
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
            if data_str.startswith("q:") and state in (States.SUB_ASK_QUALITY, States.DL_ASK_QUALITY):
                quality = data_str.split(":")[1]
                if quality not in QUALITY_LABELS:
                    await bot.answer_callback_query(call.id, "Неизвестное качество")
                    return
                data["quality"] = quality

                if state == States.SUB_ASK_QUALITY:
                    playlists = await list_playlists()
                    kb = playlists_keyboard(playlists)
                    await bot.edit_message_text(
                        f"Качество: {QUALITY_LABELS[quality]}\nВыберите плейлист:",
                        chat_id=call.message.chat.id,
                        message_id=call.message.message_id,
                        reply_markup=kb,
                    )
                    await db.save_fsm_state(uid, States.SUB_ASK_PLAYLIST, data)
                elif state == States.DL_ASK_QUALITY:
                    playlists = await list_playlists()
                    kb = playlists_keyboard(playlists, with_skip=True)
                    await bot.edit_message_text(
                        f"Качество: {QUALITY_LABELS[quality]}\nВыберите плейлист (или пропустите):",
                        chat_id=call.message.chat.id,
                        message_id=call.message.message_id,
                        reply_markup=kb,
                    )
                    await db.save_fsm_state(uid, States.DL_ASK_PLAYLIST, data)
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
                sub_id = await db.add_subscription(
                    data["channel_id"],
                    data["channel_title"],
                    data["playlist_id"],
                    data.get("quality", DEFAULT_QUALITY),
                )
                await db.clear_fsm_state(uid)
                await bot.edit_message_text(
                    f"Подписка оформлена! (#{sub_id})\nКанал: {data['channel_title']}",
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
                title = info["title"] if info else ch_id
                data["channel_id"] = ch_id
                data["channel_title"] = title
                data["original_url"] = text
                await db.save_fsm_state(uid, States.SUB_ASK_QUALITY, data)
                await bot.reply_to(
                    msg, f"Канал: {title}\nВыберите качество:",
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
        playlist_id = data.get("playlist_id", "")

        try:
            if await db.is_video_processed(yt_id):
                current_status.update({"task": "", "progress": "", "error": ""})
                await bot.send_message(user_id, f"Видео уже загружено ранее: {title}")
                return

            file_path = await download_video(url, quality)
            if not file_path or file_path == "TOO_LARGE":
                err_msg = "Файл слишком большой" if file_path == "TOO_LARGE" else "Ошибка скачивания"
                await bot.send_message(user_id, f"{err_msg}: {title}")
                return

            result = await upload_video(file_path, title, playlist_id or None)
            cleanup_file(file_path)

            vh_id = result.get("id", "") if result else ""
            if vh_id:
                await db.mark_video_processed(yt_id, None, title, quality, vh_id)
                msg_text = f"Загружено: {title}"
                if vh_id:
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