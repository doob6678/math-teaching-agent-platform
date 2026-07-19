# 教材 Milvus 迁移验收记录

迁移时间：2026-07-19。运行环境为 Windows 后端、WSL Docker 的 Milvus/MySQL/Redis，以及本机 CUDA Worker。

## 运行态

- `textbook_text_collection`：`id` 为 `chunkId`，`FloatVector(512)`，`COSINE`、`FLAT`。
- `textbook_image_collection`：`id` 为 `docId + pageNo + sourcePageImage`，`FloatVector(768)`，`COSINE`、`FLAT`。
- 两个 collection 都有 `text` 与 `metadata` JSON 字段；文本 metadata 保存 `docId`、`bookName`、`chapterPath`、`sectionId`、`parentSectionId`、`pageNo`、`printedPageNo`、`sectionTitle`、`chunkType`、`formulaText`、`sourcePath`、`corpusVersion`。图片 metadata 保存 `docId`、`bookName`、`chapterPath`、`sectionId`、`pageNo`、`sourcePageImage`、`corpusVersion`。
- `application.yml` 固化 URI、collection、维度、`FLAT/COSINE`、topK、批大小、超时、corpusVersion；Milvus token 仅从 `MATH_AGENT_MILVUS_TOKEN` 读取。

真实迁移统计：15 本教材、文本源行 3267、唯一 `chunkId` 3076、图片页 1121。Milvus `count(*)` 实测为文本 3076、图片 1121。源数据有 191 个重复 `chunkId` 行；由于 collection 主键必须是 `chunkId`，迁移按正文最长的同主键记录确定性折叠，避免最后写入覆盖产生幽灵向量。

## 默认检索链路

`TextbookPageTextSearchService` 和 `TextbookPageImageSearchService` 均调用 `TextbookMilvusSearchClient`：Worker 只生成 BGE/CLIP 查询向量，Java 使用 Milvus REST 查询两个 collection。正文 BM25、小标题 BM25、父子块聚合和 BGE Reranker 未改动。

`_page_text_index/page_embeddings.npy` 与 `_page_image_index/page_embeddings.npy` 只由离线迁移和基线评测脚本读取；默认 Java 在线路径不读取它们。图片历史 768 维语料与当前 512 维 CLIP 查询通过“512 公共前缀归一化 + 零填充至 768”迁移，和旧 NPY 公共前缀 cosine 定义等价。

## 实测

五个端到端问题均完成 BM25、Milvus BGE、Milvus CLIP 与 Reranker：平面向量的基本定理、正弦定理的应用条件、椭圆的标准方程、导数的几何意义、等差数列前 n 项和。

离线基线对照结果保存在 `textbook-milvus-recall-verification.json`：

- 文本：Recall@10 0.98、MRR 0.80、Top1 0.80、Top3 0.80、NPY P50/P95 0.723/0.749 ms、Milvus P50/P95 22.273/364.487 ms。
- 图片：Recall@10 1.00、MRR 1.00、Top1 1.00、Top3 1.00、NPY P50/P95 0.472/0.482 ms、Milvus P50/P95 7.966/215.296 ms。

文本唯一偏差来自正弦定理样本中的重复 `chunkId`：本地旧矩阵可同时返回重复行，而主键约束下 Milvus 只能保留一个同 ID 记录；该差异已作为数据质量指标记录，非 ANN 召回漂移。图片路径已通过 `FLAT` 精确索引实现相同 Top1/Top3/Top10 集合。

## 回滚

回滚只需将两个 Java 页面检索服务切回 Worker 的旧 page-search 端点并重启后端；不会删除 Milvus collection 或离线 NPY 基线。重建脚本会按 `docId` 删除后重写实体，不删除 collection。
