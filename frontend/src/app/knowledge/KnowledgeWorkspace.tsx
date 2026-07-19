import {
  AlertCircle, BrainCircuit, Database, FileText, GitBranch, Loader2,
  ZoomIn, ZoomOut, Network, Plus, Search, ShieldCheck, Sparkles,
} from "lucide-react";
import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  KnowledgeGraphSpineNode,
  KnowledgeGraphSpineResponse,
  KnowledgePointResponse,
  KnowledgeRelationResponse,
  QuestionBankItemResponse,
  TeacherResourceBlockSearchAuditEvent,
  TeacherResourceBlockSearchResponse,
  VectorIndexStatusResponse,
  createTextbookApiClient,
} from "../../shared/api/textbookApi";
import { compactText } from "../components/panelShared";

type TextbookApiClient = ReturnType<typeof createTextbookApiClient>;

type KnowledgeWorkspaceProps = { api: TextbookApiClient };

const MODULE_COLORS = [
  "#2563eb", "#7c3aed", "#d97706", "#16a34a", "#dc2626",
  "#0891b2", "#db2777", "#65a30d", "#ca8a04", "#0d9488",
  "#4f46e5", "#ea580c",
];

const NODE_TYPE_CONFIG = {
  MODULE: { r: 22, labelFont: 11, weight: 700 },
  TOPIC: { r: 14, labelFont: 10, weight: 500 },
  METHOD: { r: 9, labelFont: 0, weight: 400 },
} as const;

type SimNode = {
  id: string;
  label: string;
  nodeType: string;
  chapterPath: string;
  x: number;
  y: number;
  vx: number;
  vy: number;
  color: string;
};

export function KnowledgeWorkspace({ api }: KnowledgeWorkspaceProps) {
  const [graph, setGraph] = useState<KnowledgeGraphSpineResponse | null>(null);
  const [knowledgePoints, setKnowledgePoints] = useState<KnowledgePointResponse[]>([]);
  const [knowledgeRelations, setKnowledgeRelations] = useState<KnowledgeRelationResponse[]>([]);
  const [questions, setQuestions] = useState<QuestionBankItemResponse[]>([]);
  const [vectorStatus, setVectorStatus] = useState<VectorIndexStatusResponse | null>(null);
  const [query, setQuery] = useState("");
  const [questionQuery, setQuestionQuery] = useState("");
  const [pointName, setPointName] = useState("");
  const [chapterPath, setChapterPath] = useState("");
  const [questionTitle, setQuestionTitle] = useState("");
  const [questionText, setQuestionText] = useState("");
  const [searchResult, setSearchResult] = useState<TeacherResourceBlockSearchResponse | null>(null);
  const [searchAudit, setSearchAudit] = useState<TeacherResourceBlockSearchAuditEvent | null>(null);
  const [loading, setLoading] = useState(false);
  const [searching, setSearching] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [graphReady, setGraphReady] = useState(false);

  const nodes = graph?.nodes ?? [];
  const edges = graph?.edges ?? [];

  const simNodesRef = useRef<SimNode[]>([]);
  const simFrameRef = useRef<number>(0);
  const svgRef = useRef<SVGSVGElement>(null);
  const [hoveredId, setHoveredId] = useState<string | null>(null);
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const [dragging, setDragging] = useState<{ id: string; ox: number; oy: number } | null>(null);
  const isPanning = useRef(false);
  const panStart = useRef({ x: 0, y: 0 });
  const panOffset = useRef({ x: 0, y: 0 });

  const moduleColorMap = useMemo(() => {
    const map = new Map<string, string>();
    const seen = new Set<string>();
    let idx = 0;
    for (const n of nodes) {
      if (n.nodeType !== "MODULE") continue;
      if (seen.has(n.label)) continue;
      seen.add(n.label);
      map.set(n.label, MODULE_COLORS[idx++ % MODULE_COLORS.length]);
    }
    return map;
  }, [nodes]);

  const nodeColor = useCallback((n: KnowledgeGraphSpineNode): string => {
    if (n.nodeType === "MODULE") return moduleColorMap.get(n.label) ?? "#64748b";
    if (n.nodeType === "TOPIC") return "#3b82f6";
    return "#a78bfa";
  }, [moduleColorMap]);

  const connectorIds = useMemo(() => {
    const ids = new Set<string>();
    for (const e of edges) { ids.add(e.source); ids.add(e.target); }
    return ids;
  }, [edges]);

  const graphNodes = useMemo(
    () => nodes.filter((n) => connectorIds.has(n.id) || n.nodeType === "MODULE"),
    [nodes, connectorIds],
  );

  const initSimulation = useCallback(() => {
    const cx = 400, cy = 300;
    const sim: SimNode[] = graphNodes.map((n, i) => {
      const angle = (2 * Math.PI * i) / graphNodes.length;
      const radius = 200 + Math.random() * 60;
      return {
        id: n.id, label: n.label, nodeType: n.nodeType, chapterPath: n.chapterPath,
        x: cx + radius * Math.cos(angle), y: cy + radius * Math.sin(angle),
        vx: 0, vy: 0, color: nodeColor(n),
      };
    });

    const nodeMap = new Map(sim.map((s) => [s.id, s]));
    const edgeList = edges.filter((e) => nodeMap.has(e.source) && nodeMap.has(e.target));

    let iter = 0;
    const maxIter = 200;

    function step() {
      iter++;
      const decay = Math.max(0.02, 1 - iter / maxIter);
      const k = 80;
      const repK = 3000;
      const centerK = 0.02;

      for (const a of sim) {
        let fx = 0, fy = 0;
        fx += (cx - a.x) * centerK * (a.nodeType === "MODULE" ? 0.5 : 1);
        fy += (cy - a.y) * centerK * (a.nodeType === "MODULE" ? 0.5 : 1);

        for (const b of sim) {
          if (a.id === b.id) continue;
          const dx = a.x - b.x, dy = a.y - b.y;
          const dist = Math.max(1, Math.sqrt(dx * dx + dy * dy));
          const force = repK / (dist * dist);
          fx += (dx / dist) * force;
          fy += (dy / dist) * force;
        }

        for (const edge of edgeList) {
          if (edge.source === a.id) {
            const b = nodeMap.get(edge.target)!;
            const dx = b.x - a.x, dy = b.y - a.y;
            const dist = Math.max(1, Math.sqrt(dx * dx + dy * dy));
            const force = (dist - k) * 0.08;
            fx += (dx / dist) * force;
            fy += (dy / dist) * force;
          }
          if (edge.target === a.id) {
            const b = nodeMap.get(edge.source)!;
            const dx = b.x - a.x, dy = b.y - a.y;
            const dist = Math.max(1, Math.sqrt(dx * dx + dy * dy));
            const force = (dist - k) * 0.08;
            fx += (dx / dist) * force;
            fy += (dy / dist) * force;
          }
        }

        a.vx = (a.vx + fx) * decay;
        a.vy = (a.vy + fy) * decay;
        a.x += a.vx;
        a.y += a.vy;
      }

      if (iter < maxIter) {
        simFrameRef.current = requestAnimationFrame(step);
      } else {
        setGraphReady(true);
      }
    }

    simNodesRef.current = sim;
    step();
  }, [graphNodes, edges, nodeColor]);

  useEffect(() => {
    if (graphNodes.length === 0) return;
    cancelAnimationFrame(simFrameRef.current);
    setGraphReady(false);
    initSimulation();
    return () => cancelAnimationFrame(simFrameRef.current);
  }, [initSimulation]);

  const nodeMap = useMemo(() => {
    const map = new Map<string, SimNode>();
    for (const n of simNodesRef.current) map.set(n.id, n);
    return map;
  }, [graphReady, graphNodes.length]);

  const edgeEndpoints = useMemo(() => {
    if (!graphReady) return [];
    const map = nodeMap;
    return edges
      .filter((e) => map.has(e.source) && map.has(e.target))
      .map((e) => ({
        ...e,
        sx: map.get(e.source)!.x, sy: map.get(e.source)!.y,
        tx: map.get(e.target)!.x, ty: map.get(e.target)!.y,
      }));
  }, [graphReady, edges, nodeMap]);

  function refresh() {
    setLoading(true); setError("");
    Promise.all([
      api.getKnowledgeGraphSpine(), api.listKnowledgePoints(), api.listKnowledgeRelations(),
      api.getVectorIndexStatus(),
      api.searchQuestionBankItems(questionQuery.trim(), 50),
    ])
      .then(([g, pts, rels, vs, qs]) => { setGraph(g); setKnowledgePoints(pts); setKnowledgeRelations(rels); setVectorStatus(vs); setQuestions(qs); })
      .catch((e: Error) => setError(userFacingError(e.message)))
      .finally(() => setLoading(false));
  }

  useEffect(() => { refresh(); }, [api]);

  function handleSearchResources(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!query.trim()) { setError("请输入资源检索词。"); return; }
    setSearching(true); setError(""); setSearchAudit(null);
    api.searchTeacherResourceBlocks(query.trim(), 8)
      .then((r) => { setSearchResult(r); return api.getTeacherResourceBlockSearchAudit(r.queryId).then(setSearchAudit).catch((e: Error) => setError(userFacingError(e.message))); })
      .catch((e: Error) => setError(userFacingError(e.message))).finally(() => setSearching(false));
  }

  function handleSearchQuestions(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setLoading(true); setError("");
    api.searchQuestionBankItems(questionQuery.trim(), 50).then(setQuestions).catch((e: Error) => setError(userFacingError(e.message))).finally(() => setLoading(false));
  }

  function handleCreatePoint(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!pointName.trim() || !chapterPath.trim()) { setError("知识点名称和章节路径不能为空。"); return; }
    setSaving(true); setError("");
    api.createKnowledgePoint({ knowledgePointName: pointName.trim(), chapterPath: chapterPath.trim(), permissionScope: "MATH_VIP", sourceSummary: "前端创建" })
      .then((p) => { setKnowledgePoints((c) => [p, ...c]); setPointName(""); setChapterPath(""); })
      .catch((e: Error) => setError(userFacingError(e.message))).finally(() => setSaving(false));
  }

  function handleCreateQuestion(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!questionTitle.trim() || !questionText.trim()) { setError("题目标题和题干不能为空。"); return; }
    setSaving(true); setError("");
    api.createQuestionBankItem({ questionTitle: questionTitle.trim(), questionText: questionText.trim(), answerJson: "{}", difficulty: "medium", permissionScope: "MATH_VIP", knowledgePointIds: knowledgePoints.slice(0, 1).map((p) => p.knowledgePointId) })
      .then((q) => { setQuestions((c) => [q, ...c]); setQuestionTitle(""); setQuestionText(""); })
      .catch((e: Error) => setError(userFacingError(e.message))).finally(() => setSaving(false));
  }

  const handleSvgMouseDown = useCallback((e: React.MouseEvent) => {
    if (e.button !== 0 || e.target !== svgRef.current) return;
    isPanning.current = true;
    panStart.current = { x: e.clientX - pan.x, y: e.clientY - pan.y };
  }, [pan]);

  const handleSvgMouseMove = useCallback((e: React.MouseEvent) => {
    if (isPanning.current) {
      setPan({ x: e.clientX - panStart.current.x, y: e.clientY - panStart.current.y });
    }
  }, []);

  const handleSvgMouseUp = useCallback(() => { isPanning.current = false; setDragging(null); }, []);

  const handleNodeMouseDown = useCallback((e: React.MouseEvent, id: string) => {
    e.stopPropagation();
    setDragging({ id, ox: e.clientX, oy: e.clientY });
  }, []);

  const handleNodeMouseMove = useCallback((e: React.MouseEvent) => {
    if (!dragging) return;
    const node = simNodesRef.current.find((n) => n.id === dragging.id);
    if (!node) return;
    const dx = (e.clientX - dragging.ox) / zoom;
    const dy = (e.clientY - dragging.oy) / zoom;
    node.x += dx; node.y += dy;
    setDragging({ ...dragging, ox: e.clientX, oy: e.clientY });
  }, [dragging, zoom]);

  const handleWheel = useCallback((e: React.WheelEvent) => {
    e.preventDefault();
    const delta = e.deltaY > 0 ? 0.9 : 1.1;
    setZoom((z) => Math.max(0.2, Math.min(4, z * delta)));
  }, []);

  const hoveredEdges = useMemo(() => {
    if (!hoveredId) return new Set<string>();
    const set = new Set<string>();
    for (const e of edges) {
      if (e.source === hoveredId || e.target === hoveredId) set.add(e.id);
    }
    return set;
  }, [hoveredId, edges]);

  const hoveredNeighbors = useMemo(() => {
    if (!hoveredId) return new Set<string>();
    const set = new Set<string>([hoveredId]);
    for (const e of edges) {
      if (e.source === hoveredId) set.add(e.target);
      if (e.target === hoveredId) set.add(e.source);
    }
    return set;
  }, [hoveredId, edges]);

  const moduleNames = useMemo(() => {
    const s = new Set<string>();
    for (const n of nodes) { if (n.nodeType === "MODULE") s.add(n.label); }
    return s;
  }, [nodes]);

  const nodeEdgeCount = useMemo(() => {
    const c = new Map<string, number>();
    for (const e of edges) { c.set(e.source, (c.get(e.source) ?? 0) + 1); c.set(e.target, (c.get(e.target) ?? 0) + 1); }
    return c;
  }, [edges]);

  const edgeById = useMemo(() => new Map(edges.map((e) => [e.id, e])), [edges]);

  const legendItems = useMemo(() => {
    const items: { label: string; color: string; shape: string }[] = [];
    for (const name of moduleNames) {
      items.push({ label: name, color: moduleColorMap.get(name) ?? "#64748b", shape: "rect" });
    }
    items.push({ label: "知识点", color: "#3b82f6", shape: "circle" });
    items.push({ label: "方法", color: "#a78bfa", shape: "circle" });
    return items;
  }, [moduleNames, moduleColorMap]);

  return (
    <div className="kw">
      {error ? <div className="kw-banner kw-banner-error"><AlertCircle size={16} /><span>{error}</span></div> : null}

      <div className="kw-stats">
        {[
          { icon: <BrainCircuit size={18} />, bg: "#2563eb", gradient: "linear-gradient(135deg, #eff6ff, #dbeafe)", label: "图谱节点", value: graph?.nodeCount ?? nodes.length },
          { icon: <GitBranch size={18} />, bg: "#7c3aed", gradient: "linear-gradient(135deg, #f5f3ff, #ede9fe)", label: "关系边", value: graph?.edgeCount ?? edges.length },
          { icon: <Database size={18} />, bg: "#d97706", gradient: "linear-gradient(135deg, #fefce8, #fef9c3)", label: "知识点", value: knowledgePoints.length },
          { icon: <FileText size={18} />, bg: "#16a34a", gradient: "linear-gradient(135deg, #f0fdf4, #dcfce7)", label: "题库项", value: questions.length },
          { icon: <ShieldCheck size={18} />, bg: "#dc2626", gradient: "linear-gradient(135deg, #fef2f2, #fee2e2)", label: "向量状态", value: vectorStatusLabel(vectorStatus?.status), small: true },
        ].map((s, i) => (
          <div className="kw-stat" style={{ background: s.gradient }} key={i}>
            <div className="kw-stat-icon" style={{ background: s.bg, color: "#fff" }}>{s.icon}</div>
            <div>
              <div className="kw-stat-value" style={s.small ? { fontSize: 13 } : undefined}>{s.value}</div>
              <div className="kw-stat-label">{s.label}</div>
            </div>
          </div>
        ))}
      </div>

      {vectorStatus ? (
        <div className="kw-vector-status">
          <span className="kw-badge kw-badge-purple">集合 {vectorStatusLabel(vectorStatus.collectionState)}</span>
          <span className="kw-badge kw-badge-blue">索引 {vectorStatusLabel(vectorStatus.indexState)}</span>
          <span className="kw-badge kw-badge-amber">加载 {vectorStatusLabel(vectorStatus.loadState)}</span>
          <span className="kw-vector-meta">{vectorStatus.rowCount.toLocaleString("zh-CN")} 条向量</span>
          <span className="kw-vector-meta">{vectorStatus.embeddingModel}</span>
        </div>
      ) : null}

      <div className="kw-section">
        <div className="kw-section-header">
          <div>
            <h3 className="kw-section-title">知识图谱</h3>
            <p className="kw-section-subtitle">{nodes.length} 节点 · {edges.length} 关系 · 主干知识点与高频方法</p>
          </div>
          <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
            <button className="btn btn-ghost btn-sm" onClick={() => setZoom((z) => Math.min(4, z * 1.3))}><ZoomIn size={14} /></button>
            <span style={{ fontSize: 12, color: "var(--slate)", minWidth: 32, textAlign: "center" }}>{Math.round(zoom * 100)}%</span>
            <button className="btn btn-ghost btn-sm" onClick={() => setZoom((z) => Math.max(0.2, z / 1.3))}><ZoomOut size={14} /></button>
            <div className="kw-legend">
              {legendItems.map((item) => (
                <span className="kw-legend-item" key={item.label}>
                  <span className={`kw-legend-shape kw-legend-${item.shape}`} style={{ background: item.color }} />
                  {item.label}
                </span>
              ))}
            </div>
          </div>
        </div>
        <div className="kw-graph-container">
          <svg
            ref={svgRef}
            className="kw-graph-svg"
            onMouseDown={handleSvgMouseDown}
            onMouseMove={(e) => { handleSvgMouseMove(e); handleNodeMouseMove(e); }}
            onMouseUp={handleSvgMouseUp}
            onMouseLeave={handleSvgMouseUp}
            onWheel={handleWheel}
          >
            <g transform={`translate(${pan.x},${pan.y}) scale(${zoom})`}>
              <defs>
                {edges.map((e) => (
                  <marker key={e.id} id={`arrow-${e.id}`} markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">
                    <path d="M0,0 L8,3 L0,6" fill={hoveredId && hoveredEdges.has(e.id) ? "#2563eb" : "rgba(100,116,139,0.3)"} />
                  </marker>
                ))}
              </defs>

              {graphReady && edgeEndpoints.map((e) => {
                const dx = e.tx - e.sx, dy = e.ty - e.sy;
                const dist = Math.sqrt(dx * dx + dy * dy);
                const srcNode = graphNodes.find((n) => n.id === e.source);
                const cfg = NODE_TYPE_CONFIG[srcNode?.nodeType as keyof typeof NODE_TYPE_CONFIG] ?? NODE_TYPE_CONFIG.TOPIC;
                const r = cfg.r + 4;
                const nx = dist > 1 ? (dx / dist) * r : 0;
                const ny = dist > 1 ? (dy / dist) * r : 0;
                const isHovered = hoveredId && hoveredEdges.has(e.id);
                return (
                  <line
                    key={e.id}
                    x1={e.sx + nx} y1={e.sy + ny}
                    x2={e.tx} y2={e.ty}
                    stroke={isHovered ? "#2563eb" : "rgba(100,116,139,0.25)"}
                    strokeWidth={isHovered ? 2.5 : 1.2}
                    markerEnd={`url(#arrow-${e.id})`}
                    className="kw-graph-edge"
                  />
                );
              })}

              {graphReady && simNodesRef.current.map((sn) => {
                const orig = graphNodes.find((n) => n.id === sn.id);
                const cfg = NODE_TYPE_CONFIG[sn.nodeType as keyof typeof NODE_TYPE_CONFIG] ?? NODE_TYPE_CONFIG.TOPIC;
                const isHovered = hoveredId === sn.id;
                const isNeighbor = hoveredNeighbors.has(sn.id);
                const dimmed = hoveredId !== null && !isNeighbor;
                const connCount = nodeEdgeCount.get(sn.id) ?? 0;
                return (
                  <g
                    key={sn.id}
                    className={`kw-graph-node ${isHovered ? "hovered" : ""}`}
                    onMouseDown={(e) => handleNodeMouseDown(e, sn.id)}
                    onMouseEnter={() => setHoveredId(sn.id)}
                    onMouseLeave={() => setHoveredId(null)}
                    style={{ cursor: "grab", opacity: dimmed ? 0.2 : 1 }}
                  >
                    {sn.nodeType === "MODULE" ? (
                      <rect
                        x={sn.x - cfg.r} y={sn.y - cfg.r}
                        width={cfg.r * 2} height={cfg.r * 2}
                        rx={6} ry={6}
                        fill={sn.color}
                        stroke={isHovered ? "#fff" : "none"}
                        strokeWidth={isHovered ? 2.5 : 0}
                      />
                    ) : (
                      <>
                        <circle
                          cx={sn.x} cy={sn.y} r={cfg.r}
                          fill={sn.color}
                          stroke={isHovered ? "#fff" : "none"}
                          strokeWidth={isHovered ? 2.5 : 0}
                        />
                        {isHovered && connCount > 0 && (
                          <circle cx={sn.x} cy={sn.y} r={cfg.r + 5} fill="none" stroke={sn.color} strokeWidth={1} opacity={0.4} />
                        )}
                      </>
                    )}
                    {cfg.labelFont > 0 && (
                      <text
                        x={sn.x} y={sn.y + (sn.nodeType === "MODULE" ? 0 : cfg.r + 13)}
                        textAnchor="middle"
                        dominantBaseline={sn.nodeType === "MODULE" ? "central" : "auto"}
                        fill={sn.nodeType === "MODULE" ? "#fff" : "#1e293b"}
                        fontSize={cfg.labelFont}
                        fontWeight={cfg.weight}
                        fontFamily="var(--font-display)"
                        className="kw-graph-label"
                      >
                        {sn.label}
                      </text>
                    )}
                  </g>
                );
              })}
            </g>
          </svg>

          {hoveredId && graphReady && (() => {
            const node = simNodesRef.current.find((n) => n.id === hoveredId);
            const orig = graphNodes.find((n) => n.id === hoveredId);
            if (!node || !orig) return null;
            const connectedEdges = edges.filter((e) => e.source === hoveredId || e.target === hoveredId);
            const connectedNodes = new Set<string>();
            for (const e of connectedEdges) { connectedNodes.add(e.source); connectedNodes.add(e.target); }
            connectedNodes.delete(hoveredId);
            return (
              <div className="kw-graph-tooltip">
                <div className="kw-graph-tooltip-title">
                  <span className="kw-legend-shape kw-legend-rect" style={{ background: node.color }} />
                  {orig.label}
                </div>
                <div className="kw-graph-tooltip-meta">{orig.chapterPath}</div>
                <div className="kw-graph-tooltip-meta">{nodeTypeLabel(orig.nodeType)} · {connectedEdges.length} 关系</div>
                {connectedNodes.size > 0 && (
                  <div className="kw-graph-tooltip-conn">
                    <span>关联:</span>
                    {Array.from(connectedNodes).slice(0, 8).map((cid) => {
                      const cn = simNodesRef.current.find((s) => s.id === cid);
                      return cn ? <span key={cid} className="kw-graph-tooltip-tag">{cn.label}</span> : null;
                    })}
                  </div>
                )}
                {connectedEdges.slice(0, 3).map((e) => {
                  const src = simNodesRef.current.find((s) => s.id === e.source)?.label ?? e.source;
                  const tgt = simNodesRef.current.find((s) => s.id === e.target)?.label ?? e.target;
                  const edgeMeta = edgeById.get(e.id);
                  return (
                    <div key={e.id} className="kw-graph-tooltip-edge">
                      <Network size={10} />
                      <span>{src} → {tgt}</span>
                      {edgeMeta?.evidenceSummary ? <span className="kw-graph-tooltip-ev">{edgeMeta.evidenceSummary}</span> : null}
                    </div>
                  );
                })}
              </div>
            );
          })()}
        </div>
      </div>

      <div className="kw-section">
        <div className="kw-section-header">
          <h3 className="kw-section-title">向量检索</h3>
          <span className="kw-section-badge">{retrievalModeLabel(searchResult?.retrievalMode)}</span>
        </div>
        <form className="kw-search-form" onSubmit={handleSearchResources}>
          <div className="kw-search-row">
            <input className="form-input" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="输入知识点、题型或公式关键词进行 RAG 检索" />
            <button className="btn btn-primary" type="submit" disabled={searching}>
              {searching ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
              <span>检索</span>
            </button>
          </div>
        </form>
        {searchResult ? (
          <div className="kw-hits">
            {searchAudit ? (
              <div className="kw-audit">
                <span>{searchAudit.elapsedMs} 毫秒</span>
                <span>{subjectLabel(searchAudit.subjectType)} · {compactText(searchAudit.subjectId, 18)}</span>
              </div>
            ) : null}
            {searchResult.hits.map((hit) => (
              <div className="kw-hit" key={`${hit.documentId}:${hit.blockId}`}>
                <div className="kw-hit-header"><strong>{hit.documentTitle}</strong><span className="kw-badge kw-badge-blue">#{hit.blockOrder}</span></div>
                <p>{compactText(hit.snippet, 140)}</p>
                <details className="review-details">
                  <summary>查看完整片段</summary>
                  <p>{hit.snippet}</p>
                </details>
                <div className="kw-hit-score">相关度 {hit.score.toFixed(4)}</div>
              </div>
            ))}
          </div>
        ) : null}
      </div>

      <div className="kw-split">
        <div className="kw-section">
          <div className="kw-section-header">
            <h3 className="kw-section-title">知识点维护</h3>
            <span className="kw-section-badge">{knowledgeRelations.length} 条关系</span>
          </div>
          <form className="kw-inline-form" onSubmit={handleCreatePoint}>
            <input className="form-input" value={pointName} onChange={(e) => setPointName(e.target.value)} placeholder="知识点名称" />
            <input className="form-input" value={chapterPath} onChange={(e) => setChapterPath(e.target.value)} placeholder="章节路径" />
            <button className="btn btn-primary btn-sm" type="submit" disabled={saving}>{saving ? <Loader2 className="spin" size={14} /> : <Plus size={14} />}<span>保存</span></button>
          </form>
          <div className="kw-point-list">
            {knowledgePoints.slice(0, 12).map((p) => (
              <div className="kw-point" key={p.knowledgePointId}>
                <div className="kw-point-name">{p.knowledgePointName}</div>
                <div className="kw-point-path">{p.chapterPath}</div>
              </div>
            ))}
          </div>
        </div>
        <div className="kw-section">
          <div className="kw-section-header">
            <h3 className="kw-section-title">题库维护</h3>
            <span className="kw-section-badge">{questions.length} 题</span>
          </div>
          <form className="kw-inline-form" onSubmit={handleSearchQuestions}>
            <input className="form-input" value={questionQuery} onChange={(e) => setQuestionQuery(e.target.value)} placeholder="搜索题库" />
            <button className="btn btn-primary btn-sm" type="submit" disabled={loading}>{loading ? <Loader2 className="spin" size={14} /> : <Search size={14} />}<span>查找</span></button>
          </form>
          <form className="kw-question-form" onSubmit={handleCreateQuestion}>
            <input className="form-input" value={questionTitle} onChange={(e) => setQuestionTitle(e.target.value)} placeholder="题目标题" />
            <textarea className="form-textarea" value={questionText} onChange={(e) => setQuestionText(e.target.value)} placeholder="题干正文" rows={2} />
            <button className="btn btn-primary btn-sm" type="submit" disabled={saving}>{saving ? <Loader2 className="spin" size={14} /> : <Sparkles size={14} />}<span>保存题目</span></button>
          </form>
          <div className="kw-question-list">
            {questions.length === 0 ? <div className="kw-empty">暂无题目</div> : questions.map((q) => (
              <div className="kw-question" key={q.questionId}>
                <div className="kw-question-title">{questionDisplayTitle(q)}</div>
                <p>{questionSummary(q.questionText)}</p>
                <details className="review-details">
                  <summary>查看完整题干</summary>
                  <p>{cleanQuestionText(q.questionText)}</p>
                </details>
                <div className="kw-question-meta">
                  <span className={`kw-badge ${q.difficulty === "hard" ? "kw-badge-red" : q.difficulty === "easy" ? "kw-badge-green" : "kw-badge-blue"}`}>{difficultyLabel(q.difficulty)}</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

function questionDisplayTitle(question: QuestionBankItemResponse) {
  const cleanedTitle = cleanQuestionText(question.questionTitle);
  const cleanedBody = cleanQuestionText(question.questionText);
  const title = meaningfulQuestionTitle(cleanedTitle) || meaningfulQuestionTitle(cleanedBody);
  return title || "未命名题目";
}

function meaningfulQuestionTitle(value: string) {
  const text = value
    .replace(/赵礼显数学/g, "")
    .replace(/\*+/g, "")
    .replace(/\s+/g, " ")
    .trim();
  const numbered = text.match(/(?:^|[。；;])\s*(\d+[.．、]\s*[^。；;]{12,90})/);
  if (numbered?.[1]) {
    return compactText(numbered[1], 64);
  }
  if (text.length < 8 || /^[\W_]+$/.test(text)) {
    return "";
  }
  return compactText(text, 64);
}

function questionSummary(value: string) {
  const text = cleanQuestionText(value);
  const firstQuestion = text.match(/(?:^|[。；;])\s*\d+[.．、]\s*([^。；;]{20,150})/);
  return compactText(firstQuestion?.[1] || text, 110);
}

function cleanQuestionText(value?: string | null) {
  return (value ?? "")
    .replace(/赵礼显数学/g, " ")
    .replace(/(?:\*\s*){2,}/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function vectorStatusLabel(value?: string | null) {
  const normalized = (value ?? "").trim().toLowerCase();
  const labels: Record<string, string> = {
    loaded: "已加载",
    loadstateloaded: "已加载",
    load_state_loaded: "已加载",
    loading: "加载中",
    loadstateloading: "加载中",
    not_load: "未加载",
    not_loaded: "未加载",
    loadstatenotload: "未加载",
    healthy: "正常",
    ready: "就绪",
    searchable: "可检索",
    available: "可用",
    unavailable: "不可用",
    exists: "已创建",
    created: "已创建",
    missing: "未创建",
    not_exist: "未创建",
    indexed: "已索引",
    finished: "已完成",
    in_progress: "处理中",
    building: "构建中",
    failed: "失败",
    unknown: "未识别",
  };
  return labels[normalized] ?? (value || "未识别");
}

function nodeTypeLabel(value?: string | null) {
  const labels: Record<string, string> = {
    MODULE: "模块",
    TOPIC: "知识点",
    METHOD: "方法",
  };
  return labels[(value ?? "").trim().toUpperCase()] ?? "节点";
}

function retrievalModeLabel(value?: string | null) {
  const normalized = (value ?? "").trim().toLowerCase();
  if (!normalized) {
    return "未检索";
  }
  if (normalized.includes("hybrid")) {
    return "关键词与向量混合检索";
  }
  if (normalized.includes("vector")) {
    return "向量检索";
  }
  if (normalized.includes("keyword") || normalized.includes("bm25")) {
    return "关键词检索";
  }
  return "资料检索";
}

function subjectLabel(value?: string | null) {
  const labels: Record<string, string> = {
    admin: "管理员",
    teacher: "教师",
    student: "学生",
    api_key: "接口密钥",
    guest: "访客",
  };
  return labels[(value ?? "").trim().toLowerCase()] ?? "当前账号";
}

function userFacingError(message: string) {
  if (message.includes("Backend request failed: 403")) {
    return "当前账号没有权限访问这个数据，请切换教师或管理员账号。";
  }
  if (message.includes("Backend request failed: 404")) {
    return "没有找到对应数据，可能尚未入库或已被归档。";
  }
  if (message.includes("Backend request failed: 429")) {
    return "请求过快，系统正在限流保护，请稍后再试。";
  }
  return message.replace(/^Backend request failed:\s*/i, "").slice(0, 180);
}

function difficultyLabel(value?: string | null) {
  const labels: Record<string, string> = {
    easy: "简单",
    medium: "中等",
    hard: "较难",
  };
  return labels[(value ?? "medium").toLowerCase()] ?? (value || "中等");
}
