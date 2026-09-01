/// <reference types="vite/client" />

declare module "lucide-react" {
  import type { ComponentType, SVGProps } from "react";

  export interface LucideProps extends SVGProps<SVGSVGElement> {
    size?: number | string;
    strokeWidth?: number | string;
  }

  export type LucideIcon = ComponentType<LucideProps>;

  export const AlertCircle: LucideIcon;
  export const BookOpen: LucideIcon;
  export const Database: LucideIcon;
  export const Loader2: LucideIcon;
  export const Search: LucideIcon;
  export const ShieldCheck: LucideIcon;
  export const Home: LucideIcon;
  export const Bot: LucideIcon;
  export const GitBranch: LucideIcon;
  export const Library: LucideIcon;
  export const FolderKanban: LucideIcon;
  export const Settings: LucideIcon;
  export const User: LucideIcon;
  export const LogOut: LucideIcon;
  export const Bell: LucideIcon;
  export const ChevronDown: LucideIcon;
  export const ChevronLeft: LucideIcon;
  export const ChevronRight: LucideIcon;
  export const KeyRound: LucideIcon;
  export const Menu: LucideIcon;
  export const X: LucideIcon;
  export const Plus: LucideIcon;
  export const Check: LucideIcon;
  export const LayoutDashboard: LucideIcon;
  export const GraduationCap: LucideIcon;
  export const BrainCircuit: LucideIcon;
  export const FileText: LucideIcon;
  export const Sparkles: LucideIcon;
  export const ExternalLink: LucideIcon;
  export const RefreshCw: LucideIcon;
  export const Copy: LucideIcon;
  export const Globe: LucideIcon;
  export const BookMarked: LucideIcon;
  export const Network: LucideIcon;
  export const PanelRightOpen: LucideIcon;
  export const PanelRightClose: LucideIcon;
  export const ArrowLeft: LucideIcon;
  export const ArrowRight: LucideIcon;
  export const ZoomIn: LucideIcon;
  export const ZoomOut: LucideIcon;
  export const Clock: LucideIcon;
  export const History: LucideIcon;
  export const Download: LucideIcon;
  export const Eye: LucideIcon;
  export const Lightbulb: LucideIcon;
  export const ArrowUp: LucideIcon;
  export const CheckCircle2: LucideIcon;
  export const XCircle: LucideIcon;
}
