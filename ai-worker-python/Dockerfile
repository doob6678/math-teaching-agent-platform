FROM python:3.12-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.txt ./
# 禁止恢复 DashScope embedding；生产镜像必须安装 Torch/Transformers 并只加载挂载到 /models 的本地权重。
# Do not restore DashScope embeddings; the production image must install Torch/Transformers for /models weights.
# Preserve Docker's package cache and use bounded retries: local model weights are mounted from /models, while this
# layer installs only the Python runtimes needed to execute those local models on slow or intermittently connected WSL.
RUN pip install --retries 8 --timeout 120 -r requirements.txt
COPY app ./app
RUN mkdir -p /app/data/usage
EXPOSE 8091
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=12 \
    CMD curl --fail --silent http://127.0.0.1:8091/health || exit 1
CMD ["uvicorn", "app.server:app", "--host", "0.0.0.0", "--port", "8091"]
