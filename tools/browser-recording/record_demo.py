#!/usr/bin/env python3
"""Playwright 录屏演示：自动化操作浏览器的全过程录成视频。

背景：ZCode 内置浏览器(IAB)只有截图没有录屏 API；要"看智能体操作浏览器"
用 Playwright 原生录像（context 级 record_video_dir）即可，本脚本是最小样例。
每步操作之间 sleep 0.8s，视频里能看清"移动->点击->输入->回车"的动作轨迹。

产物: bing_demo.webm（context.close() 时才落盘）; 再用 ffmpeg 转 mp4 方便直接双击播放。
    python record_demo.py
    ffmpeg -i bing_demo.webm -c:v libx264 -pix_fmt yuv420p bing_demo.mp4
"""
import time
from pathlib import Path

from playwright.sync_api import sync_playwright

HERE = Path(__file__).resolve().parent


def main() -> None:
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        # 录像挂在 context 上；size 即输出分辨率，codecs 默认 vp8/webm
        ctx = browser.new_context(
            viewport={"width": 1280, "height": 720},
            record_video_dir=str(HERE),
            record_video_size={"width": 1280, "height": 720},
        )
        page = ctx.new_page()

        page.goto("https://www.bing.com", wait_until="domcontentloaded", timeout=60000)
        time.sleep(0.8)
        box = page.locator("input#sb_form_q")
        box.click()
        box.type("python playwright video recording", delay=45)  # delay 让打字逐字可见
        time.sleep(0.5)
        page.keyboard.press("Enter")
        page.wait_for_selector("#b_results", timeout=30000)
        time.sleep(1.2)
        page.locator("#b_results h2 a").first.click()
        page.wait_for_load_state("domcontentloaded")
        time.sleep(1.5)

        video_path = page.video.path()
        ctx.close()  # 必须关 context 视频才写完
        browser.close()
        print(f"录屏完成: {video_path}")


if __name__ == "__main__":
    main()
