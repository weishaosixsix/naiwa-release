# -*- coding: utf-8 -*-
"""从一张方形原图生成 Android 全套启动图标。

用法：python tools/make_icons.py <方形原图> <res 目录>

原来自适应图标走的是「前景缩到 82% + 纯色背景兜底」，为的是让带木框的
标志无论圆形、方形还是 squircle 都完整可见。

2026-09-23 图标换成了一张动画截图，不是带透明背景的标志，这套做法就不合适了：
截图四边颜色本来就不同（左边雾气偏白、上面岩石偏青、下面还有橙色马甲），
挑不出一个不打架的兜底色，硬用会在图周围露出一圈人造色边。改成把整张图当
**背景层铺满**、前景留空 —— 启动器裁切时切掉的是图本身的四角。

代价：圆形遮罩会切掉四角（满幅图标的固有代价），也不再是纯色描边。
要回到原来的样式：把 ic_launcher.xml 的前景指回带留白的前景图
（本文件旧版生成的那套）、背景指回 @color/icon_background。
"""
import os
import sys
from PIL import Image

SRC = sys.argv[1]
RES = sys.argv[2]

# 非正方原图裁成正方形时的水平取景比例。0.5 = 居中。
# 截图里人物偏右，0.62 能让头部落在中间。
CROP_ANCHOR = 0.62

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# 自适应图标画布（108dp 基准）
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def to_square(img):
    side = min(img.width, img.height)
    if img.width == side:
        left = 0
    else:
        left = int((img.width - side) * CROP_ANCHOR)
        left = max(0, min(left, img.width - side))
    top = max(0, (img.height - side) // 2)
    return img.crop((left, top, left + side, top + side))


def main():
    src = to_square(Image.open(SRC).convert("RGBA"))
    print("方形源图:", src.size)

    for dpi, size in LEGACY.items():
        out = os.path.join(RES, "mipmap-" + dpi, "ic_launcher.png")
        src.resize((size, size), Image.LANCZOS).save(out, optimize=True)
        print("legacy    %-8s %dpx" % (dpi, size))

    for dpi, size in ADAPTIVE.items():
        out = os.path.join(RES, "mipmap-" + dpi, "ic_launcher_background.png")
        src.resize((size, size), Image.LANCZOS).save(out, optimize=True)
        print("adaptive  %-8s %dpx" % (dpi, size))


if __name__ == "__main__":
    main()
