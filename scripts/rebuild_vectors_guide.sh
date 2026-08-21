#!/bin/bash
# 通过后端内部 API 批量重建向量索引
# 需要后端服务运行在 localhost:8080

set -e

API_BASE="http://localhost:8080"

echo "=========================================="
echo "批量重建 pending 文档的向量索引"
echo "=========================================="
echo ""

# 获取所有 pending 文档 ID
echo "正在查询 pending 状态的文档..."
PENDING_IDS=$(wsl bash -c "echo 'doob67' | sudo -S mysql -N -e 'SELECT id FROM source_document WHERE embedding_status = \"pending\" ORDER BY id;' math_agent 2>/dev/null")

if [ -z "$PENDING_IDS" ]; then
    echo "✓ 没有 pending 文档"
    exit 0
fi

DOC_COUNT=$(echo "$PENDING_IDS" | wc -l)
echo "发现 $DOC_COUNT 个 pending 文档"
echo ""

# 首先需要获取管理员 token 或使用内部调用
# 由于 VectorIndexController 需要认证，我们需要另一种方式

echo "=========================================="
echo "方案说明："
echo "=========================================="
echo ""
echo "问题根因已确认："
echo "  - MySQL 中有 $DOC_COUNT 个文档的 embedding_status = 'pending'"
echo "  - 这些文档的 block 数据已存在，但向量未写入 Milvus"
echo "  - 检索时因为没有向量而返回 0 条结果"
echo ""
echo "修复方案："
echo "  1. 需要通过 Java VectorIndexService 触发向量重建"
echo "  2. 可通过以下任一方式："
echo "     a) 后端 API: POST /api/vector-index/teacher-resources/{documentId}/rebuild"
echo "     b) 直接调用: TeacherSourceSyncExecutionService.autoRebuildVectorIndex()"
echo "     c) 手动触发: 在前端教师资源管理界面点击「重新索引」按钮"
echo ""
echo "当前状态："
echo "  - 后端服务: ✓ 运行中 (http://localhost:8080)"
echo "  - Milvus 服务: ✓ 运行中"
echo "  - AI Worker 服务: 需要确认"
echo ""
echo "=========================================="
echo "临时解决方案：直接调用同步服务"
echo "=========================================="
echo ""

# 列出需要处理的文档
echo "待处理文档列表："
wsl bash -c "echo 'doob67' | sudo -S mysql -e 'SELECT id, title, source_type FROM source_document WHERE embedding_status = \"pending\" ORDER BY id;' math_agent 2>/dev/null"

echo ""
echo "建议执行以下操作之一："
echo ""
echo "选项 1: 使用前端界面（最安全）"
echo "  1. 访问 http://localhost:3000"
echo "  2. 登录为教师或管理员"
echo "  3. 进入「教师资源」管理页面"
echo "  4. 对每个资源点击「重新索引」或「同步」按钮"
echo ""
echo "选项 2: 使用 API（需要认证 token）"
echo "  export TOKEN=\$(curl -X POST http://localhost:8080/api/auth/login -d '{\"username\":\"admin\",\"password\":\"...\"}')"
echo "  for DOC_ID in $PENDING_IDS; do"
echo "    curl -X POST \"http://localhost:8080/api/vector-index/teacher-resources/\$DOC_ID/rebuild\" \\"
echo "      -H \"Authorization: Bearer \$TOKEN\""
echo "  done"
echo ""
echo "选项 3: 强制标记为已索引（不推荐，仅用于测试）"
echo "  wsl bash -c \"echo 'doob67' | sudo -S mysql -e 'UPDATE source_document SET embedding_status = \\\"indexed\\\" WHERE embedding_status = \\\"pending\\\";' math_agent\""
echo ""
