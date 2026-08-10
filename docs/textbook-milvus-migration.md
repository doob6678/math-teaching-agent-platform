# 教材 Milvus 迁移验收记录

本记录对应生产 c2 section-child corpus。运行环境为 Windows 后端、WSL Docker 的 Milvus/MySQL/Redis，以及本机 CUDA Worker。

## 运行态

- `textbook_text_collection`：`id` 为 c2 子块 `chunkId`，`FloatVector(512)`，`COSINE`、`FLAT`。
- `textbook_image_collection`：`id` 为 `docId + pageNo + sourcePageImage`，`FloatVector(768)`，`COSINE`、`FLAT`。
- 两个 collection 都有 `text` 与 `metadata` JSON 字段；文本 metadata 保存 `docId`、`bookName`、`chapterPath`、`sectionId`、`parentSectionId`、`pageNo`、`printedPageNo`、`sectionTitle`、`chunkType`、`formulaText`、`sourcePath`、`corpusVersion`。图片 metadata 保存 `docId`、`bookName`、`chapterPath`、`sectionId`、`pageNo`、`sourcePageImage`、`corpusVersion`。
- `application.yml` 固化 URI、collection、维度、`FLAT/COSINE`、topK、批大小、超时、corpusVersion；Milvus token 仅从 `MATH_AGENT_MILVUS_TOKEN` 读取。
- `textbook-corpus-version` 固定为 `textbook-section-c2-milvus-v1`。Java、worker、迁移脚本和 benchmark 必须共同指向 c2。

真实 c2 语料统计：8 本教材、section 子块 3317、唯一 section_id 1049；section 索引存在重复 legacy `chunkId`，迁移按正文最长的同主键记录确定性折叠，避免最后写入覆盖产生幽灵向量。页面图片索引 1121 条，与文本 section 子块数量不是同一统计口径。

## 默认检索链路

`TextbookPageTextSearchService` 和 `TextbookPageImageSearchService` 均调用 `TextbookMilvusSearchClient`：Worker 只生成 BGE/CLIP 查询向量，Java 使用 Milvus REST 查询两个 collection。正文 BM25、小标题 BM25、父子块聚合和 BGE Reranker 未改动。

`_section_bge_index/embeddings.npy` 是文本迁移和基线评测的唯一生产文本源；`_page_image_index/page_embeddings.npy` 是图片迁移和图片基线源。默认 Java 在线路径不读取 NPY。图片历史 768 维语料与当前 512 维 CLIP 查询通过“512 公共前缀归一化 + 零填充至 768”迁移，和旧 NPY 公共前缀 cosine 定义等价。

## 实测

生产 benchmark 必须通过真实 backend HTTP 端点执行，并同时记录 `corpusVersion`、实际 retrieval stages、section_id 命中、源页码和 GPU worker 状态。只比较 NPY 与 Milvus 的旧页级结果不能证明生产链路正确。

最终真实生产验收报告：

- 报告：`output/benchmarks/textbook-production-c2-20260804-recall135/report.json`
- 摘要：`output/benchmarks/textbook-production-c2-20260804-recall135/summary.md`
- 后端健康：`{"status":"UP","mode":"deploy_ready","blockingIssues":""}`
- 真实 HTTP 请求：46/46 成功；GPU：NVIDIA GeForce RTX 5060 Laptop GPU。
- 语料：8 本教材、原始 section-child 3317 条、唯一 `section_id` 1049 条；Milvus 文本 3116 条唯一向量（512 维），图片 1121 条向量（768 维）。
- 检索策略：`redis_cache_two_stage_doc_page_v4_bounded_semantic_first_parent_rerank` 或未命中缓存时的 `two_stage_doc_page_v4_bounded_semantic_first_parent_rerank`。

| 指标 | @1 | @3 | @5 | MRR |
|---|---:|---:|---:|---:|
| 文档 | 0.783 | 0.957 | 0.957 | 0.862 |
| 页面 | 0.413 | 0.674 | 0.717 | 0.539 |
| 小标题块/逻辑父块 | 0.674 | 0.848 | 0.935 | 0.778 |

延迟为平均 273.881 ms、P50 313.652 ms、P95 438.2 ms、P99 671.359 ms。每条结果均来自生产 HTTP 检索链路，记录了 BGE 页面召回、CLIP 页面图像召回、父块聚合、BGE rerank 的实际阶段状态；未使用 mock、fake 或伪造分数。既定质量门禁未通过：文档 Recall@1 为 0.783（门槛 0.800），父/小标题块 Recall@1 为 0.674（门槛 0.700），父/小标题块 Recall@3 为 0.848（门槛 0.850）。

报告至少包含：c2 section-child 行数、Milvus 文本/图片实体数、真实查询数、Recall@1/3/5、MRR、P50/P95、每次检索的阶段状态，以及重复 chunkId 去重统计。

## 语料门禁

生产代码不提供页级文本语料回退。迁移脚本要求 `_section_bge_index/manifest.json` 的 kind 为 `bge_section_chunk_library`；Java 加载真实教材根目录时执行同样的 manifest 门禁。外部教材目录不由本 workspace 删除，避免越界修改参考资料；项目内部不会再把旧页级语料作为默认目录、Milvus 文本源或 benchmark 基线。
