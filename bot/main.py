"""Entry point for yt2tg Telegram bot."""

import asyncio
import logging

from telebot.async_telebot import AsyncTeleBot
from bot.config import TG_BOT_TOKEN, ADMIN_IDS
from bot import database as db
from bot.handlers import register_handlers
from bot.scheduler import scheduler_loop
from bot.version_checker import version_checker_loop

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("yt2tg")

bot = AsyncTeleBot(TG_BOT_TOKEN)
register_handlers(bot)


async def main():
    await db.init_db()
    logger.info("Bot started (admins: %s)", ADMIN_IDS)

    # Start scheduler in background
    admin_id = ADMIN_IDS[0] if ADMIN_IDS else 0
    asyncio.create_task(scheduler_loop(bot, admin_id))

    # Start version checker in background (notifies admins of new commits/APK)
    asyncio.create_task(version_checker_loop(bot))

    # Start polling
    await bot.infinity_polling()


if __name__ == "__main__":
    asyncio.run(main())