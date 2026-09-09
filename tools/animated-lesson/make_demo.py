# -*- coding: utf-8 -*-
"""生成 demo/index.html：一题一课播放器（视频+章节侧栏+结构化解析卡片）。

产物内联 chapters.json（file:// 下 fetch 不可用），视频走相对路径。
布局对标竞品截图：左播放器、右"视频章节"快速定位、下方"结构化解析"四卡。
配色沿用平台规范（主色 #4D6BFE，安静聚焦风，无蓝色高亮聚焦框）。
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
DEMO = os.path.join(HERE, "demo")

LESSON_ORDER = ["tangent-min", "two-circle-tangents", "tangent-angle", "ellipse-moving-line"]


def fmt(t):
    return f"{int(t // 60):02d}:{t % 60:04.1f}"


def build():
    lessons = []
    for lid in LESSON_ORDER:
        cp = os.path.join(OUT, lid, "chapters.json")
        vp = os.path.join(OUT, lid, "final.mp4")
        lp = os.path.join(HERE, "lessons", lid + ".json")
        if not (os.path.exists(cp) and os.path.exists(vp)):
            continue
        with open(cp, encoding="utf-8") as f:
            ch = json.load(f)
        # problem 以分镜源文件为单一事实源（LaTeX 源文本，卡片 KaTeX 渲染）
        if os.path.exists(lp):
            with open(lp, encoding="utf-8") as f:
                ch["problem"] = json.load(f)["problem"]
        lessons.append(ch)
    data = json.dumps(lessons, ensure_ascii=False)
    html = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>一题一课 · 分步动画讲解原型</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css">
<script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"></script>
<style>
  :root { --ink:#1F2937; --sub:#6B7280; --line:#E5E7EB; --primary:#4D6BFE; --paper:#FAFAF8; }
  * { box-sizing:border-box; margin:0; padding:0; }
  body { font-family:"Microsoft YaHei","PingFang SC",sans-serif; background:var(--paper); color:var(--ink); }
  header { padding:18px 28px 10px; }
  header h1 { font-size:19px; font-weight:600; }
  header p { font-size:12.5px; color:var(--sub); margin-top:3px; }
  .tabs { display:flex; gap:8px; padding:6px 28px 14px; flex-wrap:wrap; }
  .tab { border:1px solid var(--line); background:#fff; color:var(--sub); font-size:13px;
         padding:7px 14px; border-radius:8px; cursor:pointer; }
  .tab.on { color:var(--primary); border-color:var(--primary); background:#F0F3FF; font-weight:600; }
  .main { display:grid; grid-template-columns:minmax(0,1fr) 300px; gap:18px; padding:0 28px; }
  .player { background:#000; border-radius:12px; overflow:hidden; }
  video { width:100%; display:block; aspect-ratio:16/9; outline:none; }
  .side { background:#fff; border:1px solid var(--line); border-radius:12px; padding:14px 16px; height:fit-content; }
  .side h2 { font-size:13px; color:var(--sub); font-weight:500; margin-bottom:8px; }
  .ch { display:flex; gap:10px; align-items:baseline; padding:8px 10px; border-radius:8px; cursor:pointer; }
  .ch:hover { background:#F5F6FA; }
  .ch.on { background:#F0F3FF; }
  .ch .no { font-size:11px; color:var(--sub); width:18px; flex:none; }
  .ch.on .no { color:var(--primary); font-weight:700; }
  .ch .tt { font-size:13.5px; }
  .ch .tt small { display:block; font-size:11.5px; color:var(--sub); margin-top:1px; }
  .ch .tm { margin-left:auto; font-size:11.5px; color:var(--sub); flex:none; font-variant-numeric:tabular-nums; }
  .cards { display:grid; grid-template-columns:repeat(4,1fr); gap:12px; padding:16px 28px 30px; }
  .card { background:#fff; border:1px solid var(--line); border-radius:12px; padding:13px 15px; }
  .card h3 { font-size:12px; color:var(--primary); font-weight:600; margin-bottom:7px; }
  .card ul { list-style:none; font-size:13px; line-height:1.7; }
  .card li::before { content:"·"; color:var(--sub); margin-right:6px; }
  .card.ans { text-align:center; }
  .card.ans .v { display:inline-block; margin-top:14px; color:#0E9F6E; border:1px solid #0E9F6E;
                 border-radius:8px; padding:6px 16px; font-size:14px; font-weight:600; }
  .stem { padding:0 28px 14px; font-size:13.5px; color:var(--sub); line-height:1.8; }
  @media (max-width:1000px){ .main{grid-template-columns:1fr} .cards{grid-template-columns:1fr 1fr} }
</style>
</head>
<body>
<header>
  <h1>一题一课 · 分步动画讲解</h1>
  <p>分镜 JSON → Manim 确定性编译 → 配音驱动时长 → 中文章节封装（原型：4 道真题，含竞品同款题）</p>
</header>
<div class="tabs" id="tabs"></div>
<div class="main">
  <div class="player"><video id="v" controls playsinline></video></div>
  <div class="side"><h2>视频章节</h2><div id="chs"></div></div>
</div>
<div class="stem" id="stem"></div>
<div class="cards" id="cards"></div>
<script>
const LESSONS = __DATA__;
const v = document.getElementById('v');
let cur = 0;
function fmt(t){ const m=Math.floor(t/60), s=(t%60); return String(m).padStart(2,'0')+':'+s.toFixed(1).padStart(4,'0'); }
// $...$ 混排渲染：KaTeX 就绪则真排版，未就绪（离线）降级保留原文，不阻塞页面
function math(s){
  if (typeof katex === 'undefined') return s;
  return s.replace(/\$([^$]+)\$/g, (_, tex) =>
    katex.renderToString(tex, { throwOnError: false }));
}
function select(i){
  cur = i;
  const L = LESSONS[i];
  v.src = '../out/' + L.id + '/final.mp4';
  document.getElementById('stem').innerHTML = '题干：' + math(L.problem.stem);
  document.querySelectorAll('.tab').forEach((t,k)=>t.classList.toggle('on',k===i));
  const cs = document.getElementById('chs'); cs.innerHTML = '';
  L.chapters.forEach((c,k)=>{
    const d = document.createElement('div'); d.className='ch'; d.dataset.k=k;
    d.innerHTML = '<span class="no">'+(k+1)+'</span><span class="tt">'+c.title+
      (c.subtitle?'<small>'+c.subtitle+'</small>':'')+'</span><span class="tm">'+fmt(c.start)+'</span>';
    d.onclick = ()=>{ v.currentTime = c.start + 0.05; v.play(); };
    cs.appendChild(d);
  });
  const P = L.problem;
  document.getElementById('cards').innerHTML =
    '<div class="card"><h3>已知条件</h3><ul>'+P.known.map(x=>'<li>'+math(x)+'</li>').join('')+'</ul></div>'+
    '<div class="card"><h3>解题目标</h3><ul><li>'+math(P.goal)+'</li></ul></div>'+
    '<div class="card"><h3>核心观察</h3><ul><li>'+math(P.core_observation)+'</li></ul></div>'+
    '<div class="card ans"><h3>答案</h3><span class="v">'+math(P.answer)+'</span></div>';
}
v.addEventListener('timeupdate', ()=>{
  const L = LESSONS[cur];
  let k = 0;
  L.chapters.forEach((c,i)=>{ if (v.currentTime >= c.start) k = i; });
  document.querySelectorAll('.ch').forEach((d,i)=>d.classList.toggle('on',i===k));
});
LESSONS.forEach((L,i)=>{
  const t = document.createElement('button'); t.className='tab'; t.textContent=L.title.replace('一题一课 · ','');
  t.onclick=()=>select(i); document.getElementById('tabs').appendChild(t);
});
if (LESSONS.length) select(0);
window.addEventListener('load', ()=>{ if (LESSONS.length) select(cur); });  // katex defer 就绪后重渲染
</script>
</body>
</html>
"""
    html = html.replace("__DATA__", data)
    os.makedirs(DEMO, exist_ok=True)
    with open(os.path.join(DEMO, "index.html"), "w", encoding="utf-8") as f:
        f.write(html)
    print(f"[demo] {len(lessons)} 课 → {os.path.join(DEMO, 'index.html')}")


if __name__ == "__main__":
    build()
