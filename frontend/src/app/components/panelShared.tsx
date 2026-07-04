import { ReactNode } from "react";

export function boundedPercent(value: number) {
  return Math.max(0, Math.min(100, value));
}

export function countJsonArray(value: string) {
  try {
    const parsed = JSON.parse(value || "[]");
    return Array.isArray(parsed) ? parsed.length : 0;
  } catch {
    return 0;
  }
}

export function formatSimilarity(value?: number) {
  return value === undefined ? "0.0000" : value.toFixed(4);
}

export function formatDateTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false });
}

export function stageLabel(stage: string) {
  const labels: Record<string, string> = {
    memory_reuse: "记忆复用",
    reuse_short_circuit: "复用短路",
    textbook_retrieval: "教材检索",
    react_trace: "ReAct 轨迹",
    handout_generation: "讲义生成",
  };
  return labels[stage] ?? stage;
}

export function PanelTitle({ icon, title }: { icon: ReactNode; title: string }) {
  return (
    <div className="panel-title">
      {icon}
      <span>{title}</span>
    </div>
  );
}

export function Metric({ label, value }: { label: string; value: number }) {
  return (
    <div className="metric">
      <span>{label}</span>
      <strong>{value.toLocaleString("zh-CN")}</strong>
    </div>
  );
}

export function StatusLine({
  icon,
  text,
  tone = "muted",
}: {
  icon: ReactNode;
  text: string;
  tone?: "muted" | "danger";
}) {
  return (
    <div className={`status-line ${tone}`}>
      {icon}
      <span>{text}</span>
    </div>
  );
}

export function StatusBadge({ status }: { status: string }) {
  return <span className={`quality-badge ${statusTone(status)}`}>{status || "unknown"}</span>;
}

export function statusClass(status: string) {
  const normalized = normalizeStatus(status);
  if (isFailureStatus(normalized)) {
    return "failed";
  }
  if (isCompletedStatus(normalized)) {
    return "completed";
  }
  if (isRunningStatus(normalized)) {
    return "running";
  }
  return "unknown";
}

export function statusTone(status: string) {
  const normalized = normalizeStatus(status);
  if (isFailureStatus(normalized)) {
    return "danger";
  }
  if (isCompletedStatus(normalized)) {
    return "good";
  }
  return "warn";
}

function normalizeStatus(status: string) {
  return (status || "").trim().toLowerCase();
}

function isFailureStatus(status: string) {
  return status.includes("fail")
    || status.includes("error")
    || status.includes("reject")
    || status.includes("denied")
    || status.includes("expired")
    || status.includes("unavailable")
    || status === "unreachable"
    || status === "configuration_error";
}

function isCompletedStatus(status: string) {
  return new Set([
    "active",
    "completed",
    "complete",
    "deploy_ready",
    "loaded",
    "process_ready",
    "ready",
    "reachable",
    "searchable",
    "success",
    "succeeded",
  ]).has(status);
}

function isRunningStatus(status: string) {
  return status.includes("running")
    || status.includes("pending")
    || status.includes("queued")
    || status.includes("in_progress")
    || status === "processing";
}
