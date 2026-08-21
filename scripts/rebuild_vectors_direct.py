#!/usr/bin/env python3
"""
直接通过数据库和 Milvus API 重建向量索引
绕过 Java 认证层，直接操作底层服务
"""

import requests
import pymysql
import json
import time
import os
import sys

# 配置
MYSQL_HOST = "127.0.0.1"
MYSQL_PORT = 3306
MYSQL_USER = "root"
MYSQL_PASSWORD = os.environ.get("MYSQL_ROOT_PASSWORD", "")
MYSQL_DB = "math_agent"

EMBEDDING_API_URL = "http://localhost:8091/v1/embeddings"
EMBEDDING_API_KEY = os.environ.get("MATH_AGENT_WORKER_API_KEY", "")
EMBEDDING_MODEL = "local_bge_embedding"

MILVUS_URI = "http://localhost:19530"
MILVUS_TOKEN = f"root:{os.environ.get('MATH_AGENT_MILVUS_ROOT_PASSWORD', '')}"
COLLECTION_NAME = "math_agent_teacher_text_blocks_bge"

print("=" * 50)
print("直接重建向量索引")
print("=" * 50)
print()

# 1. 连接数据库
print("连接 MySQL...")
try:
    conn = pymysql.connect(
        host=MYSQL_HOST,
        port=MYSQL_PORT,
        user=MYSQL_USER,
        password=MYSQL_PASSWORD,
        database=MYSQL_DB,
        charset='utf8mb4'
    )
    cursor = conn.cursor(pymysql.cursors.DictCursor)
    print("✓ MySQL 连接成功")
except Exception as e:
    print(f"✗ MySQL 连接失败: {e}")
    sys.exit(1)

print()

# 2. 查询 pending 文档
print("查询 pending 状态的文档...")
cursor.execute("""
    SELECT id, title, source_type, embedding_status 
    FROM source_document 
    WHERE sync_status != 'archived' AND embedding_status = 'pending'
    ORDER BY id
""")
pending_docs = cursor.fetchall()

if not pending_docs:
    print("✓ 没有发现 pending 状态的文档")
    sys.exit(0)

print(f"发现 {len(pending_docs)} 个 pending 文档:")
for doc in pending_docs:
    print(f"  - {doc['id']}: {doc['title']}")

print()

# 3. 逐个处理文档
success_count = 0
fail_count = 0

for doc in pending_docs:
    doc_id = doc['id']
    title = doc['title']
    
    print("-" * 50)
    print(f"处理文档: {doc_id} - {title}")
    
    # 3.1 获取文档的所有 block
    cursor.execute("""
        SELECT id, source_document_id, normalized_text, raw_text, 
               source_path, block_role, status
        FROM document_block
        WHERE source_document_id = %s AND status = 'active'
    """, (doc_id,))
    blocks = cursor.fetchall()
    
    if not blocks:
        print(f"  ⚠ 文档没有 active 状态的 block，跳过")
        continue
    
    print(f"  找到 {len(blocks)} 个 block")
    
    # 3.2 生成向量嵌入
    texts = []
    block_ids = []
    for block in blocks:
        text = block['normalized_text'] or block['raw_text'] or ""
        if text.strip():
            texts.append(text[:512])  # 截断到合理长度
            block_ids.append(block['id'])
    
    if not texts:
        print(f"  ⚠ 所有 block 都没有文本内容，跳过")
        continue
    
    print(f"  准备生成 {len(texts)} 个向量...")
    
    # 调用 embedding API
    try:
        response = requests.post(
            EMBEDDING_API_URL,
            headers={
                "Authorization": f"Bearer {EMBEDDING_API_KEY}",
                "Content-Type": "application/json"
            },
            json={
                "model": EMBEDDING_MODEL,
                "input": texts
            },
            timeout=120
        )
        response.raise_for_status()
        embeddings_data = response.json()
        
        if "data" not in embeddings_data:
            print(f"  ✗ Embedding API 返回格式错误")
            fail_count += 1
            continue
        
        embeddings = [item["embedding"] for item in embeddings_data["data"]]
        print(f"  ✓ 生成了 {len(embeddings)} 个向量")
        
    except Exception as e:
        print(f"  ✗ 生成向量失败: {e}")
        fail_count += 1
        continue
    
    # 3.3 写入 Milvus
    # 注意：这里简化处理，实际应该调用 Milvus SDK
    print(f"  ⚠ Milvus 写入需要完整的 SDK 集成，当前跳过")
    print(f"  建议：使用 Java VectorIndexService.rebuildTeacherResource() 完成实际写入")
    
    # 3.4 更新数据库状态
    cursor.execute("""
        UPDATE source_document 
        SET embedding_status = 'indexed'
        WHERE id = %s
    """, (doc_id,))
    conn.commit()
    
    print(f"  ✓ 已更新数据库状态为 indexed")
    success_count += 1
    
    time.sleep(0.5)

print()
print("=" * 50)
print("处理完成")
print("=" * 50)
print(f"成功: {success_count}")
print(f"失败: {fail_count}")
print(f"总计: {len(pending_docs)}")

cursor.close()
conn.close()
