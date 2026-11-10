"""服务端大模型分析：配置持久化 + 代理调用，密钥只存本地 data/llm.json，不入库不进 git。

设计（2026-11）：
  * 配置存 DATA_DIR/llm.json（{base, model, key}），相对环境变量 FF_LLM_* 优先级：文件 > 环境变量。
  * /api/llm/config  GET 返回 {base, model}（不含 key）；POST 写入配置。
  * /api/llm/analyze POST {code, period?}：服务端拼装「近期行情 + 量化预测」提示词，调用 LLM，
    返回 {text}。密钥不出服务端，浏览器只收到分析结果文本。
"""
from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

import httpx

from . import collector, store
from .config import DATA_DIR

log = logging.getLogger("llm")

_LLM_JSON = DATA_DIR / "llm.json"
_TIMEOUT = 60.0

_DEFAULT_BASE = "https://api.deepseek.com/v1"
_DEFAULT_MODEL = "deepseek-chat"


def _load() -> dict:
    if _LLM_JSON.exists():
        try:
            return json.loads(_LLM_JSON.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            log.warning("llm.json 解析失败，回退默认")
    return {
        "base": _env("FF_LLM_BASE", _DEFAULT_BASE),
        "model": _env("FF_LLM_MODEL", _DEFAULT_MODEL),
        "key": _env("FF_LLM_KEY", ""),
    }


def _env(name: str, default: str) -> str:
    import os
    return os.getenv(name, default)


def _save(cfg: dict) -> None:
    _LLM_JSON.parent.mkdir(parents=True, exist_ok=True)
    _LLM_JSON.write_text(json.dumps(cfg, ensure_ascii=False, indent=2),
                         encoding="utf-8")
    # 密钥文件仅本人可读
    try:
        _LLM_JSON.chmod(0o600)
    except Exception:  # noqa: BLE001
        pass


def get_config() -> dict:
    cfg = _load()
    return {"base": cfg.get("base", _DEFAULT_BASE),
            "model": cfg.get("model", _DEFAULT_MODEL),
            "hasKey": bool(cfg.get("key"))}


def set_config(base: str, model: str, key: str | None) -> dict:
    cfg = _load()
    if base:
        cfg["base"] = base
    if model:
        cfg["model"] = model
    if key is not None:
        # 显式清除哨兵：把密钥置空；空字符串（表单未填）表示不修改
        cfg["key"] = "" if key == "__CLEAR__" else key
    _save(cfg)
    return get_config()


def _build_prompt(code: str, period: str) -> str:
    """拼装提示词：近期行情 + 量化七档预测摘要。"""
    bars = store.get_minute(code, 120)
    recent = bars[-60:] if bars else []
    last_price = recent[-1]["c"] if recent else None
    fc = collector.state.forecast.get(code) or {}
    horizons = fc.get("horizons", [])
    lines = [
        f"你是期货量化分析师。品种：{code}（{fc.get('name', code)}）。",
        f"当前最新价：{last_price}。" if last_price is not None else "当前无行情。",
        "近期 1 分钟收盘序列（最新 60 根）：" +
        (",".join(str(b["c"]) for b in recent) if recent else "无"),
        "量化模型七档方向预测：",
    ]
    for h in horizons:
        lines.append(
            f"  {h.get('label')}: {h.get('direction')} "
            f"(p_up={h.get('p_up')}, 置信={h.get('confidence')}, "
            f"预期变动={h.get('exp_move_pct')}%)")
    lines.append(
        "请基于以上数据，用中文给出该品种的多周期趋势研判与关键位置"
        "（支撑/压力），并提示风险。控制在 300 字以内，不要编造数据。")
    return "\n".join(lines)


async def analyze(code: str, period: str | None = None) -> dict:
    cfg = _load()
    base = (cfg.get("base") or _DEFAULT_BASE).rstrip("/")
    model = cfg.get("model") or _DEFAULT_MODEL
    key = cfg.get("key") or ""
    if not key:
        return {"ok": False, "error": "未配置 LLM Key（设置页填写后重试）"}
    prompt = _build_prompt(code, period or "1m")
    url = base + "/chat/completions"
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.3,
        "max_tokens": 800,
    }
    headers = {"Authorization": f"Bearer {key}",
               "Content-Type": "application/json"}
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            r = await client.post(url, json=payload, headers=headers)
            if r.status_code != 200:
                return {"ok": False, "error": f"LLM HTTP {r.status_code}",
                        "detail": r.text[:300]}
            data = r.json()
            text = data["choices"][0]["message"]["content"]
            return {"ok": True, "text": text, "model": model}
    except Exception as e:  # noqa: BLE001
        log.exception("llm analyze 失败")
        return {"ok": False, "error": type(e).__name__}
