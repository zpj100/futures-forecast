"""HTTP helpers for public market/news endpoints."""
from __future__ import annotations

import json
import re
from typing import Any

import httpx

from ..config import HTTP_TIMEOUT, HTTP_UA

_HEADERS = {
    "User-Agent": HTTP_UA,
    "Referer": "https://finance.sina.com.cn",
    "Accept": "*/*",
}

_client: httpx.AsyncClient | None = None


async def client() -> httpx.AsyncClient:
    global _client
    if _client is None or _client.is_closed:
        _client = httpx.AsyncClient(
            timeout=HTTP_TIMEOUT,
            headers=_HEADERS,
            follow_redirects=True,
        )
    return _client


async def close_client() -> None:
    global _client
    if _client is not None and not _client.is_closed:
        await _client.aclose()
    _client = None


async def get_text(url: str, *, referer: str | None = None,
                   encoding: str | None = None) -> str:
    c = await client()
    headers = {"Referer": referer} if referer else None
    r = await c.get(url, headers=headers)
    r.raise_for_status()
    if encoding:
        return r.content.decode(encoding, errors="replace")
    return r.text


_SCRIPT_RE = re.compile(r"/\*<script.*?</script>\*/\s*", re.S)


def jsonp_to_json(text: str) -> Any:
    """Extract the JSON payload from a `var _=(...)` jsonp response."""
    text = _SCRIPT_RE.sub("", text).strip()
    if text.startswith("var "):
        idx = text.find("=")
        if idx >= 0:
            text = text[idx + 1:]
    text = text.strip()
    if text.endswith(";"):
        text = text[:-1].strip()
    if text.startswith("(") and text.endswith(")"):
        text = text[1:-1]
    return json.loads(text)
