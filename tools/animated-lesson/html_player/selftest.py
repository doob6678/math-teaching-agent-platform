# -*- coding: utf-8 -*-
"""HTML 播放器自动化自测（playwright chromium 无头）。

覆盖：真实点击流推进、键盘←→、章节跳转、双课程切换、trace_move 几何正确性
（垂线段最短步结束后 P.x≈-4）、板书/标签/结论卡/KaTeX DOM 断言、关键步骤截图。
运行：python selftest.py（需 8791 静态服务在线）
"""
import sys
import time
from pathlib import Path
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:8791/html_player/index.html"
SHOTS = Path(__file__).resolve().parent / "shots"
SHOTS.mkdir(exist_ok=True)

errors = []


def wait_idle(page, timeout=20.0):
    """等待当前动画播完（player 内部 busy 标志）。"""
    t0 = time.time()
    while time.time() - t0 < timeout:
        if not page.evaluate("window.__player.busy"):
            return
        page.wait_for_timeout(80)
    raise TimeoutError("player busy timeout")


def advance_to(page, target):
    while page.evaluate("window.__player.cur") < target:
        page.click("#btn-next")
        wait_idle(page)


def snap(page, name):
    page.screenshot(path=str(SHOTS / f"{name}.png"))
    print(f"  shot: {name}.png")


def main():
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1280, "height": 800})
        page.on("pageerror", lambda e: errors.append(f"pageerror: {e}"))
        page.on("console", lambda m: errors.append(f"console.error: {m.text}")
                if m.type == "error" else None)

        # ---------- 课程 1：tangent-min（33 步 7 章）----------
        print("[tangent-min] load")
        page.goto(BASE + "#tangent-min")
        page.wait_for_timeout(700)
        assert page.evaluate("window.__player.total") == 33
        assert page.evaluate("window.__player.lessonId") == "tangent-min"
        snap(page, "tm_00_start")

        advance_to(page, 11)  # ch1 结束（12 步）：圆/点/切线/标签/字幕/板书
        snap(page, "tm_01_ch1_done")
        n_notes = page.evaluate("document.querySelectorAll('.note-line').length")
        n_labs = page.evaluate("document.querySelectorAll('.lab.show').length")
        print(f"  cur=11 notes={n_notes} labels={n_labs}")
        assert n_notes >= 1 and n_labs >= 3

        advance_to(page, 26)  # ch5 两段 trace_move 结束（P 回到 -2.5）
        snap(page, "tm_02_after_trace")

        advance_to(page, 28)  # ch6 垂线段 trace：P 应停在 x=-4 正下方
        snap(page, "tm_03_foot_min")
        p_x = page.evaluate("""() => {
            const svg=document.getElementById('board');
            const blue=[...svg.querySelectorAll('circle[r="5"]')]
                .filter(d=>d.getAttribute('fill')==='#4D6BFE');
            // P 在 x 轴上：px 空间 cy 最大的蓝点（A 在上方）
            const axis=blue.reduce((a,b)=> +a.getAttribute('cy')> +b.getAttribute('cy')?a:b);
            const view=window.LESSONS['tangent-min'].view;
            const W=1000,H=720,PAD=46;
            const dx=view.x[1]-view.x[0], dy=view.y[1]-view.y[0];
            const s=Math.min((W-2*PAD)/dx,(H-2*PAD)/dy);
            const ox=(W-dx*s)/2;
            return (+axis.getAttribute('cx')-ox)/s+view.x[0];
        }""")
        print(f"  P.x after foot trace = {p_x:.3f} (expect ≈ -4)")
        assert abs(p_x + 4) < 0.05, "trace_move 终点几何错误"

        # 键盘←回退 / →推进
        page.keyboard.press("ArrowLeft")
        page.wait_for_timeout(300)
        assert page.evaluate("window.__player.cur") == 27, "键盘←回退失败"
        page.keyboard.press("ArrowRight")
        wait_idle(page)
        assert page.evaluate("window.__player.cur") == 28, "键盘→推进失败"

        # 章节跳转：点第 1 章回到开头
        page.click("#chapters .chap:nth-child(1)")
        page.wait_for_timeout(400)
        assert page.evaluate("window.__player.cur") == 0, "章节跳转失败"

        # 直达末尾：结论卡 + 板书累积 + KaTeX
        page.evaluate("window.__player.rebuildTo(32)")
        page.wait_for_timeout(400)
        snap(page, "tm_04_answer")
        assert page.evaluate("document.getElementById('answer-card').classList.contains('pop')")
        assert page.evaluate("document.querySelectorAll('.note-line').length") >= 5
        assert page.evaluate("document.querySelectorAll('.katex').length") >= 8, "KaTeX 未渲染"

        # ---------- 课程 2：tangent-angle（29 步 5 章）----------
        print("[tangent-angle] load")
        page.goto(BASE + "#tangent-angle")
        page.reload()  # 同页仅换 hash 不会重载，强制刷新走真实启动路径
        page.wait_for_timeout(700)
        assert page.evaluate("window.__player.total") == 29
        advance_to(page, 9)   # ch1 结束：两切线+角弧+α+选项板书
        snap(page, "ta_01_ch1")
        advance_to(page, 28)  # 到 answer
        snap(page, "ta_02_answer")
        assert page.evaluate("document.getElementById('answer-card').classList.contains('pop')")

        # 点击画面推进
        page.evaluate("window.__player.rebuildTo(1)")
        page.click("#canvas-wrap", position={"x": 300, "y": 400})
        wait_idle(page)
        assert page.evaluate("window.__player.cur") == 2, "点击画面推进失败"

        # 动画中点击 = 完成当前步（不跳步）
        page.evaluate("window.__player.rebuildTo(1)")
        page.click("#btn-next")   # 触发 draw 动画
        page.wait_for_timeout(150)
        page.click("#btn-next")   # snap 当前动画
        page.wait_for_timeout(200)
        assert page.evaluate("window.__player.cur") == 2, "动画中点击应只完成当前步"

        # tab 切换回课程 1
        page.click("#lesson-tabs .tab:nth-child(1)")
        page.wait_for_timeout(500)
        assert page.evaluate("window.__player.lessonId") == "tangent-min"
        assert page.evaluate("window.__player.cur") == 0

        browser.close()

    print("\nERRORS:" if errors else "\nno console/page errors")
    for e in errors:
        print(" ", e)
    print("SELFTEST", "FAIL" if errors else "PASS")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
