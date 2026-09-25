# -*- coding: utf-8 -*-
"""生成应用图标源图（1024 方形），再交给 make_icons.py 出全套尺寸。

设计：青蓝渐变底 + 白色鱼形剪影，后面叠两条淡色的同形鱼表示"多开"。
刻意不涉及任何受版权保护的角色形象 —— 图标是自绘的抽象图形。

用法：python tools/make_icon_source.py tools/icon-source.png
"""
import sys
from PIL import Image, ImageDraw

OUT = sys.argv[1] if len(sys.argv) > 1 else "tools/icon-source.png"
S = 1024

# 背景渐变：上深下亮，同色系，避免深色图标在浅色桌面上发闷
TOP = (16, 104, 138)
BOT = (38, 182, 172)


def gradient(size):
    img = Image.new("RGB", (size, size))
    for y in range(size):
        t = y / (size - 1)
        row = tuple(round(TOP[i] + (BOT[i] - TOP[i]) * t) for i in range(3))
        img.paste(row, (0, y, size, y + 1))
    return img


def fish(draw, cx, cy, w, h, color):
    """一条朝右的鱼：椭圆身体 + 三角尾。"""
    draw.ellipse([cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2], fill=color)
    tw = w * 0.42
    draw.polygon(
        [(cx + w / 2 - w * 0.06, cy),
         (cx + w / 2 + tw, cy - h * 0.52),
         (cx + w / 2 + tw, cy + h * 0.52)],
        fill=color,
    )


def main():
    img = gradient(S)
    d = ImageDraw.Draw(img, "RGBA")

    # 后面两条淡色的鱼，错开位置 —— 表达"多"，同时也是层次
    fish(d, S * 0.40, S * 0.30, S * 0.36, S * 0.20, (255, 255, 255, 58))
    fish(d, S * 0.36, S * 0.70, S * 0.36, S * 0.20, (255, 255, 255, 44))

    # 主鱼
    body_w, body_h = S * 0.50, S * 0.30
    cx, cy = S * 0.46, S * 0.50
    fish(d, cx, cy, body_w, body_h, (255, 255, 255, 255))

    # 眼睛（用背景色挖出来，比画黑点更透气）
    r = S * 0.024
    ex = cx - body_w * 0.30
    ey = cy - body_h * 0.16
    d.ellipse([ex - r, ey - r, ex + r, ey + r], fill=TOP + (255,))

    img.save(OUT, optimize=True)
    print("已生成", OUT, img.size)


if __name__ == "__main__":
    main()
