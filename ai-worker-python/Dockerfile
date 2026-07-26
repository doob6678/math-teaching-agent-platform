FROM python:3.12-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY requirements-runtime.txt ./
# Compose leaves local model paths empty and uses the real remote embedding provider. Keep torch/transformers out of
# this runtime image; model-enabled deployments install requirements.txt and mount their weights explicitly.
RUN pip install --no-cache-dir -r requirements-runtime.txt
COPY app ./app
RUN mkdir -p /app/data/usage
EXPOSE 8091
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=12 \
    CMD curl --fail --silent http://127.0.0.1:8091/health || exit 1
CMD ["uvicorn", "app.server:app", "--host", "0.0.0.0", "--port", "8091"]
