# Luna 讲义 PDF 验收记录（2026-07-28）

真实已完成 Luna 工作流 `6bacb6c7-78e6-4116-9a64-c4c69831ea8e`（模型 `gpt-5.6-luna`）用于本次导出验收。所有文件均由运行中的本地后端 HTTP 导出接口生成，随后以 Poppler 渲染首屏人工检查。

| 版式 | 文件 | SHA-256 | 验收 |
| --- | --- | --- | --- |
| 教师讲义 | `output/pdf/luna-handout-profiles/教师讲义.pdf` | `edc8a1f2be29c72b956ad4f5758639b34e3110ff45e450f3ea97249c1ff128ff` | 中文、标题、公式、页码正常 |
| 学生空白讲义 | `output/pdf/luna-handout-profiles/学生空白讲义.pdf` | `c8a863b8f91bd0b7d19b3576e3e0104e36afeb01f28d23cfd2a543ae054220ff` | 无答案、无教师信息；向量和下标正常 |
| 16:10 单题引导 | `output/pdf/luna-handout-profiles/16比10单题引导.pdf` | `604ea8000d31a327cc30e88fc47e4f13b35fa64aa6b816bde5b145f9caa46f7a` | 单题、无最终答案；真实课题标题正常 |

本次修复覆盖：唯一标题渲染、JSON 字段中文化、前置/后置向量箭头、Unicode 下标转 LaTeX、以及控制标记过滤。验收截图位于 `tmp/pdfs/luna-handout-profiles-final/`，为临时产物，不纳入版本控制。
