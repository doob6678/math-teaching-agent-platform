import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { TemplateShelf } from "./App";
import { TeachingHandoutTemplateResponse } from "../shared/api/textbookApi";

vi.mock("pdfjs-dist", () => ({
  GlobalWorkerOptions: { workerSrc: "" },
  getDocument: vi.fn(),
}));

vi.mock("pdfjs-dist/build/pdf.worker.mjs?url", () => ({
  default: "pdf-worker-test.mjs",
}));

describe("TemplateShelf", () => {
  it("renders a filtered handout template bookshelf without exposing local paths", () => {
    const templates: TeachingHandoutTemplateResponse[] = [
      {
        templateCode: "default_standard",
        displayName: "标准讲义",
        sourceType: "builtin",
        audience: "mixed",
        description: "标准教师/学生双版本结构。",
        category: "基础讲义",
        visualStyle: "清爽双栏",
        difficultyBands: ["基础", "提高"],
        tags: ["双版本"],
      },
      {
        templateCode: "local_zhao",
        displayName: "赵礼显导数专题",
        sourceType: "local_reference",
        audience: "mixed",
        description: "来自本机 PDF 参考讲义。",
        category: "专题训练",
        visualStyle: "题型训练",
        difficultyBands: ["提高", "压轴"],
        tags: ["赵礼显", "高考", "导数"],
        referenceTitle: "zhao_lixian_daoshu.pdf",
        referencePath: "C:/Users/doob/Desktop/private/zhao_lixian_daoshu.pdf",
        referencePreview: "题型识别、方法模板、典型例题、变式训练。",
      },
      {
        templateCode: "skill_teacher",
        displayName: "教师讲评 Skill",
        sourceType: "skill_config",
        audience: "teacher",
        description: "动态配置提示词模板。",
        category: "动态模板",
        visualStyle: "教案式",
        difficultyBands: ["基础"],
        tags: ["教师版"],
        referenceTitle: "教师讲评模板",
      },
      {
        templateCode: "student_note",
        displayName: "学生学霸笔记",
        sourceType: "builtin",
        audience: "student",
        description: "学生版知识点和编号练习。",
        category: "学生讲义",
        visualStyle: "学霸笔记",
        difficultyBands: ["基础"],
        tags: ["学生版"],
      },
    ];

    const html = renderToStaticMarkup(
      <TemplateShelf
        templates={templates}
        selectedCode="local_zhao"
        loading={false}
        onSelect={vi.fn()}
      />,
    );

    expect(html).toContain("讲义模板书架");
    expect(html).toContain("当前：赵礼显导数专题");
    expect(html).toContain("全部");
    expect(html).toContain("本机参考");
    expect(html).toContain("动态 Skill");
    expect(html).toContain("教师版");
    expect(html).toContain("学生版");
    expect(html).toContain("高考压轴");
    expect(html).toContain("本机 PDF");
    expect(html).toContain("zhao_lixian_daoshu.pdf");
    expect(html).toContain("参考来源");
    expect(html).toContain("template-selected-preview");
    expect(html).toContain("template-preview-paper");
    expect(html).toContain("template-card-paper");
    expect(html).toContain("katex");
    expect(html).toContain("动态配置提示词模板");
    expect(html).not.toContain("C:/Users/doob/Desktop/private");
  });
});
