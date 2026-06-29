"""FSM states for Telegram bot."""

from enum import Enum


class States(str, Enum):
    IDLE = ""
    # Subscribe flow
    SUB_ASK_URL = "sub_ask_url"
    SUB_ASK_QUALITY = "sub_ask_quality"
    SUB_ASK_PLAYLIST = "sub_ask_playlist"
    SUB_CONFIRM = "sub_confirm"
    # One-off download flow
    DL_ASK_URL = "dl_ask_url"
    DL_ASK_QUALITY = "dl_ask_quality"
    DL_ASK_PLAYLIST = "dl_ask_playlist"
    # Unsubscribe flow
    UNSUB_SELECT = "unsub_select"
    # Quality change flow
    QUALITY_SELECT = "quality_select"
    QUALITY_VALUE = "quality_value"
    # Playlist creation
    PLAYLIST_ASK_NAME = "playlist_ask_name"
    # YouTube playlist download flow
    DLPL_ASK_URL = "dlpl_ask_url"
    DLPL_ASK_QUALITY = "dlpl_ask_quality"
    DLPL_ASK_PERIOD = "dlpl_ask_period"