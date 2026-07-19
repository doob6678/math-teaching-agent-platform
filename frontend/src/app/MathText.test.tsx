import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { MathText, splitFeishuMath } from "./App";

describe("MathText", () => {
  it("splits only Feishu-supported dollar math delimiters", () => {
    const segments = splitFeishuMath("先用 $x^2$，再看 $$x^2-4x+3=0$$，不要解析 \\[x\\]。");

    expect(segments.filter((segment) => segment.math)).toHaveLength(2);
    expect(segments.some((segment) => segment.text.includes("\\[x\\]"))).toBe(true);
  });

  it("renders inline and display formulas with KaTeX", () => {
    const html = renderToStaticMarkup(<p><MathText text="零点满足 $f(x)=0$，即 $$x^2-4x+3=0$$。" /></p>);

    expect(html).toContain("katex");
    expect(html).toContain("katex-display");
    expect(html).toContain("零点满足");
  });

  it("does not render unsupported bracket delimiters", () => {
    const html = renderToStaticMarkup(<p><MathText text="不支持 \\[x^2\\] 这种包装。" /></p>);

    expect(html).not.toContain("katex");
    expect(html).toContain("\\\\[x^2\\\\]");
  });
});
