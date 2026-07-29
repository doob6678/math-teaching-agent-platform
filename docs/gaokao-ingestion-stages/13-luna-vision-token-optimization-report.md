# 阶段 13：Luna 页图 token 压缩真实实验

## 目的

降低页面初筛的视觉 token 和网络负载，同时验证题号、版式和跨页风险结论没有因压缩丢失。压缩只作用于派生图，原 PDF、原页 PNG 与题目高保真裁图均不覆盖。

## 固定参数

递进实验后的安全参数写入 `config/gaokao-ingestion-2024.json`：最大长边 960 px、JPEG quality 0.82。该参数只用于“整页初筛”；几何图、公式、跨页和低置信度题必须回退到原页/单题高分辨率图。

## 同页真实对照

对象是北京空白卷第 1 页，两个请求使用相同模型 `gpt-5.6-luna`、相同页面级提示词和相同返回 schema。

| 指标 | 原始 PNG | 1280px/0.88 | 960px/0.82（通过） | 768px/0.78（失败） |
|---|---:|---:|---:|---:|
| 尺寸 | 1322×1870 | 905×1280 | 679×960 | 543×768 |
| 文件大小（bytes） | 209,667 | 119,394 | 71,877 | 46,763 |
| prompt token | 3,130 | 1,549 | 949 | 646 |
| total token | 3,296 | 1,787 | 1,196 | 876 |
| 耗时（ms） | 18,458 | 12,947 | 8,573 | 8,809 |
| 题号 1–7 | 正确 | 正确 | 正确 | 正确 |
| 第 7 题跨页风险 | 正确 | 正确 | 正确 | **错误丢失** |

1280px/0.88 与 960px/0.82 都返回题号 1–7、单栏，并标记第 7 题底部不完整/可能跨页。960px/0.82 的 prompt token 为 949，比原始图少 69.7%，是该页当前已验证的最低安全设置。

768px/0.78 虽仍返回题号 1–7，却错误称第 7 题“完整包含在页面内”，丢失了原图和较高分辨率图均识别出的跨页风险。它的 prompt token 虽降至 646，但被判为准确性失败，禁止用于初筛。该失败点说明不能只以题号识别作为压缩验收条件。

## 完整证据

- 原始请求：`output/gaokao-evidence/2024/luna-2024-visual-page-audit.json`
- 优化请求：`output/gaokao-evidence/2024/luna-2024-beijing-page-1-1280-q88-visual-audit.json`
- 960px 通过请求：`output/gaokao-evidence/2024/luna-2024-beijing-page-1-960-q82-visual-audit.json`
- 768px 失败请求：`output/gaokao-evidence/2024/luna-2024-beijing-page-1-768-q78-visual-audit.json`
- 原始页图：`output/gaokao-evidence/2024/beijing-blank-page-1.png`
- 优化派生图：`output/gaokao-evidence/2024/beijing-blank-page-1-1280-q88.jpg`

两份交互均包含完整 request shape、data URL、response、HTTP status、usage 与耗时，认证凭据已排除。
