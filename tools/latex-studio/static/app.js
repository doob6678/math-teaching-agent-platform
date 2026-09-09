/* LaTeX Studio 前端联动核心。
   数据流：编辑(防抖900ms) → POST /api/save（服务端保存即编译）→ 轮询 /api/state
   → 编译完成 → PDF.js 重渲染所有页（保持滚动比例）；错误时展示错误条并可点击跳行。
   外部修改检测：AI 改文件时 texMtime 变化，编辑器无未保存内容则自动加载新版本，
   实现"AI 迭代、人盯渲染"的中间过程展示。 */

import * as pdfjsLib from '/static/vendor/pdfjs/pdf.min.mjs';

pdfjsLib.GlobalWorkerOptions.workerSrc = '/static/vendor/pdfjs/pdf.worker.min.mjs';

const $ = (id) => document.getElementById(id);
const editor = CodeMirror.fromTextArea($('editor'), {
  mode: 'stex',
  lineNumbers: true,
  lineWrapping: true,
  matchBrackets: true,
  autoCloseBrackets: true,
});

const state = {
  path: new URLSearchParams(location.search).get('file') || localStorage.getItem('latex-studio.file') || '',
  mtime: 0,               // 最近一次加载/保存后的服务端 mtime，用于外部修改检测
  lastCompiledAt: 0,      // 已渲染 PDF 对应的编译完成时间，变化才重载
  zoom: 'fit',
  compiling: false,
  saveTimer: null,
  externalDismissedMtime: 0,
};

/* ---------------- 最近文件（localStorage 记忆，下拉快速切换） ---------------- */

const RECENT_KEY = "latex-studio.recent";

function renderRecent() {
  const sel = $("recent-files");
  sel.textContent = "";
  const opt = document.createElement("option");
  opt.value = "";
  opt.textContent = "最近文件";
  sel.appendChild(opt);
  for (const p of getRecent()) {
    const o = document.createElement("option");
    o.value = p;
    o.textContent = p.split(/[\\/]/).pop();
    o.title = p;
    sel.appendChild(o);
  }
}

function getRecent() {
  try {
    return JSON.parse(localStorage.getItem(RECENT_KEY) || "[]");
  } catch {
    return [];
  }
}

function pushRecent(path) {
  const list = [path, ...getRecent().filter((p) => p !== path)].slice(0, 8);
  localStorage.setItem(RECENT_KEY, JSON.stringify(list));
  renderRecent();
}

$("recent-files").addEventListener("change", (e) => {
  if (e.target.value) loadFile(e.target.value);
});
renderRecent();

/* ---------------- 文件加载 / 保存 ---------------- */

async function loadFile(path, { keepScroll = true } = {}) {
  const res = await fetch('/api/file?path=' + encodeURIComponent(path));
  const data = await res.json();
  if (!res.ok) { alert(data.error || '打开失败'); return; }
  const scroll = $('pdf-container');
  const ratio = keepScroll ? scroll.scrollTop / Math.max(1, scroll.scrollHeight - scroll.clientHeight) : 0;

  state.path = data.path;
  state.mtime = data.mtime;
  localStorage.setItem('latex-studio.file', data.path);
  pushRecent(data.path);
  $('file-path').value = data.path;
  document.title = `LaTeX Studio — ${data.path.split(/[\\/]/).pop()}`;

  // 保留光标/滚动：setValue 会重置，先记录再恢复
  const cursor = editor.getCursor();
  const scrollInfo = editor.getScrollInfo();
  editor.setValue(data.content);
  editor.clearHistory();
  editor.markClean();
  editor.setCursor(cursor);
  editor.scrollTo(scrollInfo.left, scrollInfo.top);

  state.lastCompiledAt = 0;
  // 打开/外部重载即触发编译：否则从未编译过（或服务重启后）的文件会永远停在"未编译"。
  // 服务端对内容未变的文件有 hash 短路，重复请求几乎零成本。
  await fetch('/api/compile', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path: data.path }),
  });
  await pollOnce();
  if (keepScroll && scroll.scrollHeight > scroll.clientHeight) {
    scroll.scrollTop = ratio * (scroll.scrollHeight - scroll.clientHeight);
  }
}

function scheduleSave() {
  if (!state.path) return;
  clearTimeout(state.saveTimer);
  state.saveTimer = setTimeout(saveAndCompile, 900);
}

async function saveAndCompile() {
  if (editor.isClean() || !state.path) return;
  // 即时反馈：不必等 1s 轮询，请求发出即进入排队态
  setStatus('queued');
  const res = await fetch('/api/save', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path: state.path, content: editor.getValue() }),
  });
  const data = await res.json();
  if (!res.ok) { setStatus('error', '保存失败'); console.error(data.error); return; }
  state.mtime = data.mtime;
  editor.markClean();
  await pollOnce();
}

/* ---------------- 状态轮询 ---------------- */

const BADGE_TEXT = { idle: '未编译', queued: '排队中', compiling: '编译中', ok: '编译成功', error: '编译失败' };

function setStatus(status, note) {
  const badge = $('status-badge');
  badge.className = 'badge badge-' + (status === 'error' ? 'error' : status);
  badge.textContent = note || BADGE_TEXT[status] || status;
}

async function pollOnce() {
  if (!state.path) return;
  const res = await fetch('/api/state?path=' + encodeURIComponent(state.path));
  if (!res.ok) return;
  const job = await res.json();

  // 编译状态徽章 + 右侧蒙层
  const compiling = job.status === 'compiling' || job.status === 'queued';
  if (compiling !== state.compiling) {
    state.compiling = compiling;
    $('pdf-container').classList.toggle('compiling', compiling);
  }
  if (job.status === 'error') {
    setStatus('error', '编译失败');
    showErrorBar(job);
  } else {
    setStatus(job.status);
    hideErrorBar();
  }

  $('stat-info').textContent = [
    job.pageCount ? `${job.pageCount} 页` : '',
    job.compileMs ? `${(job.compileMs / 1000).toFixed(1)}s` : '',
  ].filter(Boolean).join(' · ');

  $('warn-info').textContent = job.warnings.length ? `⚠ ${job.warnings.length}` : '';
  $('warn-info').title = job.warnings.join('\n');

  // 编译完成 → 重载 PDF（只在新一轮编译产物时）
  if (job.status === 'ok' && job.lastCompiledAt && job.lastCompiledAt !== state.lastCompiledAt) {
    state.lastCompiledAt = job.lastCompiledAt;
    await renderPdf();
  }

  // 外部修改检测：编辑器干净（没有未保存输入）才自动跟随
  if (job.texMtime && job.texMtime !== state.mtime) {
    if (editor.isClean()) {
      await loadFile(state.path);
    } else if (job.texMtime !== state.externalDismissedMtime) {
      $('external-hint').classList.remove('hidden');
    }
  }
}

/* ---------------- PDF 渲染 ---------------- */

async function renderPdf() {
  if (!state.path) return;
  const container = $('pdf-container');
  const res = await fetch('/api/pdf?path=' + encodeURIComponent(state.path));
  if (!res.ok) return;
  const buf = await res.arrayBuffer();
  const doc = await pdfjsLib.getDocument({ data: buf }).promise;

  const ratio = container.scrollTop / Math.max(1, container.scrollHeight - container.clientHeight);
  container.textContent = '';

  for (let i = 1; i <= doc.numPages; i++) {
    const page = await doc.getPage(i);
    const base = page.getViewport({ scale: 1 });
    // fit 模式：页宽铺满预览列（留 28px 边距）；数字模式：1pt=1px 基准再乘 zoom
    const scale = state.zoom === 'fit'
      ? (container.clientWidth - 28) / base.width
      : Number(state.zoom);
    const viewport = page.getViewport({ scale });
    const dpr = window.devicePixelRatio || 1;

    const wrap = document.createElement('div');
    wrap.className = 'page-wrap';
    wrap.dataset.page = i;
    const canvas = document.createElement('canvas');
    canvas.width = Math.floor(viewport.width * dpr);
    canvas.height = Math.floor(viewport.height * dpr);
    canvas.style.width = viewport.width + 'px';
    canvas.style.height = viewport.height + 'px';
    const label = document.createElement('span');
    label.className = 'page-number';
    label.textContent = i;
    wrap.append(canvas, label);
    container.appendChild(wrap);

    page.render({ canvasContext: canvas.getContext('2d'), viewport, transform: dpr !== 1 ? [dpr, 0, 0, dpr, 0, 0] : null });
  }

  $('page-info').textContent = `共 ${doc.numPages} 页`;
  if (ratio > 0 && container.scrollHeight > container.clientHeight) {
    container.scrollTop = ratio * (container.scrollHeight - container.clientHeight);
  }
}

// 滚动时显示当前页码
$('pdf-container').addEventListener('scroll', () => {
  if (!state.lastCompiledAt) return;
  const container = $('pdf-container');
  const mid = container.scrollTop + container.clientHeight * 0.4;
  let current = 1;
  container.querySelectorAll('.page-wrap').forEach((w) => {
    if (w.offsetTop <= mid) current = Number(w.dataset.page);
  });
  const total = container.querySelectorAll('.page-wrap').length;
  if (total) $('page-info').textContent = `${current} / ${total} 页`;
});

/* ---------------- 错误条 ---------------- */

function showErrorBar(job) {
  const bar = $('error-bar');
  const list = $('error-list');
  list.textContent = '';
  const errors = job.errors && job.errors.length ? job.errors : [{ file: '', line: null, message: job.errorMsg || '未知错误' }];
  for (const err of errors.slice(0, 5)) {
    const item = document.createElement('div');
    item.className = 'error-item';
    const line = document.createElement('span');
    line.className = 'err-line';
    line.textContent = err.line ? `第 ${err.line} 行` : '无行号';
    const msg = document.createElement('span');
    msg.textContent = err.message;
    item.append(line, msg);
    if (err.line) {
      item.addEventListener('click', () => jumpToLine(err.line));
    }
    list.appendChild(item);
  }
  bar.classList.remove('hidden');
  if (errors[0] && errors[0].line) highlightErrorLine(errors[0].line);
}

function hideErrorBar() {
  $('error-bar').classList.add('hidden');
  editor.eachLine((h) => editor.removeLineClass(h, 'background', 'cm-error-line'));
}

function jumpToLine(lineNo) {
  const line = Math.min(Math.max(1, lineNo), editor.lineCount()) - 1;
  editor.setCursor({ line, ch: 0 });
  editor.scrollIntoView({ line, ch: 0 }, 120);
  editor.focus();
}

function highlightErrorLine(lineNo) {
  editor.eachLine((h) => editor.removeLineClass(h, 'background', 'cm-error-line'));
  if (lineNo && lineNo <= editor.lineCount()) {
    editor.addLineClass(lineNo - 1, 'background', 'cm-error-line');
  }
}

/* ---------------- 交互绑定 ---------------- */

editor.on('change', () => {
  hideErrorBar();
  if ($('auto-compile').checked) scheduleSave();
});

$('btn-open').addEventListener('click', () => {
  const p = $('file-path').value.trim();
  if (p) loadFile(p);
});
$('file-path').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') $('btn-open').click();
});

$('btn-compile').addEventListener('click', async () => {
  if (!state.path) return;
  await saveAndCompile();
  await fetch('/api/compile', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path: state.path }),
  });
  await pollOnce();
});

// Ctrl+S 立即保存并编译（跳过 900ms 防抖，审阅改完想马上看时用）
window.addEventListener('keydown', (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault();
    clearTimeout(state.saveTimer);
    if (state.path) saveAndCompile();
  }
});

document.querySelectorAll('[data-zoom]').forEach((btn) => {
  btn.addEventListener('click', () => {
    state.zoom = btn.dataset.zoom;
    if (state.lastCompiledAt) renderPdf();
  });
});

$('btn-load-external').addEventListener('click', () => {
  $('external-hint').classList.add('hidden');
  if (state.path) loadFile(state.path);
});
$('btn-dismiss-external').addEventListener('click', () => {
  $('external-hint').classList.add('hidden');
  state.externalDismissedMtime = state.mtime;
});

// 拖拽分割条：调整编辑器列宽度
(() => {
  const divider = $('divider');
  const pane = $('editor-pane');
  let dragging = false;
  divider.addEventListener('mousedown', (e) => { dragging = true; e.preventDefault(); });
  window.addEventListener('mousemove', (e) => {
    if (!dragging) return;
    const width = Math.min(Math.max(300, e.clientX), window.innerWidth - 360);
    pane.style.width = width + 'px';
  });
  window.addEventListener('mouseup', () => { dragging = false; });
})();

// 预览列宽度变化（拖分割条/窗口缩放）时，fit 模式需要重排页面
(() => {
  let t = null;
  const ro = new ResizeObserver(() => {
    if (!state.lastCompiledAt || state.zoom !== 'fit') return;
    clearTimeout(t);
    t = setTimeout(renderPdf, 200);
  });
  ro.observe($('preview-pane'));
})();

/* ---------------- 轮询循环 & 启动 ---------------- */

setInterval(pollOnce, 1000);

if (state.path) {
  loadFile(state.path, { keepScroll: false });
}
