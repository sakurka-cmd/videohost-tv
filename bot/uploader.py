"""VideoHost API uploader."""

import logging
import os

import aiohttp

from bot.config import VIDEOHOST_URL, VIDEOHOST_TOKEN
from bot.downloader import current_status

logger = logging.getLogger(__name__)


def _headers() -> dict:
    h = {}
    if VIDEOHOST_TOKEN:
        h["Authorization"] = f"Bearer {VIDEOHOST_TOKEN}"
        h["X-Bot-Token"] = VIDEOHOST_TOKEN
    return h


async def _request(method: str, path: str, **kwargs) -> dict | None:
    url = f"{VIDEOHOST_URL}{path}"
    kwargs.setdefault("headers", _headers())
    try:
        async with aiohttp.ClientSession() as session:
            async with session.request(method, url, **kwargs) as resp:
                data = await resp.json(content_type=None)
                if resp.status >= 400:
                    logger.error("VideoHost %s %s → %d: %s", method, path, resp.status, data)
                    return None
                return data
    except Exception as e:
        logger.error("VideoHost request error %s %s: %s", method, path, e)
        return None


async def upload_video(file_path: str, title: str,
                       playlist_id: str | None = None) -> dict | None:
    """Upload video to VideoHost. Returns response dict or None."""
    global current_status
    file_size = os.path.getsize(file_path)
    size_mb = file_size / (1024 * 1024)

    current_status.update({
        "task": "upload", "title": title,
        "progress": f"{size_mb:.1f} MB uploading...", "error": "",
    })
    logger.info("Uploading to VideoHost: %s (%.1f MB) playlist=%s", title, size_mb, playlist_id)

    try:
        with open(file_path, "rb") as f:
            form = aiohttp.FormData()
            form.add_field("file", f, filename=f"{title}.mp4", content_type="video/mp4")
            form.add_field("title", title)
            if playlist_id:
                form.add_field("playlistId", playlist_id)

            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"{VIDEOHOST_URL}/api/bot/upload",
                    data=form,
                    headers=_headers(),
                    timeout=aiohttp.ClientTimeout(total=1800),
                ) as resp:
                    if resp.status == 201:
                        data = await resp.json(content_type=None)
                        logger.info("Uploaded: %s → %s", title, data.get("id", "?"))
                        current_status["progress"] = "done"
                        return data
                    else:
                        err = await resp.text()
                        logger.error("Upload failed %d: %s", resp.status, err[:300])
                        current_status["error"] = f"HTTP {resp.status}"
                        return None
    except Exception as e:
        logger.error("Upload error: %s", e)
        current_status["error"] = str(e)
        return None


async def list_playlists() -> list[dict]:
    data = await _request("GET", "/api/bot/playlists")
    if data and isinstance(data, list):
        return data
    return []


async def list_playlist_items(playlist_id: str) -> list[dict]:
    data = await _request("GET", f"/api/bot/playlists?playlistId={playlist_id}")
    if data and isinstance(data, list):
        return data
    return []


async def reorder_playlist(playlist_id: str, items: list[dict]) -> bool:
    """items: [{itemId, order}, ...]"""
    # This endpoint requires auth session, not bot token.
    # For bot, we skip reorder via API. If needed, implement differently.
    return True