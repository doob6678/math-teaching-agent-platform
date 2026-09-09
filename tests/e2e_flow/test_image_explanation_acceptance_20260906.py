# -*- coding: utf-8 -*-
"""2026-09-06 学生对话"图片讲解"链路浏览器验收（Playwright 真实 Chromium，全程录屏）。

覆盖老板验收脚本要求：
1) 登录 local-browser-acceptance → AI 讲题页（标题"有什么数学题可以帮你？"）；
2) 上传人教B版必修一第93页扫描图 p093.png，断言题图 chip 文案；
3) UI 修复断言：模型下拉视觉标记、带图自动标签"自动 · 视觉 openai/gpt-5.6-terra"、
   选 deepseek-v4-flash 时提示+禁发、改选 glm-5.3-flash 时提示消失可发；
4) 自动路由提交真实问题，轮询记录首个进度文案与答案首字时间，间隔采样证明流式增长；
5) 完成后断言答案含 297600/29.76万/蓄水池，且含"第 93 页"或教材来源引用。

设计说明（为什么这么做）：
- 断言用软收集（assertions 列表）+ 末尾统一 fail：失败时录屏仍覆盖全过程，
  报告里能同时看到通过项与失败证据截图，而不是第一个断言就抛错中断。
- 选择器全部对齐 TeachingConversationPanel.tsx 真实 DOM（aria-label/类名），
  不改应用代码；页面文本轮询用 body.innerText，编码交给 Playwright（UTF-8）无转义问题。
- 录屏 record_video_dir 原生支持 headless（ffmpeg 随 Playwright 安装），自起独立
  Chromium 实例，与老板任何已开窗口无关。

运行：python -m pytest tests/e2e_flow/test_image_explanation_acceptance_20260906.py -v -s
产物：tmp/acceptance_video/（录屏+report_20260906.json+截图 screens/）。
"""
from __future__ import annotations

import json
import time
from pathlib import Path

import pytest
from playwright.sync_api import sync_playwright

REPO_ROOT = Path(__file__).resolve().parents[2]
BASE_URL = "http://mathagent.local"
USERNAME = "local-browser-acceptance"
PASSWORD = "BrowserAcceptance2026!"
# 教材扫描页：processed_books/renjiao_bbixiu1math/pages/p093.png（含蓄水池最低造价题，答案 297600 元）。
P093_IMAGE = Path(
    r"C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main"
    r"\tchMaterial-parser-main\processed_books\renjiao_bbixiu1math\pages\p093.png"
)
ART_DIR = REPO_ROOT / "tmp" / "acceptance_video"
SCREEN_DIR = ART_DIR / "screens"
QUESTION = "这一页讲的是什么？蓄水池最低造价是多少？"
# 目录 /api/agents/model-catalog 实测（2026-09-06 curl 验证）：这五个带 vision=true，deepseek 两个均 false。
VISION_MODELS = ["gpt-5.6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.5", "glm-5.3-flash"]
NON_VISION_MODELS = ["deepseek-v4-flash"]
TOTAL_TIMEOUT_S = 300


def _shot(page, name: str) -> str:
    SCREEN_DIR.mkdir(parents=True, exist_ok=True)
    path = SCREEN_DIR / f"{name}.png"
    page.screenshot(path=str(path))
    return str(path)


def _answer_text(page) -> str:
    """当前会话最新一条助手消息的完整文本。

    流式中是 [aria-label="讲解内容"] 卡；完成后组件切换为 AssistantResponse
    （section.teaching-response-card.agent，无该 aria-label，见面板源码 325-331 行），
    因此统一取最后一条 .teaching-assistant-row 的 innerText，两种状态都覆盖。
    """
    rows = page.locator(".teaching-assistant-row")
    if rows.count() == 0:
        return ""
    return rows.last.inner_text() or ""


def _live_answer_text(page) -> str:
    """仅流式讲解卡文本；用于"答案首字"判定（卡存在即首字已渲染）。"""
    loc = page.locator('[aria-label="讲解内容"]')
    if loc.count() == 0:
        return ""
    return loc.last.inner_text() or ""


def test_image_explanation_acceptance() -> None:
    assert P093_IMAGE.exists(), f"测试图缺失: {P093_IMAGE}"
    ART_DIR.mkdir(parents=True, exist_ok=True)
    assertions: list[dict] = []
    console_errors: list[str] = []
    http_errors: list[str] = []
    timings: dict = {}
    screenshots: dict[str, str] = {}
    # finally 块会写报告，所有中间变量必须先初始化，避免早退时 UnboundLocalError 掩盖真实异常。
    stream_samples: list[dict] = []
    first_progress_ts: float | None = None
    first_answer_ts: float | None = None
    fatal_error: str | None = None

    def check(name: str, passed: bool, evidence: str) -> None:
        assertions.append({"name": name, "passed": bool(passed), "evidence": evidence})

    with sync_playwright() as p:
        # 自起独立 Chromium（headless=new），record_video_dir 原生录屏，1440x900。
        browser = p.chromium.launch()
        context = browser.new_context(
            viewport={"width": 1440, "height": 900},
            record_video_dir=str(ART_DIR),
            record_video_size={"width": 1440, "height": 900},
        )
        page = context.new_page()
        page.on(
            "console",
            lambda m: console_errors.append(m.text[:300]) if m.type == "error" else None,
        )
        page.on(
            "response",
            lambda r: http_errors.append(f"{r.status} {r.url}") if r.status >= 400 else None,
        )
        try:
            # ---------- 1. 打开首页并登录 ----------
            page.goto(BASE_URL, wait_until="networkidle", timeout=60_000)
            check("站点可达", "Math Agent" in page.title() or page.locator(".top-nav").count() > 0,
                  f"title={page.title()!r}")
            # 未登录时头像下拉里有"登录账号"；若会话已存在（不应发生）直接跳过。
            page.locator("button.nav-avatar").click()
            login_entry = page.get_by_text("登录账号", exact=True)
            login_entry.first.wait_for(state="visible", timeout=10_000)
            login_entry.first.click()
            page.locator('input[placeholder="输入后端账号"]').wait_for(state="visible", timeout=15_000)
            page.fill('input[placeholder="输入后端账号"]', USERNAME)
            page.fill('input[placeholder="输入真实密码"]', PASSWORD)
            page.locator('form button.btn.btn-primary[type="submit"]').click()
            # 成功后 App 会 navigate("dashboard")，登录表单被卸载；用"表单消失"判定，
            # 再用头像下拉里的真实 userId（local-acceptance-…）复核，避免误判文案。
            page.wait_for_selector('input[placeholder="输入后端账号"]', state="detached", timeout=20_000)
            page.locator("button.nav-avatar").click()
            # 下拉有 CSS 过渡，刚点开时 innerText 可能为空；轮询 10s 再判定，避免误报。
            header_name = ""
            for _ in range(20):
                header_name = page.locator(".dropdown-header-name").first.inner_text()
                if header_name.strip():
                    break
                page.wait_for_timeout(500)
            login_ok = "local-acceptance" in header_name or "local-browser-acceptance" in header_name
            screenshots["login_ok"] = _shot(page, "01_login_ok")
            check("登录成功", login_ok, f"dropdown-header-name={header_name!r}")
            page.keyboard.press("Escape")

            # ---------- 2. 进入学生对话页 ----------
            page.locator('button.nav-link[aria-label="AI 讲题"]').click()
            h1 = page.locator("h1", has_text="有什么数学题可以帮你？")
            h1.wait_for(state="visible", timeout=20_000)
            header_ok = all(
                page.get_by_text(t, exact=False).count() > 0 for t in ["发题目", "贴题图", "继续追问"]
            )
            check("对话页标题与引导按钮", header_ok, "h1+发题目/贴题图/继续追问")
            screenshots["chat_home"] = _shot(page, "02_chat_home")

            # ---------- 3. 上传题图并断言 chip ----------
            page.set_input_files('form.teaching-live-composer input[type="file"]', str(P093_IMAGE))
            chip = page.get_by_text("题图已上传，发送后原图直接进入 AI 上下文", exact=False)
            chip_first = chip.first
            chip_first.wait_for(state="visible", timeout=60_000)
            check("题图上传 chip 文案", True, "题图已上传，发送后原图直接进入 AI 上下文")
            screenshots["upload_chip"] = _shot(page, "03_upload_chip")

            # ---------- 4a. 模型下拉视觉标记 ----------
            trigger = page.locator('button[aria-label="选择讲解模型"]')

            # 组件源码（TeachingConversationPanel 1329-1336）只监听外部 mousedown 关闭，
            # Escape 无效；trigger 在 rootRef 内，直接再点会 toggle 回关闭态。
            # 因此按 aria-expanded 判断真实开合，保证幂等。
            def open_menu() -> "object":
                if trigger.get_attribute("aria-expanded") != "true":
                    trigger.click()
                menu_loc = page.locator('[role="listbox"]')
                menu_loc.wait_for(state="visible", timeout=10_000)
                return menu_loc

            def close_menu() -> None:
                if trigger.get_attribute("aria-expanded") == "true":
                    trigger.click()
                    page.wait_for_timeout(200)

            menu = open_menu()
            option_rows = {}
            for opt in menu.locator('button[role="option"]').all():
                code = (opt.locator("span").first.inner_text() or "").strip() if opt.locator("span").count() else ""
                badge = (opt.locator("small").first.inner_text() or "") if opt.locator("small").count() else ""
                if code:
                    option_rows[code] = badge
            screenshots["model_menu"] = _shot(page, "04_model_menu")
            for model in VISION_MODELS:
                badge = option_rows.get(model)
                check(f"{model} 带视觉标记", badge is not None and "视觉" in badge,
                      f"badge={badge!r}" if badge is not None else "菜单缺行")
            for model in NON_VISION_MODELS:
                badge = option_rows.get(model, "<缺行>")
                check(f"{model} 不带视觉标记", "视觉" not in badge, f"badge={badge!r}")
            close_menu()

            # ---------- 4b. 带图自动标签 ----------
            auto_label = (trigger.inner_text() or "").strip()
            check(
                "自动标签=视觉默认",
                "自动 · 视觉 openai/gpt-5.6-terra" in auto_label,
                f"trigger={auto_label!r}",
            )
            screenshots["auto_label"] = _shot(page, "05_auto_label")

            # ---------- 4c. deepseek-v4-flash → 提示+禁发 ----------
            menu = open_menu()
            menu.locator('button[role="option"]', has_text="deepseek-v4-flash").first.click()
            hint = page.locator(".teaching-inline-hint", has_text="所选模型不支持图片输入")
            hint_visible = hint.count() > 0 and hint.first.is_visible()
            send_btn = page.locator("button.teaching-send-btn")
            disabled = send_btn.is_disabled()
            screenshots["deepseek_blocked"] = _shot(page, "06_deepseek_blocked")
            check("deepseek 不支持图片提示", hint_visible, f"hint count={hint.count()}")
            check("deepseek 时发送禁用", disabled, f"disabled={disabled}")

            # ---------- 4d. 改选 glm-5.3-flash → 提示消失可发 ----------
            menu = open_menu()
            menu.locator('button[role="option"]', has_text="glm-5.3-flash").first.click()
            page.wait_for_timeout(500)
            hint_gone = page.locator(".teaching-inline-hint", has_text="所选模型不支持图片输入").count() == 0
            send_enabled = not send_btn.is_disabled()
            check("glm 选中后提示消失", hint_gone, f"count={page.locator('.teaching-inline-hint').count()}")
            check("glm 选中后可发送", send_enabled, f"enabled={send_enabled}")

            # ---------- 5. 切回自动并提交问题 ----------
            menu = open_menu()
            menu.locator('button[role="option"]', has_text="自动 ·").first.click()
            page.fill('form.teaching-live-composer textarea', QUESTION)
            baseline = page.evaluate("() => document.body.innerText.length")
            t_submit = time.monotonic()
            send_btn.click()
            timings["submit_wall"] = time.strftime("%H:%M:%S")

            # ---------- 6. 实时进度轮询（首个进度文案 / 答案首字 / 流式采样） ----------
            # 进度文案：流式思考行 .teaching-thinking-live-text（"正在整理思路…"/阶段详情/推理尾句）；
            # 答案首字：[aria-label="讲解内容"] 卡出现即首字（前端逐字渲染，见 useCharacterRenderedText）；
            # 完成：思考行卸载 + 助手消息文本稳定 ≥4s + 发送按钮恢复。
            first_progress_ts = None
            first_answer_ts = None
            stream_samples = []
            final_text = ""
            stable_since = None
            deadline = t_submit + TOTAL_TIMEOUT_S
            next_shot_i = 0
            shot_plan = [5.0, 15.0, 30.0]  # 首字出现后这些秒数各截一张，证明逐字流式
            while time.monotonic() < deadline:
                now = time.monotonic()
                elapsed = now - t_submit
                if first_progress_ts is None:
                    live_text = page.locator(".teaching-thinking-live-text")
                    if live_text.count() > 0 and (live_text.first.inner_text() or "").strip():
                        first_progress_ts = elapsed
                    elif page.evaluate("() => document.body.innerText.length") > baseline + 4:
                        first_progress_ts = elapsed
                if first_answer_ts is None and len(_live_answer_text(page)) >= 2:
                    first_answer_ts = elapsed
                text = _answer_text(page)
                if (
                    next_shot_i < len(shot_plan)
                    and elapsed >= shot_plan[next_shot_i]
                    and first_answer_ts is not None
                ):
                    name = f"07_stream_t{int(elapsed)}s"
                    stream_samples.append(
                        {"t_s": round(elapsed, 1), "answer_len": len(text), "screenshot": _shot(page, name)}
                    )
                    next_shot_i += 1
                # 完成信号不能用"发送按钮可用"：提交后输入框清空，按钮按空输入规则
                # 永久禁用，直到用户再次输入。真实完成信号=流式思考行卸载+文本稳定。
                live_row_gone = page.locator(".teaching-thinking-row.live").count() == 0
                if text and first_answer_ts is not None:
                    if final_text == text:
                        if stable_since is None:
                            stable_since = now
                        elif now - stable_since >= 4 and live_row_gone:
                            break
                    else:
                        stable_since = None
                    final_text = text
                page.wait_for_timeout(1000)
            final_text = _answer_text(page) or final_text
            timings["first_progress_s"] = round(first_progress_ts, 2) if first_progress_ts is not None else None
            timings["first_answer_char_s"] = round(first_answer_ts, 2) if first_answer_ts is not None else None
            timings["total_wait_s"] = round(time.monotonic() - t_submit, 1)

            check("首个进度文案出现", first_progress_ts is not None,
                  f"t={first_progress_ts}s")
            check("答案首字出现", first_answer_ts is not None, f"t={first_answer_ts}s")
            lens = [s["answer_len"] for s in stream_samples]
            grew = len(lens) >= 2 and all(b >= a for a, b in zip(lens, lens[1:])) and lens[-1] > lens[0]
            check("流式增长（采样答案长度递增）", grew, f"samples={stream_samples}")

            # 服务端自报的首字耗时 chip（"首字 xxx"），作为页面侧观测量一并留证。
            speed_chip = page.locator(".teaching-speed-chip", has_text="首字")
            timings["server_first_token_chip"] = (
                speed_chip.first.inner_text() if speed_chip.count() else None
            )

            # ---------- 7. 完成后断言内容 ----------
            body_text = page.evaluate("() => document.body.innerText")
            answer_ok = any(k in final_text for k in ["297600", "29.76万", "蓄水池"])
            check("答案含 297600/29.76万/蓄水池", answer_ok,
                  f"answer_tail={final_text[-120:]!r}")
            # 来源引用：答案卡 + 检索详情面板 + 会话标题栏。注意 p093.png 的 PDF 页码=93、
            # 印刷页码=86（见 markdown/pages/p093.md 头），AI 引用印刷页码"第86页"同样是
            # 真实教材页引用，两种都接受；用户气泡的文件名 chip 不算来源引用。
            # 另外 AI 可能判定"题目自洽，本轮不执行检索"（workflowStages react_decision），
            # 此时 sources 为空，页面引用只可能来自 AI 正文/标题。
            inspector_text = ""
            insp = page.locator(".teaching-evidence-inspector, .teaching-thinking-panel")
            for i in range(insp.count()):
                inspector_text += insp.nth(i).text_content() or ""
            page_ref_keys = ["第 93 页", "第93页", "93 页", "第 86 页", "第86页", "86 页", "人教B版", "必修一"]
            ref_text = final_text + inspector_text + body_text
            source_ok = any(k in ref_text for k in page_ref_keys)
            check("出现第93页/教材来源引用", source_ok,
                  f"refs={[k for k in page_ref_keys if k in ref_text]}")
            screenshots["final"] = _shot(page, "08_final_answer")
        except Exception as exc:  # 任何早退都要留下现场截图与异常文本，报告不掩盖失败
            fatal_error = f"{type(exc).__name__}: {exc}"
            try:
                screenshots["fatal"] = _shot(page, "99_fatal")
            except Exception:
                pass
            raise
        finally:
            context.close()  # 关闭才会落盘 webm
            video_path = page.video.path() if page.video else None
            browser.close()
            report = {
                "run_date": "2026-09-06",
                "base_url": BASE_URL,
                "question": QUESTION,
                "image": str(P093_IMAGE),
                "video": video_path,
                "timings": timings,
                "assertions": assertions,
                "screenshots": {**screenshots, "stream_samples": stream_samples},
                "console_errors": console_errors[:20],
                "http_errors": http_errors[:20],
                "fatal_error": fatal_error,
                "passed": fatal_error is None and all(a["passed"] for a in assertions),
            }
            (ART_DIR / "report_20260906.json").write_text(
                json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
            )
            print("\n=== 图片讲解浏览器验收报告 ===")
            print(json.dumps(report, ensure_ascii=False, indent=2))

    failed = [a["name"] for a in assertions if not a["passed"]]
    assert not failed, f"验收失败项: {failed}（详见 {ART_DIR / 'report_20260906.json'}，录屏 {video_path}）"


if __name__ == "__main__":
    # 直接调用而非 pytest.main：仓库根有 Linux 遗留符号链接（lib64/bin 等），
    # Windows 下 pytest 收集阶段 stat 这些链接会抛 WinError 1920 中断整个会话。
    try:
        test_image_explanation_acceptance()
    except AssertionError as exc:
        print(f"ACCEPTANCE FAILED: {exc}")
        raise SystemExit(1)
    print("ACCEPTANCE PASSED")
