/* 一题一课 · 点击式讲题播放器
 *
 * 输入：compile_lessons.py 生成的 window.LESSONS[id]（几何已全部解析为数学坐标）。
 * 交互：点击画面 / → / 空格 = 下一步（动画播放中再点一次 = 立即完成当前动画）；
 *       ← = 上一步；顶部章节可跳转；无配音，纯视觉节奏。
 * 实现：SVG 逐笔绘制用 pathLength=1 + stroke-dashoffset CSS transition；
 *       动点/动直线（trace_move/trace_line）用 rAF 按 lesson_runtime.py 同款
 *       闭式公式逐帧重算（切点、椭圆交点），保证与 Manim 视频版几何一致。
 * 回退策略：上一步/跳章 = 从头瞬时重放再重绘，避免维护复杂 undo 状态。
 */
"use strict";

const NS = "http://www.w3.org/2000/svg";
const COLORS = { main: "#4D6BFE", aux: "#0E9F6E", hl: "#E8890C", ans: "#DC2626" };
// 各 op 动画时长（ms），基准对齐 lesson_runtime.py 的 BASE_TIME
const DUR = { draw: 1300, show: 700, label: 700, caption: 350, note: 900,
              highlight: 1300, dim: 600, hide: 600, answer: 1400, wait: 500 };
const W = 1000, H = 720, PAD = 46;

/* ---------- 小工具 ---------- */

function tangentPoints(px, py, cx, cy, r) {
  // 与 compile_lessons.py / lesson_runtime.py 同公式：[0]=ccw, [1]=cw
  const dx = px - cx, dy = py - cy, d = Math.hypot(dx, dy);
  if (d < r - 1e-12) return [];
  const ux = dx / d, uy = dy / d, phi = Math.acos(Math.max(-1, Math.min(1, r / d)));
  const out = [];
  for (const sign of [1, -1]) {
    const c = Math.cos(sign * phi), s = Math.sin(sign * phi);
    out.push([cx + r * (ux * c - uy * s), cy + r * (ux * s + uy * c)]);
  }
  return out;
}

function esc(t) {
  return String(t).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

// $..$ 混排：中文段转义文本，公式段 KaTeX（对齐 runtime 的 _mixed）
function mixedHTML(text) {
  const parts = String(text).split(/\$([^$]+)\$/);
  let html = "";
  parts.forEach((seg, i) => {
    if (!seg) return;
    if (i % 2 === 1) {
      html += katex.renderToString(seg, { throwOnError: false });
    } else {
      html += esc(seg);
    }
  });
  return html;
}

function svgEl(tag, attrs) {
  const el = document.createElementNS(NS, tag);
  for (const k in attrs) el.setAttribute(k, attrs[k]);
  return el;
}

/* ---------- 全局状态 ---------- */

const $ = (id) => document.getElementById(id);
const board = $("board"), labelsLayer = $("labels"), notesEl = $("notes");
let L = null;            // 当前课程数据
let map = null;          // 数学坐标 → 逻辑 px 映射
let flat = [];           // 展平步骤 [{ch, step, chIdx, stepIdx}]
let cur = -1;            // 已应用到的步骤下标
let els = {};            // 对象名 → SVG 元素
let labEls = {};         // 标签引用名 → {el, off:[px,px]}
let busy = null;         // 当前动画的 snap 句柄
let dynGroup = null;     // 动态几何图层
let footEls = {};        // trace foot_line 元素（按步骤 key）

/* ---------- 坐标映射与坐标系 ---------- */

function computeMap(view) {
  const dx = view.x[1] - view.x[0], dy = view.y[1] - view.y[0];
  const s = Math.min((W - 2 * PAD) / dx, (H - 2 * PAD) / dy);
  const ox = (W - dx * s) / 2, oy = (H - dy * s) / 2;
  return {
    s,
    X: (x) => ox + (x - view.x[0]) * s,
    Y: (y) => oy + (view.y[1] - y) * s,
  };
}

function drawAxes(view) {
  const g = svgEl("g", {});
  const defs = svgEl("defs", {});
  const mk = svgEl("marker", { id: "arrow", viewBox: "0 0 10 10", refX: 8, refY: 5,
                               markerWidth: 7, markerHeight: 7, orient: "auto-start-reverse" });
  mk.appendChild(svgEl("path", { d: "M0,0L10,5L0,10z", fill: "#1F2937" }));
  defs.appendChild(mk);
  g.appendChild(defs);
  if (view.axes === false) { board.appendChild(g); return; }
  const { X, Y } = map;
  const [x0, x1] = view.x, [y0, y1] = view.y;
  if (y0 <= 0 && y1 >= 0) {
    const ax = svgEl("line", { x1: X(x0), y1: Y(0), x2: X(x1), y2: Y(0),
                               class: "axis", "marker-end": "url(#arrow)" });
    g.appendChild(ax);
    g.appendChild(svgTxt(X(x1) + 14, Y(0) + 18, "x", "axis-txt"));
  }
  if (x0 <= 0 && x1 >= 0) {
    g.appendChild(svgEl("line", { x1: X(0), y1: Y(y0), x2: X(0), y2: Y(y1),
                                  class: "axis", "marker-end": "url(#arrow)" }));
    g.appendChild(svgTxt(X(0) - 18, Y(y1) + 4, "y", "axis-txt"));
  }
  if (x0 <= 0 && x1 >= 0 && y0 <= 0 && y1 >= 0)
    g.appendChild(svgTxt(X(0) - 14, Y(0) + 18, "O", "axis-txt"));
  for (const [axis, vals] of Object.entries(view.ticks || {})) {
    for (const v of vals) {
      if (axis === "x") {
        g.appendChild(svgEl("line", { x1: X(v), y1: Y(0) - 5, x2: X(v), y2: Y(0) + 5, class: "axis" }));
        g.appendChild(svgTxt(X(v), Y(0) + 22, String(v), "tick-txt"));
      } else {
        g.appendChild(svgEl("line", { x1: X(0) - 5, y1: Y(v), x2: X(0) + 5, y2: Y(v), class: "axis" }));
        g.appendChild(svgTxt(X(0) - 12, Y(v) + 5, String(v), "tick-txt"));
      }
    }
  }
  board.appendChild(g);
}

function svgTxt(x, y, t, cls) {
  const el = svgEl("text", { x, y, class: cls, "text-anchor": "middle" });
  el.textContent = t;
  return el;
}

/* ---------- 几何元素创建（默认隐藏，reveal 时播放） ---------- */

function styleOf(o) { return COLORS[o.style] || COLORS.main; }

function ensureObj(name) {
  if (els[name]) return els[name];
  const o = L.objects[name];
  const { X, Y, s } = map;
  let el;
  if (o.kind === "point") {
    el = svgEl("circle", { cx: X(o.x), cy: Y(o.y), r: 5, fill: styleOf(o), class: "fade-anim" });
  } else if (o.kind === "circle") {
    el = svgEl("circle", { cx: X(o.cx), cy: Y(o.cy), r: o.r * s, class: "geo stroke-anim",
                           pathLength: 1, stroke: styleOf(o), "stroke-width": 3 });
  } else if (o.kind === "segment") {
    el = svgEl("line", { x1: X(o.x1), y1: Y(o.y1), x2: X(o.x2), y2: Y(o.y2), class: "geo",
                         stroke: styleOf(o), "stroke-width": 3 });
    if (o.dashed) {
      el.setAttribute("stroke-dasharray", "7 7");
      el.classList.add("fade-anim");
    } else {
      el.setAttribute("pathLength", 1);
      el.classList.add("stroke-anim");
    }
  } else if (o.kind === "ray") {
    el = svgEl("line", { x1: X(o.x1), y1: Y(o.y1), x2: X(o.x2), y2: Y(o.y2), class: "geo",
                         stroke: styleOf(o), "stroke-width": 3, "marker-end": "url(#arrow)",
                         pathLength: 1 });
    el.classList.add("stroke-anim");
  } else if (o.kind === "angle_square") {
    el = svgEl("path", { d: angleSquareD(o.pts), class: "geo stroke-anim", pathLength: 1,
                         stroke: COLORS.hl, "stroke-width": 2.5 });
  } else if (o.kind === "angle_arc") {
    el = svgEl("path", { d: angleArcD(o.vx, o.vy, o.a0, o.a1, o.r), class: "geo stroke-anim",
                         pathLength: 1, stroke: COLORS.hl, "stroke-width": 2.5 });
  } else if (o.kind === "polyline") {
    el = svgEl("path", { d: "M" + o.pts.map((p) => `${X(p[0])},${Y(p[1])}`).join("L"),
                         class: "geo stroke-anim", pathLength: 1, stroke: styleOf(o), "stroke-width": 3 });
  } else {
    throw new Error("未知 kind " + o.kind);
  }
  dynGroup.appendChild(el);
  els[name] = el;
  return el;
}

function angleSquareD(pts) {
  return "M" + pts.map((p) => `${map.X(p[0])},${map.Y(p[1])}`).join("L") + "Z";
}

function angleArcD(vx, vy, a0, a1, r) {
  const n = 24, pts = [];
  for (let i = 0; i <= n; i++) {
    const a = a0 + ((a1 - a0) * i) / n;
    pts.push([vx + r * Math.cos(a), vy + r * Math.sin(a)]);
  }
  return "M" + pts.map((p) => `${map.X(p[0])},${map.Y(p[1])}`).join("L");
}

function reveal(name, animated, dur) {
  const el = ensureObj(name);
  const doIt = () => {
    if (el.classList.contains("stroke-anim")) {
      if (animated) el.style.transition = `stroke-dashoffset ${dur}ms linear`;
      el.classList.add("stroke-drawn");
    } else {
      if (animated) el.style.transition = `opacity ${Math.min(dur, 600)}ms`;
      el.classList.add("fade-drawn");
    }
  };
  if (animated) requestAnimationFrame(doIt); else doIt();
}

/* ---------- trace 逐帧更新（与 lesson_runtime 的 updater 等价） ---------- */

function resolveRef(v) {
  if (Array.isArray(v)) return [v[0], v[1]];
  const o = L.objects[v];
  return [o.x, o.y];
}

function movePoint(name, xy) {
  const el = els[name];
  if (el) { el.setAttribute("cx", map.X(xy[0])); el.setAttribute("cy", map.Y(xy[1])); }
}

function updateSeg(name, dyn) {
  const o = L.objects[name], el = els[name];
  if (!o || !el || !o.refs) return;
  let pa = dyn[o.refs.from] || resolveRef(o.refs.from);
  let pb = dyn[o.refs.to] || resolveRef(o.refs.to);
  if (o.refs.extend) {
    const e = o.refs.extend, vx = pb[0] - pa[0], vy = pb[1] - pa[1];
    pa = [pa[0] - vx * e, pa[1] - vy * e];
    pb = [pb[0] + vx * e, pb[1] + vy * e];
  }
  el.setAttribute("x1", map.X(pa[0])); el.setAttribute("y1", map.Y(pa[1]));
  el.setAttribute("x2", map.X(pb[0])); el.setAttribute("y2", map.Y(pb[1]));
}

function updateAngle(name, dyn) {
  const o = L.objects[name], el = els[name];
  if (!o || !el) return;
  const v = dyn[o.refs.at] || resolveRef(o.refs.at);
  const a = dyn[o.refs.from] || resolveRef(o.refs.from);
  const b = dyn[o.refs.to] || resolveRef(o.refs.to);
  if (o.kind === "angle_square") {
    const s = o.size, unit = (p) => {
      const dx = p[0] - v[0], dy = p[1] - v[1], d = Math.hypot(dx, dy) || 1;
      return [dx / d, dy / d];
    };
    const u1 = unit(a), u2 = unit(b);
    const pts = [[v[0] + s * u1[0], v[1] + s * u1[1]],
                 [v[0] + s * (u1[0] + u2[0]), v[1] + s * (u1[1] + u2[1])],
                 [v[0] + s * u2[0], v[1] + s * u2[1]]];
    el.setAttribute("d", angleSquareD(pts));
  } else {
    const av = Math.atan2(a[1] - v[1], a[0] - v[0]);
    const bv = Math.atan2(b[1] - v[1], b[0] - v[0]);
    el.setAttribute("d", angleArcD(v[0], v[1], Math.min(av, bv), Math.max(av, bv), o.r));
  }
}

function repositionLabel(name, xy) {
  const lab = labEls[name];
  if (!lab) return;
  lab.el.style.left = (map.X(xy[0]) + lab.off[0]) / W * 100 + "%";
  lab.el.style.top = (map.Y(xy[1]) + lab.off[1]) / H * 100 + "%";
}

function traceMoveFrame(step, px) {
  const [cx, cy] = step.circleCenter, r = step.circleRadius, py = step.moverY;
  const pts = tangentPoints(px, py, cx, cy, r);
  if (!pts.length) return;
  const b = step.branch === "cw" ? pts[1] : pts[0];
  const dyn = { [step.mover]: [px, py] };
  if (step.tangent_point) dyn[step.tangent_point] = b;
  for (const k in dyn) movePoint(k, dyn[k]);
  (step.links || []).forEach((n) => updateSeg(n, dyn));
  (step.arcs || []).forEach((n) => updateAngle(n, dyn));
  (step.labels || []).forEach((n) => dyn[n] && repositionLabel(n, dyn[n]));
  if (step.foot_line) {
    const key = step.__key, fa = resolveRef(step.foot_line);
    let el = footEls[key];
    if (!el) {
      el = svgEl("line", { class: "geo", stroke: COLORS.hl, "stroke-width": 2.5,
                           "stroke-dasharray": "6 6" });
      dynGroup.appendChild(el);
      footEls[key] = el;
    }
    el.setAttribute("x1", map.X(fa[0])); el.setAttribute("y1", map.Y(fa[1]));
    el.setAttribute("x2", map.X(px)); el.setAttribute("y2", map.Y(py));
  }
}

function traceLineFrame(step, th) {
  const [px0, py0] = step.pivot;
  const { a, b } = step.conic;
  const c = step.conic.center || [0, 0];
  const dx = Math.cos(th), dy = Math.sin(th);
  const ox = px0 - c[0], oy = py0 - c[1];
  const A = (dx * dx) / (a * a) + (dy * dy) / (b * b);
  const B = 2 * ((ox * dx) / (a * a) + (oy * dy) / (b * b));
  const C = (ox * ox) / (a * a) + (oy * oy) / (b * b) - 1;
  const disc = B * B - 4 * A * C;
  if (disc < 0) return;
  const sq = Math.sqrt(disc);
  const s1 = (-B - sq) / (2 * A), s2 = (-B + sq) / (2 * A);
  const names = Object.values(step.points);
  const dyn = {
    [names[0]]: [px0 + s1 * dx, py0 + s1 * dy],
    [names[1]]: [px0 + s2 * dx, py0 + s2 * dy],
  };
  const ext = step.extend || 1.8;
  const el = ensureObj(step.line);
  el.setAttribute("x1", map.X(px0 - ext * dx)); el.setAttribute("y1", map.Y(py0 - ext * dy));
  el.setAttribute("x2", map.X(px0 + ext * dx)); el.setAttribute("y2", map.Y(py0 + ext * dy));
  for (const n in dyn) {
    if (!els[n]) ensureObj(n);
    movePoint(n, dyn[n]);
  }
  for (const [pname, text] of Object.entries(step.labels || {})) {
    if (!labEls[pname]) makeLabel(pname, text, (step.label_offsets || {})[pname] || [0.3, 0.3], null, false);
    if (dyn[pname]) repositionLabel(pname, dyn[pname]);
  }
  (step.links || []).forEach((n) => updateSeg(n, dyn));
}

/* ---------- 标签 / 板书 / 字幕 / 结论 ---------- */

function makeLabel(name, text, off, atXY, animated) {
  const old = labEls[name];
  if (old) old.el.remove();
  const el = document.createElement("div");
  el.className = "lab";
  el.innerHTML = mixedHTML(text);
  const { s } = map;
  let px, py;
  if (atXY) { px = map.X(atXY[0]); py = map.Y(atXY[1]); }
  else {
    const o = L.objects[name];
    if (!els[name]) { ensureObj(name); reveal(name, false, 0); } // 标注引用未出现的点：直接落点
    px = map.X(o.x) + off[0] * s;
    py = map.Y(o.y) - off[1] * s;
  }
  el.style.left = px / W * 100 + "%";
  el.style.top = py / H * 100 + "%";
  labelsLayer.appendChild(el);
  labEls[name] = { el, off: [off[0] * s, -off[1] * s] };
  if (animated) requestAnimationFrame(() => el.classList.add("show"));
  else el.classList.add("show");
}

function addNote(step, animated) {
  const el = document.createElement("div");
  el.className = "note-line" + (step.kind === "formula" ? " formula" : "");
  el.innerHTML = step.kind === "formula"
    ? katex.renderToString(step.text, { throwOnError: false, displayMode: true })
    : mixedHTML(step.text);
  notesEl.appendChild(el);
  notesEl.scrollTop = notesEl.scrollHeight;
  if (animated) requestAnimationFrame(() => el.classList.add("show"));
  else el.classList.add("show");
}

function setCaption(text) {
  const cap = $("caption");
  cap.classList.remove("show");
  setTimeout(() => { cap.innerHTML = mixedHTML(text); cap.classList.add("show"); }, 120);
}

function showAnswer(text, animated) {
  const card = $("answer-card");
  card.innerHTML = katex.renderToString(text, { throwOnError: false });
  card.classList.remove("pop");
  void card.offsetWidth; // 重启动画
  card.classList.add("pop");
  if (!animated) { card.style.animationDuration = ".01s"; }
}

/* ---------- 步骤引擎 ---------- */

function flatten() {
  flat = [];
  L.chapters.forEach((ch, ci) =>
    ch.steps.forEach((st, si) => flat.push({ ch, step: st, chIdx: ci, stepIdx: si })));
  flat.forEach((f, i) => { f.step.__key = "s" + i; });
}

function chapterUI(chIdx, animated) {
  const ch = L.chapters[chIdx];
  document.querySelectorAll(".chap").forEach((el, i) => {
    el.classList.toggle("cur", i === chIdx);
    el.classList.toggle("done", i < chIdx);
  });
  $("chapter-chip").innerHTML =
    `${chIdx + 1} ${esc(ch.title)}<div class="chip-sub">${esc(ch.subtitle || "")}</div>`;
  if (animated) {
    const bn = $("banner");
    bn.innerHTML = `${esc(chIdx + 1)}. ${esc(ch.title)}<div class="bn-sub">${esc(ch.subtitle || "")}</div>`;
    bn.classList.remove("pop");
    void bn.offsetWidth;
    bn.classList.add("pop");
  }
}

// 应用一步。animated=false 时瞬时（用于重放/回退）
function apply(i, animated) {
  const { step, chIdx, stepIdx } = flat[i];
  if (stepIdx === 0) chapterUI(chIdx, animated);
  const op = step.op;
  const dur = step.run_time ? step.run_time * 1000 : (DUR[op] || 700);

  if (op === "draw" || op === "show") {
    reveal(step.ref, animated, dur);
    if (animated) setBusy(dur);
  } else if (op === "label") {
    const text = step.text || L.objects[step.ref]?.label;
    if (!text) return;
    makeLabel(step.ref, text, step.offset || [0.28, 0.28], step.at || null, animated);
    if (animated) setBusy(600);
  } else if (op === "caption") {
    setCaption(step.text);
    if (animated) setBusy(DUR.caption);
  } else if (op === "note") {
    addNote(step, animated);
    if (animated) setBusy(DUR.note);
  } else if (op === "highlight") {
    const refs = step.refs || (step.ref ? [step.ref] : []);
    refs.forEach((r) => {
      const el = els[r];
      if (el && animated) {
        el.classList.add("hl-pulse");
        setTimeout(() => el.classList.remove("hl-pulse"), DUR.highlight);
      }
    });
    if (animated) setBusy(DUR.highlight);
  } else if (op === "dim" || op === "hide") {
    (step.refs || (step.ref ? [step.ref] : [])).forEach((r) => {
      const el = els[r];
      if (el) el.style.opacity = op === "dim" ? 0.22 : 0;
    });
    if (animated) setBusy(DUR[op]);
  } else if (op === "answer") {
    showAnswer(step.text, animated);
    if (animated) setBusy(DUR.answer);
  } else if (op === "wait") {
    if (animated) setBusy(DUR.wait);
  } else if (op === "trace_move" || op === "trace_line") {
    const isMove = op === "trace_move";
    const from = isMove ? step.from : (step.from_deg * Math.PI) / 180;
    const to = isMove ? step.to : (step.to_deg * Math.PI) / 180;
    const frame = isMove ? traceMoveFrame : traceLineFrame;
    if (!animated) { frame(step, to); return; }
    const t0 = performance.now();
    let raf;
    const tick = (now) => {
      const p = Math.min(1, (now - t0) / dur);
      frame(step, from + (to - from) * p); // linear，对齐 runtime rate_func=linear
      if (p < 1) raf = requestAnimationFrame(tick);
      else { busy = null; }
    };
    raf = requestAnimationFrame(tick);
    busy = { snap() { cancelAnimationFrame(raf); frame(step, to); busy = null; } };
  } else {
    throw new Error("未知 op: " + op);
  }
}

// 短动画统一句柄：点击可跳过
function setBusy(ms) {
  const t = setTimeout(() => { busy = null; }, ms);
  busy = { snap() { clearTimeout(t); document.body.classList.add("noanim");
                    requestAnimationFrame(() => document.body.classList.remove("noanim"));
                    busy = null; } };
}

function rebuildTo(target) {
  // 瞬时重放 0..target（含），用于回退/跳章：DOM 全清重建，逻辑最简单可靠
  dynGroup.innerHTML = "";
  labelsLayer.innerHTML = "";
  notesEl.innerHTML = "";
  footEls = {};
  els = {};
  labEls = {};
  $("answer-card").classList.remove("pop");
  $("answer-card").style.animationDuration = "";
  $("banner").classList.remove("pop"); // 重放/回退时清掉残留的章横幅闪现
  $("caption").classList.remove("show");
  document.body.classList.add("noanim");
  for (let k = 0; k <= target; k++) apply(k, false);
  requestAnimationFrame(() => document.body.classList.remove("noanim"));
  cur = target;
  updateIndicator();
}

function next() {
  if (busy) { busy.snap(); return; }        // 动画中：先完成当前步
  if (cur >= flat.length - 1) return;
  cur += 1;
  apply(cur, true);
  updateIndicator();
}

function prev() {
  if (busy) { busy.snap(); return; }
  if (cur <= -1) return;
  rebuildTo(cur - 1);
}

function updateIndicator() {
  $("step-indicator").textContent = `${cur + 1} / ${flat.length}`;
}

/* ---------- 课程装载 ---------- */

function loadLesson(id, startStep) {
  L = window.LESSONS[id];
  cur = -1;
  busy = null;
  map = computeMap(L.view);
  board.innerHTML = "";
  labelsLayer.innerHTML = "";
  notesEl.innerHTML = "";
  els = {}; labEls = {}; footEls = {};
  drawAxes(L.view);
  dynGroup = svgEl("g", {});
  board.appendChild(dynGroup);
  flatten();
  // 标题：· 前红色主题、后黑色（参考视频标题手法）
  const [t1, t2] = L.title.split("·").map((s) => s.trim());
  $("title").innerHTML = `<span class="t-red">${esc(t1)}</span> ${esc(t2 || "")}`;
  $("chapters").innerHTML = L.chapters
    .map((c, i) => `<span class="chap" data-i="${i}">${esc(c.title)}</span>`).join("");
  document.querySelectorAll(".chap").forEach((el) =>
    el.addEventListener("click", () => {
      const first = flat.findIndex((f) => f.chIdx === +el.dataset.i);
      rebuildTo(first - 1);
      next();
    }));
  $("stem-card").innerHTML =
    `<div>${mixedHTML(L.problem.stem)}</div>` +
    `<div class="stem-goal">目标：${mixedHTML(L.problem.goal)}</div>`;
  chapterUI(0, false);
  document.querySelectorAll(".tab").forEach((el) =>
    el.classList.toggle("on", el.dataset.id === id));
  history.replaceState(null, "", `#${id}`);
  if (startStep > 0) rebuildTo(Math.min(startStep, flat.length - 1));
  updateIndicator();
}

/* ---------- 事件绑定与启动 ---------- */

$("canvas-wrap").addEventListener("click", next);
$("btn-next").addEventListener("click", (e) => { e.stopPropagation(); next(); });
$("btn-prev").addEventListener("click", (e) => { e.stopPropagation(); prev(); });
$("btn-replay").addEventListener("click", (e) => { e.stopPropagation(); rebuildTo(-1); next(); });
document.addEventListener("keydown", (e) => {
  if (e.key === "ArrowRight" || e.key === " ") { e.preventDefault(); next(); }
  else if (e.key === "ArrowLeft") { e.preventDefault(); prev(); }
});

// 课程 tab：标题去前缀后仍可能很长（如含试卷年份），截断显示避免与居中大标题打架
$("lesson-tabs").innerHTML = Object.keys(window.LESSONS)
  .map((id) => {
    let short = window.LESSONS[id].title.replace(/^一题一课\s*·\s*/, "").replace(/（.*?）/g, "").trim();
    if (short.length > 10) short = short.slice(0, 10) + "…";
    return `<span class="tab" data-id="${id}" title="${esc(window.LESSONS[id].title)}">${esc(short)}</span>`;
  })
  .join("");
document.querySelectorAll(".tab").forEach((el) =>
  el.addEventListener("click", () => { loadLesson(el.dataset.id); next(); }));

// URL hash 支持直达：#tangent-min 或 #tangent-min:12（第 12 步，供自动化测试截图）
const m = (location.hash || "").match(/^#([\w-]+)(?::(\d+))?$/);
const startId = m && window.LESSONS[m[1]] ? m[1] : Object.keys(window.LESSONS)[0];
const startStep = m && m[2] ? +m[2] - 1 : 0;
loadLesson(startId, startStep);
if (startStep <= 0) next(); // 直达指定步时 rebuild 已呈现，不再多走一步

// 暴露给自动化测试（playwright 断言用）
window.__player = {
  get cur() { return cur; },
  get total() { return flat.length; },
  get busy() { return busy !== null; },
  next, prev, rebuildTo, loadLesson,
  get lessonId() { return L && L.id; },
};
