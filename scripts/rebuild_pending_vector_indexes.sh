#!/bin/bash
# 批量重建所有 pending 状态文档的向量索引

set -e

# 配置
API_BASE="http://localhost:8080"
TENANT_ID="default"

echo "=========================================="
echo "批量重建 pending 文档的向量索引"
echo "=========================================="

# 1. 获取所有 pending 状态的文档 ID
echo ""
echo "正在查询 pending 状态的文档..."
PENDING_DOCS=$(wsl bash -c "echo 'doob67' | sudo -S mysql -N -e 'SELECT id FROM source_document WHERE sync_status != \"archived\" AND embedding_status = \"pending\";' math_agent 2>/dev/null")

if [ -z "$PENDING_DOCS" ]; then
    echo "✓ 没有发现 pending 状态的文档，所有文档向量索引都是最新的！"
    exit 0
fi

DOC_COUNT=$(echo "$PENDING_DOCS" | wc -l)
echo "发现 $DOC_COUNT 个 pending 状态的文档需要重建向量索引"
echo ""

# 2. 逐个触发向量索引重建
SUCCESS_COUNT=0
FAIL_COUNT=0

for DOC_ID in $PENDING_DOCS; do
    echo "----------------------------------------"
    echo "处理文档 ID: $DOC_ID"
    
    # 先查询文档标题
    DOC_TITLE=$(wsl bash -c "echo 'doob67' | sudo -S mysql -N -e 'SELECT title FROM source_document WHERE id = $DOC_ID;' math_agent 2>/dev/null")
    echo "文档标题: $DOC_TITLE"
    
    # 触发向量索引重建（使用 curl）
    echo "正在触发向量索引重建..."
    RESPONSE=$(curl -s -X POST "$API_BASE/api/vector-index/teacher-resources/$DOC_ID/rebuild" \
        -H "Content-Type: application/json" \
        -w "\nHTTP_CODE:%{http_code}" \
        2>/dev/null || echo "HTTP_CODE:000")
    
    HTTP_CODE=$(echo "$RESPONSE" | grep "HTTP_CODE:" | cut -d: -f2)
    BODY=$(echo "$RESPONSE" | grep -v "HTTP_CODE:")
    
    if [ "$HTTP_CODE" = "200" ]; then
        echo "✓ 向量索引重建成功"
        SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
        
        # 显示索引详情
        INDEXED_COUNT=$(echo "$BODY" | grep -o '"indexedCount":[0-9]*' | cut -d: -f2 || echo "0")
        echo "  已索引向量数: $INDEXED_COUNT"
        
        # 更新数据库状态
        wsl bash -c "echo 'doob67' | sudo -S mysql -e 'UPDATE source_document SET embedding_status = \"indexed\" WHERE id = $DOC_ID;' math_agent 2>/dev/null"
    else
        echo "✗ 向量索引重建失败 (HTTP $HTTP_CODE)"
        echo "  响应: $BODY"
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
    
    echo ""
    sleep 1
done

# 3. 汇总结果
echo "=========================================="
echo "批量重建完成！"
echo "=========================================="
echo "成功: $SUCCESS_COUNT"
echo "失败: $FAIL_COUNT"
echo "总计: $DOC_COUNT"
echo ""

# 4. 验证最终状态
echo "验证最终向量索引状态..."
FINAL_PENDING=$(wsl bash -c "echo 'doob67' | sudo -S mysql -N -e 'SELECT COUNT(*) FROM source_document WHERE sync_status != \"archived\" AND embedding_status = \"pending\";' math_agent 2>/dev/null")
FINAL_INDEXED=$(wsl bash -c "echo 'doob67' | sudo -S mysql -N -e 'SELECT COUNT(*) FROM source_document WHERE sync_status != \"archived\" AND embedding_status IN (\"indexed\", \"ready\");' math_agent 2>/dev/null")

echo "  Pending: $FINAL_PENDING"
echo "  Indexed/Ready: $FINAL_INDEXED"
echo ""

if [ "$FINAL_PENDING" = "0" ]; then
    echo "✓✓✓ 所有文档向量索引已完成！"
    exit 0
else
    echo "⚠ 仍有 $FINAL_PENDING 个文档待索引"
    exit 1
fi
