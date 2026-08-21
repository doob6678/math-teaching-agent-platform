#!/usr/bin/env python3
"""
修复脚本：从 source_document 表生成 .source-file-catalog.json

根本原因：
- TeacherSourceFileReader 依赖 .source-file-catalog.json 文件来读取文档注册信息
- VectorIndexService.rebuildTeacherResource 会调用 sourceFileReader.register() 注册文档
- 但历史文档没有被注册到 catalog 文件中，导致 handout-document-read 工具返回 400 错误

修复方案：
1. 从 source_document 表读取所有有 local_path 的文档
2. 生成符合 TeacherSourceFileReader 期望格式的 catalog 文件
3. 写入到默认路径 .local-storage/teacher-source-imports/.source-file-catalog.json
"""

import pymysql
import json
import os
from pathlib import Path

# 数据库配置
DB_CONFIG = {
    'host': '127.0.0.1',
    'port': 3307,
    'user': 'root',
    'password': 'local_mysql_7f9b1cc8a2d44529b6e481d5',
    'database': 'math_agent_rag',
    'charset': 'utf8mb4'
}

# catalog 文件路径
CATALOG_PATH = Path(__file__).parent.parent / '.local-storage' / 'teacher-source-imports' / '.source-file-catalog.json'

def main():
    print('=' * 80)
    print('修复教材文档注册问题')
    print('=' * 80)
    
    # 连接数据库
    print('\n1. 连接数据库...')
    conn = pymysql.connect(**DB_CONFIG)
    cursor = conn.cursor()
    
    # 查询所有有 local_path 的文档
    print('2. 查询 source_document 表...')
    cursor.execute('''
        SELECT id, tenant_id, local_path, checksum
        FROM source_document
        WHERE local_path IS NOT NULL AND local_path != ""
        ORDER BY id
    ''')
    
    rows = cursor.fetchall()
    print(f'   找到 {len(rows)} 条有 local_path 的文档记录')
    
    # 构建 catalog 数据结构
    print('3. 构建 catalog 数据结构...')
    catalog = {}
    for row in rows:
        doc_id = str(row[0])
        tenant_id = row[1]
        local_path = row[2]
        checksum = row[3] if row[3] else ''
        
        catalog[doc_id] = {
            'tenantId': tenant_id,
            'documentId': doc_id,
            'sourcePath': local_path,
            'checksum': checksum
        }
        print(f'   - {doc_id}: {local_path[:60]}...')
    
    cursor.close()
    conn.close()
    
    # 确保目标目录存在
    print(f'\n4. 准备写入 catalog 文件到: {CATALOG_PATH}')
    CATALOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    
    # 备份旧文件（如果存在）
    if CATALOG_PATH.exists():
        backup_path = CATALOG_PATH.with_suffix('.json.backup')
        print(f'   发现现有文件，备份到: {backup_path}')
        CATALOG_PATH.rename(backup_path)
    
    # 写入新的 catalog 文件
    print('5. 写入 catalog 文件...')
    with open(CATALOG_PATH, 'w', encoding='utf-8') as f:
        json.dump(catalog, f, ensure_ascii=False, indent=2)
    
    print(f'   ✓ 成功写入 {len(catalog)} 个文档注册信息')
    
    # 验证文件
    print('6. 验证 catalog 文件...')
    with open(CATALOG_PATH, 'r', encoding='utf-8') as f:
        loaded = json.load(f)
    print(f'   ✓ 文件可正常读取，包含 {len(loaded)} 个条目')
    
    print('\n' + '=' * 80)
    print('修复完成！')
    print('=' * 80)
    print(f'\ncatalog 文件路径: {CATALOG_PATH}')
    print(f'注册的文档数量: {len(catalog)}')
    print('\n下一步：运行验收脚本测试 handout-document-read 工具')

if __name__ == '__main__':
    main()
