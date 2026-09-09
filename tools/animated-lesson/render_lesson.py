# -*- coding: utf-8 -*-
"""一题一课渲染流水线：分镜 JSON → 配音 → 时长对齐 → Manim 渲染 → 音轨/章节封装。

用法：  .venv/Scripts/python.exe render_lesson.py lessons/tangent-min.json
产物：  out/<lesson_id>/final.mp4（带中文章节标记+配音）、chapters.json、timing.json、narration/*.mp3

流程设计（对齐调研结论 docs/animated-lesson-research-20260908.md）：
1. 每章旁白 edge-tts 合成 → ffprobe 实测时长 → 作为该章动画目标时长（MathLens 纪律：
   不硬编码秒数，音长驱动画面节奏）；
2. 生成绑定分镜的 Scene 文件 → manim 1080p30 渲染 + --save_sections（章节分段即时间戳权威）；
3. 读 sections JSON 拿实际段长 → 按段起点 adelay 拼接旁白 → 混流；
4. ffmetadata 封装 mp4 内嵌章节（前端导航另读 chapters.json，不依赖浏览器原生章节 UI）。
edge-tts 失败自动降级为无声版（时长回退基准值），保证原型交付率。
"""
from __future__ import annotations

import asyncio
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PY = sys.executable
VOICE = os.environ.get("LESSON_VOICE", "zh-CN-XiaoxiaoNeural")


def run(cmd, **kw):
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                       errors="replace", env=env, **kw)
    return r


def ffprobe_duration(path):
    r = run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "csv=p=0", path])
    return float(r.stdout.strip())


def tts(text, out_path):
    import edge_tts
    async def _go():
        await edge_tts.Communicate(text, VOICE).save(out_path)
    asyncio.run(_go())


def main(lesson_path):
    lesson_path = os.path.abspath(lesson_path)
    with open(lesson_path, encoding="utf-8") as f:
        sb = json.load(f)
    lid = sb["id"]
    # LESSON_OUT_DIR：主链路（ai-worker animated_lesson workload）指定产物根目录；
    # 不设时保持原型行为，写在本目录 out/ 下。
    out_root = os.environ.get("LESSON_OUT_DIR") or os.path.join(HERE, "out")
    out = os.path.join(out_root, lid)
    os.makedirs(os.path.join(out, "narration"), exist_ok=True)
    os.makedirs(os.path.join(out, "media"), exist_ok=True)

    # 1) 逐章配音 → 目标时长
    timing_path = os.path.join(out, "timing.json")
    timing = {}
    audio_files = []
    for ch in sb["chapters"]:
        mp3 = os.path.join(out, "narration", f"{ch['id']}.mp3")
        narration = ch.get("narration", "").strip()
        if narration:
            try:
                if not (os.path.exists(mp3) and os.path.getsize(mp3) > 1000):
                    tts(narration, mp3)  # 旁白缓存：改分镜文字后需删 out/<id>/narration 重合成
                timing[ch["id"]] = ffprobe_duration(mp3) + 0.8  # 0.8s 呼吸余量
                audio_files.append((ch["id"], mp3))
                print(f"[tts] {ch['id']} {timing[ch['id']]:.1f}s")
                continue
            except Exception as e:
                print(f"[tts] {ch['id']} 失败，降级无声: {e}")
        timing[ch["id"]] = None
    with open(timing_path, "w", encoding="utf-8") as f:
        json.dump({k: v for k, v in timing.items() if v}, f, ensure_ascii=False)

    # 2) 生成场景文件并渲染
    scene = os.path.join(out, "scene.py")
    with open(scene, "w", encoding="utf-8") as f:
        f.write(
            "# 自动生成：绑定分镜的 Lesson 场景（勿手改，改分镜 JSON）\n"
            "import sys\n"
            f"sys.path.insert(0, {HERE!r})\n"
            "from lesson_runtime import make_lesson_scene\n"
            "# 本地 class 声明：manim 只收集 __module__ 属于场景文件本身的 Scene 子类\n"
            f"class Lesson(make_lesson_scene({lesson_path!r}, {timing_path!r})):\n"
            "    pass\n"
        )
    media = os.path.join(out, "media")
    print("[render] manim 渲染中（1080p30）...")
    r = run([PY, "-m", "manim", "render", "-r", "1920,1080", "--fps", "30",
             "--save_sections", "--media_dir", media, scene, "Lesson"], cwd=HERE)
    if r.returncode != 0:
        print(r.stdout[-4000:])
        print(r.stderr[-4000:])
        sys.exit(f"[render] 失败 rc={r.returncode}")

    # 3) 定位产物：视频 + sections JSON
    vdir = os.path.join(media, "videos", "scene", "1080p30")
    video = sec_json = None
    for root, _dirs, files in os.walk(vdir):
        for fn in files:
            if root != vdir and fn.endswith(".json"):
                sec_json = os.path.join(root, fn)  # 0.21 起清单为 sections/<场景名>.json
            elif root == vdir and fn.endswith(".mp4"):
                video = os.path.join(root, fn)  # 产物名=场景类名 Lesson.mp4，非文件名
    if not os.path.exists(video):
        sys.exit("[render] 找不到输出视频")

    chapters_meta = []
    t = 0.0
    if sec_json:
        with open(sec_json, encoding="utf-8") as f:
            secs = [s for s in json.load(f) if s.get("name")]  # type=default.normal
        for s in secs:
            dur = float(s["duration"])
            chapters_meta.append({"id": s["name"], "start": round(t, 3),
                                  "duration": round(dur, 3)})
            t += dur
    total = t or ffprobe_duration(video)

    # 4) 旁白按实际段起点混流（adelay 对齐，段内起点用渲染实测值）
    id2start = {c["id"]: c["start"] for c in chapters_meta}
    cmd = ["ffmpeg", "-y", "-i", video]
    for i, (cid, mp3) in enumerate(audio_files, 1):
        cmd += ["-i", mp3]
    fc = []
    for i, (cid, _mp3) in enumerate(audio_files, 1):
        ms = int(id2start.get(cid, 0) * 1000)
        fc.append(f"[{i}:a]adelay={ms}|{ms},volume=1.0[a{i}]")
    if fc:
        mix = "".join(f"[a{i}]" for i in range(1, len(audio_files) + 1))
        fc.append(f"{mix}amix=inputs={len(audio_files)}:normalize=0:duration=longest[aout]")
        cmd += ["-filter_complex", ";".join(fc), "-map", "0:v", "-map", "[aout]",
                "-c:v", "copy", "-c:a", "aac", "-shortest"]
    else:
        cmd += ["-c", "copy"]
    mixed = os.path.join(out, "mixed.mp4")
    cmd.append(mixed)  # 输出文件必须在 argv 末尾，否则 filtergraph 输出无人连接
    r = run(cmd)
    if r.returncode != 0:
        with open(os.path.join(out, "mux_cmd.json"), "w", encoding="utf-8") as f:
            json.dump(cmd, f, ensure_ascii=False)
        print("CMD:", " ".join(cmd))
        print(r.stderr[-3000:])
        sys.exit("[mux] 混流失败")

    # 5) ffmetadata 中文章节封装
    meta = os.path.join(out, "chapters.txt")
    with open(meta, "w", encoding="utf-8") as f:
        f.write(";FFMETADATA1\n")
        idx = 0
        for c, ch in zip(chapters_meta, sb["chapters"]):
            idx += 1
            f.write("[CHAPTER]\nTIMEBASE=1/1000\nSTART=%d\nEND=%d\ntitle=%s %s\n"
                    % (int(c["start"] * 1000), int((c["start"] + c["duration"]) * 1000),
                       idx, ch["title"]))
    final = os.path.join(out, "final.mp4")
    r = run(["ffmpeg", "-y", "-i", mixed, "-i", meta, "-map_metadata", "1",
             "-codec", "copy", final])
    if r.returncode != 0:
        print(r.stderr[-2000:]); sys.exit("[chapters] 封装失败")

    # 6) 交付清单：chapters.json（前端导航）+ 分镜快照
    ch_list = []
    for c, ch in zip(chapters_meta, sb["chapters"]):
        ch_list.append({"id": c["id"], "start": c["start"], "duration": c["duration"],
                        "title": ch["title"], "subtitle": ch.get("subtitle", "")})
    with open(os.path.join(out, "chapters.json"), "w", encoding="utf-8") as f:
        json.dump({"id": lid, "title": sb["title"], "total": round(total, 2),
                   "problem": sb["problem"], "chapters": ch_list}, f, ensure_ascii=False, indent=1)
    import shutil
    shutil.copy(lesson_path, os.path.join(out, "lesson.json"))
    print(f"[done] {final}  时长 {total:.1f}s  章节 {len(ch_list)}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("用法: render_lesson.py lessons/<id>.json")
    main(sys.argv[1])
