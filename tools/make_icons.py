# -*- coding: utf-8 -*-
"""从一张方形原图生成 Android 全套启动图标。

自适应图标的前景不能铺满画布：外圈 18/108 会被各家启动器的形状裁掉。
所以前景按 SAFE 比例缩放后居中，背景用木框主色兜底，这样无论圆形、
方形还是 squircle，木框都完整可见。
"""
import os
import sys
from PIL import Image

SRC = sys.argv[1]
RES = sys.argv[2]

# 前景在 108dp 画布里的占比。72/108=0.667 绝对安全但太小，
# 0.82 能让 squircle 几乎完整显示，圆形也只削掉一点木框转角。
SAFE = 0.82

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
FOREGROUND = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def main():
    src = Image.open(SRC).convert("RGBA")
    if src.width != src.height:
        side = min(src.width, src.height)
        left = (src.width - side) // 2
        top = (src.height - side) // 2
        src = src.crop((left, top, left + side, top + side))

    for dpi, size in LEGACY.items():
        out = os.path.join(RES, "mipmap-" + dpi, "ic_launcher.png")
        src.resize((size, size), Image.LANCZOS).save(out, optimize=True)
        print("legacy    ", os.path.basename(os.path.dirname(out)), size)

    for dpi, size in FOREGROUND.items():
        inner = max(1, round(size * SAFE))
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        art = src.resize((inner, inner), Image.LANCZOS)
        off = (size - inner) // 2
        canvas.paste(art, (off, off), art)
        out = os.path.join(RES, "mipmap-" + dpi, "ic_launcher_foreground.png")
        canvas.save(out, optimize=True)
        print("foreground", os.path.basename(os.path.dirname(out)), size, "art", inner)


if __name__ == "__main__":
    main()
