#!/bin/bash
# 压测期间资源采样器：每 2s 采一次 docker stats --no-stream，追加时间戳。只读。
# 用法：wsl bash /mnt/c/.../stats_sampler.sh <输出文件 /mnt/c 路径>
OUT="$1"
ITER="${2:-120}"
for i in $(seq 1 "$ITER"); do
  {
    echo "=== sample #$i t=$(date +%H:%M:%S) ==="
    docker stats --no-stream --format '{{.Name}}\tCPU={{.CPUPerc}}\tMEM={{.MemUsage}}\tNET={{.NetIO}}\tBLK={{.BlockIO}}' 2>&1 \
      | grep -E 'backend|ai-worker|milvus-1|rabbitmq|mysql|redis'
  } >> "$OUT"
  sleep 2
done
echo "sampler done" >> "$OUT"
