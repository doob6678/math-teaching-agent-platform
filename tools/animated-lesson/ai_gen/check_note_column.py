# -*- coding: utf-8 -*-
"""结论帧自动体检：笔记本列区（x 0.95..6.95）内，题干卡顶以上不应有文字残留
（09-09 实拍缺陷：滚动后旧行从卡顶上方露半截），且底边带不应有字（字幕钳制哨兵）。
用法：python ai_gen/check_note_column.py <png> [png...]
场景坐标→像素：1080p 下 x_px=(x+7.11)/14.22*1920, y_px=(2.8125-y)/5.625*1080（frame 高 8 但相机 16:9 裁切按 manim 默认 frame_height=8 映射 y∈[-4,4]→px[1080,0] 即 y_px=(4-y)/8*1080）。
卡顶按列区最高灰框行自动定位：找列区内第一条长横边（灰框线）。"""
import sys
from PIL import Image

W, H = 1920, 1080
X0, X1 = int((0.95 + 7.11) / 14.22 * W), int((6.95 + 7.11) / 14.22 * W)
Y_CAP = int((4 - (-3.25)) / 8 * H)   # 字幕带顶界（caption 中心 -3.62，留余量）


def dark(px, x, y, th=120):
    """只认中性暗像素（墨色文字）。章节横幅是蓝底白字（B 通道远高于 R），
    09-09 扫描发现横幅出现瞬间会误报"卡顶残留"——按色度过滤掉。"""
    r, g, b = px[x, y]
    lum = 0.299 * r + 0.587 * g + 0.114 * b
    return lum < th and (max(r, g, b) - min(r, g, b)) < 45


def check(path: str) -> bool:
    im = Image.open(path).convert("RGB")
    px = im.load()
    # 卡顶定位：列区内从上往下第一条"连续灰框横线"（>40% 列宽的中灰像素）
    card_top = None
    for y in range(0, H // 2):
        def grayish(x):
            r, g, b = px[x, y]
            lum = 0.299 * r + 0.587 * g + 0.114 * b
            return 120 <= lum <= 200 and (max(r, g, b) - min(r, g, b)) < 45
        run = sum(1 for x in range(X0, X1, 4) if grayish(x))
        if run > (X1 - X0) // 4 * 0.5:
            card_top = y
            break
    issues = []
    if card_top is not None:
        # 卡顶以上 60px：不应有文字（暗像素团）
        stray = sum(1 for x in range(X0, X1, 3) for y in range(max(0, card_top - 60), card_top - 4)
                    if dark(px, x, y))
        if stray > 30:
            issues.append(f"卡顶上方残留暗像素 {stray}（滚动旧行露出）")
    # 底部字幕带以下：不应有第二层文字（结论框压字幕哨兵）
    bottom = sum(1 for x in range(X0, X1, 3) for y in range(H - 12, H)
                 if dark(px, x, y))
    if bottom > 20:
        issues.append(f"画面最底边暗像素 {bottom}（内容出框）")
    print(path, "OK" if not issues else "FAIL: " + "; ".join(issues))
    return not issues


if __name__ == "__main__":
    ok = all([check(p) for p in sys.argv[1:]])
    sys.exit(0 if ok else 1)
