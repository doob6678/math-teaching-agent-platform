#!/usr/bin/env python3
"""检查 PDF 文本内容是否完整"""
import sys
import PyPDF2

def inspect_pdf(pdf_path):
    """提取 PDF 文本并检查关键内容"""
    with open(pdf_path, 'rb') as f:
        reader = PyPDF2.PdfReader(f)
        pages = len(reader.pages)
        
        # 提取所有文本
        full_text = ""
        for i, page in enumerate(reader.pages):
            text = page.extract_text()
            full_text += text
            print(f"--- Page {i+1}/{pages} ---")
            print(text[:500])  # 每页前 500 字符
            print()
        
        # 检查关键内容
        checks = {
            "包含抛物线": "抛物线" in full_text,
            "包含数学公式标记": "$" in full_text or "\\(" in full_text,
            "包含定义关键词": "定义" in full_text,
            "包含标准方程": "标准方程" in full_text,
            "包含焦点": "焦点" in full_text,
            "包含准线": "准线" in full_text,
            "总字符数": len(full_text),
        }
        
        print("=" * 60)
        print("内容检查:")
        for key, value in checks.items():
            print(f"  {key}: {value}")
        
        return checks

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python inspect_pdf_content.py <pdf_path>")
        sys.exit(1)
    
    pdf_path = sys.argv[1]
    inspect_pdf(pdf_path)
