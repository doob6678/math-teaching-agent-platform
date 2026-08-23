#!/usr/bin/env python3
"""
测试 catalog 修复后 handout-document-read 工具是否正常工作
"""
import requests
import sys
import hashlib

# 读取测试任务信息
with open('test_task_info.txt', 'r') as f:
    lines = f.read().strip().split('\n')
    task_id = lines[0]
    doc_id = lines[1]

# 按照 Java 的逻辑计算 documentRef
input_str = f'{task_id}|document|{doc_id}'
fingerprint = hashlib.sha256(input_str.encode('utf-8')).hexdigest()[:16]
doc_ref = f'doc_{fingerprint}'

print("================================================================================")
print("测试 handout-document-read 工具（catalog 修复后）")
print("================================================================================")
print(f"\n测试数据:")
print(f"  runId: {task_id}")
print(f"  documentId: {doc_id}")
print(f"  documentRef: {doc_ref}")

# 准备请求
url = "http://localhost:8080/internal/agent-tools/v1/handout-document-read"
headers = {
    "X-Agent-Worker-Key": "local_broker_0ca6b57b594743749ab6dce4",
    "Content-Type": "application/json"
}
payload = {
    "runId": task_id,
    "documentRef": doc_ref,
    "maxBlocks": 50,
    "maxChars": 10000
}

print(f"\n发送请求到: {url}")
print(f"Payload: {payload}")

try:
    response = requests.post(url, headers=headers, json=payload, timeout=30)
    print(f"\n响应状态码: {response.status_code}")

    if response.status_code == 200:
        data = response.json()
        print("\n✅ 成功！响应数据:")
        print(f"  runId: {data.get('runId')}")
        print(f"  documentRef: {data.get('documentRef')}")
        blocks = data.get('blocks', [])
        print(f"  blocks 数量: {len(blocks)}")
        if blocks:
            print(f"\n前3个 blocks 预览:")
            for i, block in enumerate(blocks[:3]):
                print(f"  Block {i+1}:")
                print(f"    blockOrder: {block.get('blockOrder')}")
                print(f"    rawText: {block.get('rawText', '')[:100]}...")

        print("\n" + "="*80)
        print("✅ 测试成功！catalog 修复已生效")
        print("="*80)
        sys.exit(0)

    elif response.status_code == 400:
        print(f"\n⚠ 返回 400 错误: {response.text}")
        print("\n可能原因: 文档未在 catalog 中注册")
        print("="*80)
        sys.exit(1)

    elif response.status_code == 403:
        print(f"\n⚠ 返回 403 错误: {response.text}")
        print("\n可能原因: documentRef 未在 task 的 evidence 中授权")
        print("="*80)
        sys.exit(1)

    else:
        print(f"\n⚠ 返回其他状态码: {response.status_code}")
        print(f"响应: {response.text}")
        print("\n" + "="*80)
        print("❌ 测试失败！需要进一步调查")
        print("="*80)
        sys.exit(1)

except requests.exceptions.RequestException as e:
    print(f"\n❌ 请求失败: {e}")
    print("\n请确保后端服务正在运行")
    print("="*80)
    sys.exit(1)
