FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY backend-java/pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY backend-java/src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    # A PDF must never be "successfully" exported by exposing raw LaTeX commands or missing Chinese glyphs.
    # Keep the compiler in the same Linux runtime as the backend so the production path is deterministic.
    && apt-get install -y --no-install-recommends \
        curl \
        python3 \
        python3-requests \
        # DOCX is rendered in this Linux image before entering the common PDF/PNG source pipeline; do not depend on
        # Windows Word or PowerShell for a Docker production run.
        libreoffice-writer \
        texlive-xetex \
        texlive-lang-chinese \
        fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/target/*.jar /app/math-agent-rag.jar
COPY ai-worker-python/scripts/download_feishu_url.py /app/scripts/download_feishu_url.py
RUN mkdir -p /app/data/teacher-source-imports /app/data/teacher-assets
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=12 \
    CMD curl --fail --silent http://127.0.0.1:8080/api/system/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/math-agent-rag.jar"]
