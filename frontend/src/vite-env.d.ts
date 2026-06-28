/// <reference types="vite/client" />

declare module "lucide-react" {
  import type { ComponentType, SVGProps } from "react";

  /** Lucide 图标组件的通用属性，继承标准 SVG 属性并支持 size/strokeWidth。 */
  export interface LucideProps extends SVGProps<SVGSVGElement> {
    /** 图标宽高，默认由 lucide-react 内部决定。 */
    size?: number | string;
    /** SVG 线条宽度，用于控制图标视觉权重。 */
    strokeWidth?: number | string;
  }

  /** 单个 Lucide 图标组件类型。 */
  export type LucideIcon = ComponentType<LucideProps>;

  export const AlertCircle: LucideIcon;
  export const BookOpen: LucideIcon;
  export const Database: LucideIcon;
  export const Loader2: LucideIcon;
  export const Search: LucideIcon;
  export const ShieldCheck: LucideIcon;
}
