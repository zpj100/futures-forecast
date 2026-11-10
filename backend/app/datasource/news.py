"""News aggregation from public endpoints.

Sources verified working (2026-11):
  * 东方财富 全文搜索 (search-api-web.eastmoney.com)  —— 可按关键词检索，含电解铝/氧化铝/伦铝
  * 同花顺 7x24 快讯 (news.10jqka.com.cn)             —— 财经快讯流，本地关键词过滤

Some venues (路透中文、文华财经、博易、SMM 内容接口) either 403 / require login /
are client-rendered, so their public pages are not scraped directly. Their news
usually propagates to the two aggregators above within minutes; keyword mapping
below tags relevant items so the UI can still surface them.
"""
from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime, timezone
from urllib.parse import quote

from ..config import HTTP_UA
from .http_util import client

EM_SEARCH = "https://search-api-web.eastmoney.com/search/jsonp"
THS_PUSH = ("https://news.10jqka.com.cn/tapp/news/push/stock/"
            "?page={page}&tag=&track=website&pagesize={size}")

# keyword -> product code tags
KEYWORDS = {
    "电解铝": "AL",
    "沪铝": "AL",
    "原铝": "AL",
    "铝锭": "AL",
    "氧化铝": "AO",
    "铝土矿": "AO",
    "伦铝": "AHD",
    "LME铝": "AHD",
    "LME 铝": "AHD",
    "铝价": "AL,AO,AHD",
}

_TAG_RE = re.compile(r"<[^>]+>")


def _clean(s: str | None) -> str:
    if not s:
        return ""
    return _TAG_RE.sub("", s).replace("&nbsp;", " ").strip()


def _match_codes(text: str) -> str:
    codes: set[str] = set()
    for kw, code in KEYWORDS.items():
        if kw in text:
            codes.update(code.split(","))
    return ",".join(sorted(codes))


def _mkid(source: str, key: str) -> str:
    return hashlib.sha1(f"{source}:{key}".encode()).hexdigest()[:20]


async def fetch_eastmoney(keyword: str, size: int = 20) -> list[dict]:
    param = {
        "uid": "",
        "keyword": keyword,
        "type": ["cmsArticleWebOld"],
        "client": "web",
        "clientType": "web",
        "param": {
            "cmsArticleWebOld": {
                "searchScope": "default",
                "sort": "time",
                "pageIndex": 1,
                "pageSize": size,
            }
        },
    }
    url = f"{EM_SEARCH}?cb=x&param={quote(json.dumps(param, ensure_ascii=False))}"
    c = await client()
    r = await c.get(url, headers={"User-Agent": HTTP_UA})
    r.raise_for_status()
    text = r.text.strip()
    if text.startswith("x("):
        text = text[2:]
    if text.endswith(")"):
        text = text[:-1]
    data = json.loads(text)
    rows = (data.get("result") or {}).get("cmsArticleWebOld") or []
    out = []
    for it in rows:
        title = _clean(it.get("title"))
        content = _clean(it.get("content"))
        if not title:
            continue
        # 检索接口会带出大量弱相关稿，标题命中才保留
        if keyword not in title:
            continue
        ts = it.get("date") or ""
        out.append({
            "id": _mkid("eastmoney", it.get("code") or title),
            "source": "东方财富",
            "title": title,
            "url": f"https://finance.eastmoney.com/a/{it.get('code')}.html"
                   if it.get("code") else "",
            "ts": ts,
            "summary": content[:220],
            "codes": _match_codes(title + content) or _match_codes(keyword),
        })
    return out


async def fetch_ths(page: int = 1, size: int = 60) -> list[dict]:
    c = await client()
    r = await c.get(THS_PUSH.format(page=page, size=size),
                    headers={"User-Agent": HTTP_UA})
    r.raise_for_status()
    data = r.json()
    items = ((data.get("data") or {}).get("list")) or []
    out = []
    for it in items:
        title = _clean(it.get("title"))
        digest = _clean(it.get("digest"))
        codes = _match_codes(title + digest)
        if not codes:
            continue
        ts_raw = it.get("rtime") or it.get("ctime") or "0"
        try:
            ts = datetime.fromtimestamp(int(ts_raw), tz=timezone.utc).isoformat()
        except (TypeError, ValueError):
            ts = ""
        out.append({
            "id": _mkid("ths", str(it.get("id") or title)),
            "source": "同花顺",
            "title": title,
            "url": it.get("url") or "",
            "ts": ts,
            "summary": digest[:220],
            "codes": codes,
        })
    return out


async def fetch_all(errors: list[str] | None = None) -> list[dict]:
    """Fetch from every source; tolerate individual failures but surface them."""
    import asyncio
    import logging

    tasks = [fetch_eastmoney("电解铝"), fetch_eastmoney("氧化铝"),
             fetch_eastmoney("伦铝"), fetch_ths()]
    labels = ["eastmoney/电解铝", "eastmoney/氧化铝", "eastmoney/伦铝", "同花顺"]
    results = await asyncio.gather(*tasks, return_exceptions=True)
    merged: list[dict] = []
    for label, r in zip(labels, results):
        if isinstance(r, list):
            merged.extend(r)
        else:
            exc = r if isinstance(r, BaseException) else Exception(str(r))
            # 详细异常（可能含第三方 URL）只写日志，对外登记脱敏摘要
            logging.warning("news %s 拉取失败: %s", label, exc)
            if errors is not None:
                errors.append(f"{label}: {type(exc).__name__}")
    dedup: dict[str, dict] = {}
    for n in merged:
        dedup.setdefault(n["id"], n)
    return list(dedup.values())
