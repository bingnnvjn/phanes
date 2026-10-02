#!/usr/bin/env bash
# 从 ic_launcher.svg / ic_launcher_round.svg 生成各密度启动器位图。
# 位图供 API < 26 使用；API 26+ 用 mipmap-anydpi-v26 的自适应图标（颜色取 drawable/ic_launcher_background.xml）。
# 依赖：python3 + cairosvg。
set -euo pipefail

cd "$(dirname "$0")"

for density in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
    bucket="${density%%:*}"
    size="${density##*:}"
    folder="../app/src/main/res/mipmap-${bucket}"
    mkdir -p "$folder"

    for file in ic_launcher ic_launcher_round; do
        python3 -c "import cairosvg, sys; cairosvg.svg2png(url=sys.argv[1], write_to=sys.argv[2], output_width=int(sys.argv[3]), output_height=int(sys.argv[3]))" \
            "${file}.svg" "${folder}/${file}.png" "${size}"
    done
done

echo "launcher bitmaps written under ../app/src/main/res/mipmap-*"
