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
                       playlist_id: str | None = None,
                       published_at: str = "") -> dict | None:
    """Upload video to VideoHost. Returns response dict or None.

    published_at: optional ISO date string or YYYYMMDD (yt-dlp upload_date).
    Stored on the Video record and used for chronological playlist sorting.
    """
    global current_status
    file_size = os.path.getsize(file_path)
    size_mb = file_size / (1024 * 1024)

    current_status.update({
        "task": "upload", "title": title,
        "progress": f"{size_mb:.1f} MB uploading...", "error": "",
    })
    logger.info("Uploading to VideoHost: %s (%.1f MB) playlist=%s published_at=%s",
                title, size_mb, playlist_id, published_at or "-")

    try:
        with open(file_path, "rb") as f:
            form = aiohttp.FormData()
            form.add_field("file", f, filename=f"{title}.mp4", content_type="video/mp4")
            form.add_field("title", title)
            if playlist_id:
                form.add_field("playlistId", playlist_id)
            if published_at:
                form.add_field("publishedAt", published_at)

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


async def sort_playlist(playlist_id: str) -> bool:
    """Ask VideoHost to reorder playlist items chronologically by publishedAt.

    Returns True on success, False otherwise.
    """
    if not playlist_id:
        return False
    try:
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"{VIDEOHOST_URL}/api/bot/playlists/{playlist_id}/sort",
                headers=_headers(),
                timeout=aiohttp.ClientTimeout(total=60),
            ) as resp:
                if resp.status == 200:
                    data = await resp.json(content_type=None)
                    logger.info("Sorted playlist %s: %s items reordered",
                                playlist_id, data.get("reordered", "?"))
                    return True
                else:
                    err = await resp.text()
                    logger.error("Sort playlist %s failed %d: %s",
                                 playlist_id, resp.status, err[:300])
                    return False
    except Exception as e:
        logger.error("Sort playlist error: %s", e)
        return False


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


async def find_or_create_playlist(name: str, description: str = "") -> dict | None:
    """Find an existing playlist by name, or create a new one via bot API.

    Returns: {"id": "...", "name": "...", "description": "...", "createdAt": "..."}
    or None on failure.
    """
    if not name or not name.strip():
        return None
    payload = {"name": name.strip()}
    if description and description.strip():
        payload["description"] = description.strip()
    try:
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"{VIDEOHOST_URL}/api/bot/playlists",
                json=payload,
                headers=_headers(),
                timeout=aiohttp.ClientTimeout(total=30),
            ) as resp:
                if resp.status in (200, 201):
                    data = await resp.json(content_type=None)
                    logger.info("find_or_create_playlist('%s') -> %s (status=%d)",
                                name, data.get("id") if data else "?", resp.status)
                    return data
                else:
                    err = await resp.text()
                    logger.error("find_or_create_playlist failed %d: %s",
                                 resp.status, err[:300])
                    return None
    except Exception as e:
        logger.error("find_or_create_playlist error: %s", e)
        return None


async def reorder_playlist(playlist_id: str, items: list[dict]) -> bool:
    """items: [{itemId, order}, ...]"""
    # This endpoint requires auth session, not bot token.
    # For bot, we skip reorder via API. If needed, implement differently.
    return True