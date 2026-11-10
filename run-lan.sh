#!/usr/bin/env bash
# 手机 / 局域网访问启动脚本：让 uvicorn 监听 0.0.0.0。
# 注意：不改 run.sh 的默认值（仍为 127.0.0.1），仅通过环境变量覆盖。
#
# Windows 侧还需完成两步（在「以管理员身份」的 PowerShell / CMD 中执行）：
#   1) 端口转发：WSL2 是独立虚拟网络，需把 Windows 的 8000 转发到 WSL2 的 8000。
#      <WSL2-IP> 用 `wsl -d Debian -e hostname -I` 查询（重启后可能变化）。
#      netsh interface portproxy add v4tov4 listenport=8000 listenaddress=0.0.0.0 connectport=8000 connectaddress=<WSL2-IP>
#   2) 防火墙放行 8000 入站：
#      netsh advfirewall firewall add rule name="futures-forecast 8000" dir=in action=allow protocol=TCP localport=8000
# 之后同一局域网的手机访问 http://<Windows-局域网IP>:8000 即可。
cd "$(dirname "$0")"
exec env FF_HOST=0.0.0.0 PORT="${PORT:-8000}" bash run.sh
