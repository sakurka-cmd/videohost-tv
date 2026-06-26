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
            # channel = display name (e.g. "Асафьев. Жизнь")
            "channel": info.get("channel", ""),
            # uploader = handle without @ (e.g. "AsafevLife"), or display name
            # when channel has no handle. Prefer uploader for playlist naming
            # because it's stable across locales and matches the URL.
            "uploader": info.get("uploader", "") or info.get("channel", ""),
            "uploader_url": info.get("uploader_url", "") or info.get("channel_url", ""),
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


async def get_channel_info(url: str) -> dict | None:
    """Get channel info via RSS feed."""
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
                "title": feed.feed.get("title", channel_id),
                "link": feed.feed.get("link", url),
            }
    except Exception as e:
        logger.error("Channel info error: %s", e)
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