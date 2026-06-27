"""yt-dlp wrapper for downloading YouTube videos."""

import asyncio
import logging
import os
import re
import shutil
from pathlib import Path

from bot.config import QUALITIES, DEFAULT_QUALITY, MAX_FILE_SIZE, TMP_DIR

logger = logging.getLogger(__name__)

YOUTUBE_URL_RE = re.compile(
    r"(?:https?://)?(?:www\.)?"
    r"(?:youtube\.com/(?:watch\?v=|shorts/|embed/)|youtu\.be/)"
    r"([a-zA-Z0-9_-]{11})"
)

CHANNEL_URL_RE = re.compile(
    r"(?:https?://)?(?:www\.)?youtube\.com/(?:channel/|@|c/)([a-zA-Z0-9_.-]+)"
)

YTDLP_BIN = shutil.which("yt-dlp") or "yt-dlp"

# Global status for /status command
current_status: dict = {
    "task": "",
    "url": "",
    "title": "",
    "progress": "",
    "error": "",
}


def extract_video_id(url: str) -> str | None:
    m = YOUTUBE_URL_RE.search(url)
    return m.group(1) if m else None


def extract_channel_id(url: str) -> str | None:
    m = CHANNEL_URL_RE.search(url)
    return m.group(1) if m else None


def get_format_string(quality: str) -> str:
    fmt = QUALITIES.get(quality, QUALITIES[DEFAULT_QUALITY])
    return fmt


async def get_video_info(url: str) -> dict | None:
    cmd = [YTDLP_BIN, "--dump-json", "--no-download", "--no-playlist", url]
    try:
        proc = await asyncio.create_subprocess_exec(
            *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=60)
        if proc.returncode != 0:
            logger.error("yt-dlp info failed: %s", stderr.decode(errors="replace")[:500])
            return None
        import json
        info = json.loads(stdout.decode())
        return {
            "id": info.get("id", ""),
            "title": info.get("title", ""),
            "duration": info.get("duration", 0),
            # channel = display name (e.g. "Асафьев. Жизнь" or "Russian Car Crash channel")
            "channel": info.get("channel", ""),
            # uploader_id = full handle with @ (e.g. "@russiancrashchannel6171")
            # This is the canonical handle on YouTube — use this (without @) for
            # playlist naming so /dl and /subscribe produce the same name.
            "uploader_id": info.get("uploader_id", ""),
            # uploader = display name (NOT handle) for many YouTube videos — kept for backward compat only
            "uploader": info.get("uploader", "") or info.get("channel", ""),
            "uploader_url": info.get("uploader_url", "") or info.get("channel_url", ""),
            # channel_id = UCxxxxxxx (unique YouTube channel ID, never changes)
            # This is the reliable key for matching /dl → subscription
            "channel_id": info.get("channel_id", ""),
            "description": info.get("description", "")[:500],
            "thumbnail": info.get("thumbnail", ""),
            "filesize_approx": info.get("filesize_approx", 0),
            # yt-dlp returns upload_date as YYYYMMDD string
            "upload_date": info.get("upload_date", ""),
        }
    except asyncio.TimeoutError:
        logger.error("yt-dlp info timeout for %s", url)
        return None
    except Exception as e:
        logger.error("yt-dlp info error: %s", e)
        return None


def clean_handle(s: str) -> str:
    """Extract a clean handle (without @, without URL prefix) from various
    yt-dlp fields: uploader_id (@russiancrashchannel6171) or uploader_url
    (https://www.youtube.com/@russiancrashchannel6171).

    Returns the bare handle, e.g. 'russiancrashchannel6171'.
    Returns empty string if input is empty.
    """
    if not s:
        return ""
    s = s.strip()
    if s.startswith("@"):
        return s[1:]
    if s.startswith("http"):
        from urllib.parse import urlparse
        path = urlparse(s).path.strip("/")
        if path.startswith("@"):
            return path[1:].split("/")[0]
    return s


async def get_channel_info(url: str) -> dict | None:
    """Get channel info via yt-dlp (preferred) or RSS feed (fallback).

    Returns: {
        "id": <channel_id UCxxxxx or handle>,
        "channel_id": <UCxxxxx>,           # unique YouTube channel ID
        "channel_handle": <handle without @>,  # e.g. "russiancrashchannel6171"
        "title": <display name>,            # e.g. "Russian Car Crash channel"
        "link": <channel URL>,
    }
    """
    # Try yt-dlp first — it returns the canonical channel_id (UCxxxxx)
    # and uploader_id (the actual handle YouTube uses, which may differ
    # from the handle in the URL the user pasted).
    cmd = [
        YTDLP_BIN, "--dump-json", "--no-download",
        "--playlist-items", "1",  # only fetch first video to save time
        url,
    ]
    try:
        proc = await asyncio.create_subprocess_exec(
            *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=60)
        if proc.returncode == 0:
            import json
            info = json.loads(stdout.decode())
            channel_id = info.get("channel_id", "")
            uploader_id = info.get("uploader_id", "")
            handle = clean_handle(uploader_id) or clean_handle(info.get("uploader_url", ""))
            display_name = info.get("channel", "") or handle or channel_id
            return {
                "id": channel_id or handle or extract_channel_id(url) or "",
                "channel_id": channel_id,
                "channel_handle": handle,
                "title": display_name,
                "link": info.get("uploader_url", url),
            }
        else:
            logger.warning("yt-dlp channel info failed, falling back to RSS: %s",
                          stderr.decode(errors="replace")[:300])
    except asyncio.TimeoutError:
        logger.warning("yt-dlp channel info timeout, falling back to RSS")
    except Exception as e:
        logger.warning("yt-dlp channel info error, falling back to RSS: %s", e)

    # Fallback to RSS (used when yt-dlp can't resolve the channel)
    return await _get_channel_info_rss(url)


async def _get_channel_info_rss(url: str) -> dict | None:
    """Fallback: get channel info via RSS feed (less accurate, no UCxxxxx)."""
    channel_id = extract_channel_id(url)
    if not channel_id:
        return None
    import feedparser
    if channel_id.startswith("@") or channel_id.startswith("UC"):
        feed_url = f"https://www.youtube.com/feeds/videos.xml?channel_id={channel_id}"
    else:
        feed_url = f"https://www.youtube.com/feeds/videos.xml?channel_id=@{channel_id}"
    try:
        feed = feedparser.parse(feed_url)
        if not feed.entries:
            feed_url = f"https://www.youtube.com/feeds/videos.xml?search_query={channel_id}"
            feed = feedparser.parse(feed_url)
        if feed.feed:
            return {
                "id": channel_id,
                "channel_id": "",  # RSS doesn't reliably give UCxxxxx
                "channel_handle": clean_handle(channel_id) or channel_id,
                "title": feed.feed.get("title", channel_id),
                "link": feed.feed.get("link", url),
            }
    except Exception as e:
        logger.error("Channel info RSS error: %s", e)
    return None


async def download_video(url: str, quality: str = DEFAULT_QUALITY) -> str | None:
    """Download video, return path. Caller must delete. Returns 'TOO_LARGE' if too big."""
    global current_status
    os.makedirs(TMP_DIR, exist_ok=True)

    fmt = get_format_string(quality)
    output_template = os.path.join(TMP_DIR, "%(id)s.%(ext)s")

    cmd = [
        YTDLP_BIN, "-f", fmt, "--merge-output-format", "mp4",
        "-o", output_template, "--no-playlist", "--no-cache-dir",
        "--newline", "--progress", url,
    ]
    if MAX_FILE_SIZE > 0:
        cmd.extend(["--max-filesize", str(MAX_FILE_SIZE)])

    yt_id = extract_video_id(url) or "unknown"
    current_status.update({"task": "download", "url": url, "title": yt_id, "progress": "0%", "error": ""})
    logger.info("Downloading: %s (quality: %s)", url, quality)

    try:
        proc = await asyncio.create_subprocess_exec(
            *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
        while True:
            line = await proc.stdout.readline()
            if not line:
                break
            text = line.decode(errors="replace").strip()
            # Parse progress: [download]  45.2% of ~150.00MiB
            if "[download]" in text and "%" in text:
                pct = text.split("%")[0].split()[-1] if "%" in text else ""
                current_status["progress"] = pct

        await asyncio.wait_for(proc.wait(), timeout=600)

        if proc.returncode != 0:
            err = (await proc.stderr.read()).decode(errors="replace")
            logger.error("yt-dlp download failed: %s", err[:500])
            current_status["error"] = err[:200]
            if "File is larger than max-filesize" in err:
                return "TOO_LARGE"
            return None

        # Find downloaded file
        for ext in ("mp4", "mkv", "webm", "flv"):
            path = os.path.join(TMP_DIR, f"{yt_id}.{ext}")
            if os.path.exists(path):
                return path

        # Fallback: find any recent file in TMP_DIR
        files = sorted(Path(TMP_DIR).glob(f"{yt_id}*"))
        for f in files:
            if f.stat().st_size > 1024:
                return str(f)

        logger.error("Downloaded file not found for %s", yt_id)
        return None
    except asyncio.TimeoutError:
        logger.error("yt-dlp download timeout for %s", url)
        current_status["error"] = "Timeout"
        return None
    except Exception as e:
        logger.error("yt-dlp download error: %s", e)
        current_status["error"] = str(e)
        return None


def cleanup_file(path: str):
    try:
        if path and os.path.exists(path):
            os.remove(path)
            logger.info("Cleaned up: %s", path)
    except OSError as e:
        logger.warning("Cleanup failed for %s: %s", path, e)