import json
import os
import urllib.request

TOKEN = os.environ.get("GITEE_TOKEN", "")
REPO = "zpj100/futures-forecast"
REL = "1291191"
BASE = f"https://gitee.com/api/v5/repos/{REPO}/releases/{REL}"


def get(url):
    with urllib.request.urlopen(url, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))


def main():
    try:
        items = get(f"{BASE}/attach_files?access_token={TOKEN}")
    except Exception as e:  # noqa: BLE001
        print("list failed:", e)
        return
    if isinstance(items, dict):
        items = items.get("attach_files", []) or items.get("assets", [])
    for a in items:
        name = a.get("name", "")
        if not name.endswith(".apk"):
            continue
        aid = a.get("id")
        print("delete", aid, name)
        req = urllib.request.Request(
            f"{BASE}/attach_files/{aid}?access_token={TOKEN}", method="DELETE")
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                print("  ->", r.status)
        except Exception as e:  # noqa: BLE001
            print("  -> failed", e)


if __name__ == "__main__":
    main()
