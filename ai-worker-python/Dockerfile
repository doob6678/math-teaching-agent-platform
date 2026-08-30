FROM math-agent-rag-ai-worker:deps-20260827
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY requirements.txt ./
# The dependency base is validated once with GPU runtimes installed; source-only rebuilds must not reinstall them.
COPY app ./app
RUN mkdir -p /app/data/usage
EXPOSE 8091
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=12 \
    CMD curl --fail --silent http://127.0.0.1:8091/health || exit 1
CMD ["uvicorn", "app.server:app", "--host", "0.0.0.0", "--port", "8091"]
