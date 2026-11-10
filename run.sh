#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/backend"

# 优先使用仓库内已验证的虚拟环境解释器，否则回退到系统 python3。
if [[ -x ".venv/bin/python" ]]; then
    PY=".venv/bin/python"
else
    PY="python3"
fi

exec "$PY" -m uvicorn app.main:app \
    --host "${FF_HOST:-127.0.0.1}" --port "${PORT:-8123}"
