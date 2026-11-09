#!/usr/bin/env python3
"""生成「学记期货」启动图标。

设计：深色底 + 白色圆角方框，方框内双行小字「学记 / 期货」。
同时输出自适应图标前景图（内容限制在 108dp 的安全区内）。
用法：python3 scripts/make_icons.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "android/app/src/main/res"
FONT_BOLD = "/mnt/c/Windows/Fonts/msyhbd.ttc"

DENSITIES = [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0)]

SS = 4          # 超采样倍数
BASE = 512      # 设计基准边长
PANEL = (11, 18, 32)   # 深色底
INK = (10, 16, 28)     # 框内文字色
WHITE = (255, 255, 255)


def background(px):
    """竖向渐变 + 左上青色光晕。"""
    small = 192
    img = Image.new("RGB", (small, small))
    p = img.load()
    for y in range(small):
        t = y / (small - 1)
        br = int(12 * (1 - t) + 5 * t)
        bg = int(23 * (1 - t) + 9 * t)
        bb = int(40 * (1 - t) + 16 * t)
        for x in range(small):
            dx, dy = (x - small * 0.30), (y - small * 0.14)
            g = max(0.0, 1.0 - (dx * dx + dy * dy) / (small * small * 0.42))
            g = g * g * 0.7
            p[x, y] = (min(255, int(br + g * 18)), min(255, int(bg + g * 120)),
                       min(255, int(bb + g * 150)))
    return img.resize((px, px), Image.BICUBIC).convert("RGBA")


def draw_content(draw, s, scale):
    """白色圆角方框 + 双行文字。s 为整像素边长，scale 为内容缩放。"""
    def u(v):            # 设计单位 -> 像素
        return v * scale * s / BASE

    w, h = u(256), u(208)
    cx, cy = s / 2, s / 2
    box = (cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    r = u(40)

    # 轻微投影
    sh = u(10)
    draw.rounded_rectangle((box[0], box[1] + sh * 0.7, box[2], box[3] + sh * 0.7),
                           radius=r, fill=(0, 0, 0, 90))
    draw.rounded_rectangle(box, radius=r, fill=WHITE)

    fs = int(u(64))
    font = ImageFont.truetype(FONT_BOLD, fs, index=0)
    draw.text((cx, cy - u(35)), "学记", font=font, fill=INK, anchor="mm")
    draw.text((cx, cy + u(35)), "期货", font=font, fill=INK, anchor="mm")


def render_full(size, scale=1.20):
    s = BASE * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    bg = background(s)
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * 0.215), fill=255)
    img.paste(bg, (0, 0), mask)
    draw_content(ImageDraw.Draw(img), s, scale)
    return img.resize((size, size), Image.LANCZOS)


def render_fg(size):
    s = BASE * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw_content(ImageDraw.Draw(img), s, 1.0)
    return img.resize((size, size), Image.LANCZOS)


def main():
    for name, mul in DENSITIES:
        d = RES / f"mipmap-{name}"
        d.mkdir(parents=True, exist_ok=True)
        legacy = int(round(48 * mul))
        adaptive = int(round(108 * mul))
        render_full(legacy).save(d / "ic_launcher.png", optimize=True)
        render_fg(adaptive).save(d / "ic_launcher_fg.png", optimize=True)
        print(f"{name}: ic_launcher {legacy}px, ic_launcher_fg {adaptive}px")



if __name__ == "__main__":
    main()
