#!/bin/bash
# 阶段1 WSL 侧真实探测：docker 容器状态 + 容器网络监听端口。只读，不启停服务。
echo "===== docker ps (name,status,ports) ====="
docker ps --format '{{.Names}}\t{{.Status}}\t{{.Ports}}' 2>&1
echo
echo "===== ss -tlnp (WSL 侧监听) ====="
ss -tlnp 2>/dev/null | grep -E ':19530|:3306|:6379|:5672|:15672|:8080|:8091|:9091' | sort
echo
echo "===== 容器内 health 直探 (docker exec curl) ====="
# backend 容器内 8080
docker exec math-agent-rag-backend-1 curl -s -m 5 -o /dev/null -w "backend_inner_8080 http=%{http_code} time=%{time_total}s\n" http://127.0.0.1:8080/api/system/health 2>&1 || echo "backend exec/curl 不可用(容器名或无curl)"
echo
echo "===== docker stats --no-stream (一次) ====="
docker stats --no-stream --format '{{.Name}}\tCPU={{.CPUPerc}}\tMEM={{.MemUsage}}\tNET={{.NetIO}}' 2>&1
