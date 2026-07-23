# 本机 GPU Embedding 与 BGE Rerank 验证（2026-07-13）

## 结论

本机 GPU 可以用于项目的本地 BGE embedding 和 BGE rerank。此前不能使用的原因不是显卡、驱动或模型权重，而是实际运行的 `ai-worker-python\.venv` 未安装 PyTorch/CUDA 与模型依赖，并且 worker 配置在未设置环境变量时把设备默认设为 `cpu`。

已在项目实际 worker 的 CUDA Python 环境完成 CUDA 可用性、项目服务调用和 CPU/GPU 性能实测。worker 必须使用 `ai-worker-python\.venv\Scripts\python.exe`；不能使用 `D:\conda\envs\py_12\python.exe`，后者的 PyTorch 是 CPU 版。

## 硬件与 CUDA 证据

| 项目 | 实测值 |
| --- | --- |
| GPU | NVIDIA GeForce RTX 5060 Laptop GPU |
| 显存 | 8151 MiB |
| NVIDIA 驱动 | 596.36 |
| 驱动报告的 CUDA | 13.2 |
| Python | 3.12.12 |
| PyTorch | `2.11.0+cu128` |
| PyTorch CUDA 编译版本 | 12.8 |
| `torch.cuda.is_available()` | `True` |

PyTorch 运行时自带 CUDA 12.8 用户态库，不依赖本机另装 CUDA Toolkit；NVIDIA 596.36 驱动能够运行该运行时。

## 已下载与安装的内容

CUDA 版 wheel 已下载到 D 盘，可被其他 Python 虚拟环境复用：

| 内容 | 位置 | 版本/大小 | 校验或来源 |
| --- | --- | --- | --- |
| PyTorch CUDA wheel | `D:\AI-dependencies\pytorch-cu128\torch-2.11.0+cu128-cp312-cp312-win_amd64.whl` | 2.11.0+cu128，2,753,189,216 字节 | SHA-256 `7C78215C3AF4F62E63F2B2E360F1722FC719B0853C7AC22666483D9810613A4C`，与 PyTorch 发布值一致 |
| TorchVision CUDA wheel | `D:\AI-dependencies\pytorch-cu128\torchvision-0.26.0+cu128-cp312-cp312-win_amd64.whl` | 0.26.0+cu128，9.36 MB | SHA-256 `8C0D1C4FBB2C9A4D5D41D0AAA87DA20E525BCB2A154CE405725B0BE59456804B` |
| GPU 下载工具 | 用户级 `aria2` 安装 | 1.37.0 | 用于可靠下载大 wheel |
| worker 依赖 | `C:\Users\doob\Desktop\code\dev\math_agent_rag\ai-worker-python\.venv` | `torch`、`torchvision`、`numpy`、`Pillow`、`transformers`、`sentence-transformers`、`modelscope`、`addict`、`scipy`、`scikit-learn` | `pip check`：`No broken requirements found.` |

以下模型原先已在 D 盘，未重复下载：

| 模型 | 本地路径 | 用途 |
| --- | --- | --- |
| `bge-small-zh-v1.5` | `D:\ModelScope\models\BAAI\bge-small-zh-v1.5` | 文本 embedding |
| `bge-reranker-v2-m3` | `D:\ModelScope\models\BAAI\bge-reranker-v2-m3` | 候选文本重排 |
| `multi-modal_clip-vit-large-patch14_zh` | `D:\ModelScope\models\damo\multi-modal_clip-vit-large-patch14_zh` | CLIP 文本/图像 embedding |

## 已持久化的配置

已写入当前 Windows 用户环境变量。新启动的 PowerShell、worker 或通过 `scripts\local\start-worker.ps1` 启动的进程会自动读取它们；已运行的 worker 必须重启一次。

| 环境变量 | 值 |
| --- | --- |
| `MATH_AGENT_LOCAL_CLIP_DEVICE` | `cuda` |
| `MATH_AGENT_LOCAL_RERANK_DEVICE` | `cuda` |
| `MATH_AGENT_LOCAL_TEXT_EMBEDDING_DEVICE` | `cuda` |
| `MATH_AGENT_LOCAL_CLIP_MODEL_PATH` | `D:\ModelScope\models\damo\multi-modal_clip-vit-large-patch14_zh` |
| `MATH_AGENT_LOCAL_RERANK_MODEL_PATH` | `D:\ModelScope\models\BAAI\bge-reranker-v2-m3` |
| `MATH_AGENT_LOCAL_TEXT_EMBEDDING_MODEL_PATH` | `D:\ModelScope\models\BAAI\bge-small-zh-v1.5` |
| `MATH_AGENT_EMBEDDING_PROVIDER_ORDER` | `local_bge_embedding,local_clip` |
| `MATH_AGENT_WORKER_PYTHON` | `C:\Users\doob\Desktop\code\dev\math_agent_rag\ai-worker-python\.venv\Scripts\python.exe` |

## 项目如何使用 GPU

`scripts\local\start-worker.ps1` 会优先读取 `MATH_AGENT_WORKER_PYTHON`，因此会固定使用项目 CUDA `.venv` 而不是自动选中的 CPU conda 环境。`ai-worker-python/app/settings.py` 从上述环境变量读取设备。`EmbeddingService` 将 `local_bge_embedding` 路由到 `LocalBgeEmbeddingBackend`，其 `SentenceTransformer(..., device=local_text_embedding_device)` 会使用 `cuda`。重排则路由到 `LocalRerankBackend`，模型及 tokenized tensor 都会移动到 `local_rerank_device`，即 `cuda`。

后端默认通过本地 worker 的 `http://127.0.0.1:8091/v1` 请求 embedding。启动 worker 后，文本入库会优先使用 BGE 512 维向量；CLIP 仍可供页面图像检索接口使用。

## 真实验证结果

所有计时均为预热后的同一进程实测；GPU 每轮后调用同步，计时不遗漏异步 CUDA 工作。

| 工作负载 | 批量与序列长度 | CPU 平均推理时间 | GPU 平均推理时间 | GPU 加速 |
| --- | --- | ---: | ---: | ---: |
| BGE embedding，`bge-small-zh-v1.5` | 64 条中文文本 | 135.996 ms | 12.123 ms | **11.22x** |
| BGE rerank，`bge-reranker-v2-m3` | 16 个 query-document 对，`max_length=128` | 1367.941 ms | 55.911 ms | **24.47x** |

还通过项目原有类而非独立模型调用做了真实验证：

- `EmbeddingService.embed()` 返回 `provider=local_bge_embedding`，且向量维度为 `512`。
- `EmbeddingService.rerank()` 返回 `provider=local_bge_reranker`，并产生真实模型分数。
- 两个模型均从上述 D 盘路径加载，GPU 设备配置为 `cuda`。
- 重启后的实际 HTTP worker 已验证：`POST /v1/embeddings` 返回 `local_bge_embedding` 的 512 维向量；`POST /v1/rerank` 返回 `local_bge_reranker` 的两个候选分数。此前 8091 端口上的旧 worker 已停止，避免其 CPU 配置继续被误用。

## 使用注意

1. 必须在新的终端会话或重启后的 worker 中启动，确保用户环境变量已进入进程。
2. 8 GB 显存足以分别执行 BGE embedding 和 rerank。worker 按需加载模型；避免在同一进程长期同时常驻 CLIP 大模型、BGE reranker 与其他大模型，以保留显存余量。
3. `bge-reranker-v2-m3` 是 2.27 GB 权重，首个请求需要加载模型；上表仅描述模型已加载后的推理性能，不把首次加载时间混入吞吐比较。
4. `MATH_AGENT_LOCAL_RERANK_MAX_TOKENS` 维持项目现有 `128`。这是显式计算预算，不是相关性规则；需要更长候选文本时应评估显存和延迟后再提高。
