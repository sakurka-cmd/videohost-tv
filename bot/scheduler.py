"""YouTube channel RSS checker for subscriptions."""

import asyncio
import logging
from datetime import datetime, timezone

import aiohttp
import feedparser

from bot import database as db
from bot.downloader import extract_video_id, download_video, cleanup_file, current_status
from bot.uploader import upload_video, sort_playlist, video_exists
from bot.config import CHECK_INTERVAL
from bot.filters import should_download

logger = logging.getLogger(__name__)

# YouTube RSS occasionally returns an empty feed (transient cache refresh
# or brief outage). Retry a few times with backoff before giving up —
# otherwise we silently miss new videos until the next hourly check,
# which can age them past the 7-day skip window and lose them forever.
# NOTE: retry only applies to HTTP 200 with empty body (transient).
# Permanent errors (404 = channel deleted, 500 = YouTube server error)
# return immediately without retry — retrying those is wasteful and
# risks YouTube IP-blocking the VPS for spamming dead channels.
FEED_RETRY_ATTEMPTS = 3
FEED_RETRY_BACKOFF_SEC = 10


async def _fetch_feed_body(session: aiohttp.ClientSession, url: str) -> tuple[int, str | None]:
    """Fetch URL body via aiohttp. Returns (status_code, body_text or None)."""
    async with session.get(url, timeout=aiohttp.ClientTimeout(total=15)) as resp:
        body = await resp.text() if resp.status == 200 else None
        return resp.status, body


async def get_channel_feed(channel_id: str) -> feedparser.FeedParserDict | None:
    if channel_id.startswith("@") or channel_id.startswith("UC"):
        feed_url = f"https://www.youtube.com/feeds/videos.xml?channel_id={channel_id}"
    else:
        feed_url = f"https://www.youtube.com/feeds/videos.xml?channel_id=@{channel_id}"

    async with aiohttp.ClientSession() as session:
        for attempt in range(1, FEED_RETRY_ATTEMPTS + 1):
            try:
                status, body = await _fetch_feed_body(session, feed_url)

                # Permanent errors — no retry (channel deleted/blocked)
                if status == 404:
                    logger.warning("RSS 404 for channel %s (deleted?) — no retry", channel_id)
                    return None
                if status == 500:
                    logger.warning("RSS 500 for channel %s (YouTube server error) — no retry", channel_id)
                    return None
                if status != 200:
                    logger.warning("RSS HTTP %d for channel %s — no retry", status, channel_id)
                    return None

                # 200 OK — parse the body
                feed = feedparser.parse(body)
                if feed.entries:
                    return feed

                # 200 but empty — try search_query fallback (only for non-UC handles)
                if "@" not in channel_id and not channel_id.startswith("UC"):
                    search_url = f"https://www.youtube.com/feeds/videos.xml?search_query={channel_id}"
                    try:
                        s_status, s_body = await _fetch_feed_body(session, search_url)
                        if s_status == 200 and s_body:
                            feed = feedparser.parse(s_body)
                            if feed.entries:
                                return feed
                    except Exception:
                        pass

                # 200-empty — retry (transient YouTube cache refresh).
                # Use asyncio.sleep (NOT time.sleep) to avoid blocking the
                # event loop — other async tasks keep running during backoff.
                if attempt < FEED_RETRY_ATTEMPTS:
                    logger.info("Empty RSS feed for %s (attempt %d/%d), retrying in %ds",
                                channel_id, attempt, FEED_RETRY_ATTEMPTS, FEED_RETRY_BACKOFF_SEC)
                    await asyncio.sleep(FEED_RETRY_BACKOFF_SEC)
            except Exception as e:
                logger.error("RSS fetch error for %s (attempt %d/%d): %s",
                             channel_id, attempt, FEED_RETRY_ATTEMPTS, e)
                if attempt < FEED_RETRY_ATTEMPTS:
                    await asyncio.sleep(FEED_RETRY_BACKOFF_SEC)

    logger.warning("No feed entries for channel %s after %d attempts",
                   channel_id, FEED_RETRY_ATTEMPTS)
    return None


async def process_subscription(sub: dict) -> int:
    channel_id = sub["channel_id"]
    # Prefer youtube_channel_id (UCxxxxx) for RSS — handles don't work with RSS feed
    yt_channel_id = sub.get("youtube_channel_id", "") or ""
    feed_id = yt_channel_id if yt_channel_id else channel_id
    playlist_id = sub["playlist_id"]
    quality = sub["quality"]
    sub_id = sub["id"]
    white_filter = sub.get("white_filter", "") or ""
    black_filter = sub.get("black_filter", "") or ""

    feed = await get_channel_feed(feed_id)
    if not feed:
        logger.warning("No feed entries for channel %s (feed_id=%s)", channel_id, feed_id)
        return 0

    uploaded = 0
    for entry in feed.entries:
        yt_url = entry.get("link", "")
        yt_id = extract_video_id(yt_url)
        if not yt_id:
            continue

        # Skip if already processed — NO EXCEPTIONS.
        # If a video was deleted on VideoHost by the user, it stays in
        # processed_videos and is NOT re-downloaded. This is intentional:
        # user deletion should be respected.
        # Use /backfill to force re-download if needed.
        existing = await db.get_processed_video(yt_id)
        if existing:
            continue

        title = entry.get("title", "Untitled")

        # Apply white/black list filters (skip silently — don't mark as processed,
        # so if user later changes filters, the video can still be downloaded).
        if not should_download(title, white_filter, black_filter):
            logger.info("Filter skip: %s (%s) — white=%r black=%r",
                        title, yt_id, white_filter, black_filter)
            continue

        # Skip videos older than 7 days
        published = entry.get("published_parsed")
        if published:
            pub_dt = datetime(*published[:6], tzinfo=timezone.utc)
            age = (datetime.now(tz=timezone.utc) - pub_dt).days
            if age > 7:
                logger.info("Skipping old video: %s (%d days)", title, age)
                await db.mark_video_processed(yt_id, sub_id, title, quality, "")
                continue

        logger.info("New video from %s: %s (%s)", channel_id, title, yt_id)

        # Get publication date from RSS entry (used for playlist sorting)
        published_iso = ""
        if published:
            try:
                pub_dt = datetime(*published[:6], tzinfo=timezone.utc)
                published_iso = pub_dt.strftime("%Y-%m-%dT%H:%M:%SZ")
            except Exception:
                pass

        file_path = await download_video(yt_url, quality)
        if not file_path or file_path == "TOO_LARGE":
            if file_path == "TOO_LARGE":
                logger.warning("Video too large, skipping: %s", title)
                await db.mark_video_processed(yt_id, sub_id, title, quality, "")
            continue

        # YouTube thumbnail URL — always available for public videos
        yt_thumb = f"https://img.youtube.com/vi/{yt_id}/hqdefault.jpg" if yt_id else ""

        result = await upload_video(
            file_path, title, playlist_id or None,
            published_at=published_iso,
            thumbnail_url=yt_thumb,
            youtube_id=yt_id,
        )
        cleanup_file(file_path)

        if result:
            vh_id = result.get("id", "")
            await db.mark_video_processed(yt_id, sub_id, title, quality, vh_id)
            uploaded += 1
            logger.info("Uploaded: %s → %s", title, vh_id)
            # Re-sort playlist chronologically
            if playlist_id:
                await sort_playlist(playlist_id)
        else:
            logger.error("Failed to upload: %s", title)

    await db.update_last_check(sub_id)
    return uploaded


async def scheduler_loop(bot=None, admin_chat_id: int = 0):
    logger.info("Scheduler started (interval: %ds)", CHECK_INTERVAL)
    while True:
        try:
            subs = await db.list_subscriptions()
            active_subs = [s for s in subs if s["active"]]
            logger.info("Checking %d active subscriptions", len(active_subs))

            total_uploaded = 0
            for sub in active_subs:
                try:
                    count = await process_subscription(sub)
                    total_uploaded += count
                except Exception as e:
                    logger.error("Error processing subscription %s: %s", sub["id"], e)

            if total_uploaded > 0 and admin_chat_id and bot:
                try:
                    await bot.send_message(
                        admin_chat_id,
                        f"📡 Проверка подписок: загружено {total_uploaded} новых видео.",
                    )
                except Exception:
                    pass

        except Exception as e:
            logger.error("Scheduler error: %s", e)

        await asyncio.sleep(CHECK_INTERVAL)