#!/usr/bin/env python3
"""Inspect PDFs using pypdf and basic text extraction."""
import json
import sys
from pathlib import Path
from typing import Any

try:
    import pypdf
except ImportError:
    print("pypdf not available; attempting minimal inspection", file=sys.stderr)
    pypdf = None

OUTPUT_DIR = Path(r"C:\Users\doob\Desktop\code\dev\math_agent_rag\output\acceptance\handout-mcp\recovered-fe814d79")
VARIANTS = ["teacher", "student", "lecture"]

def inspect_pdf_basic(pdf_path: Path) -> dict[str, Any]:
    """Extract basic metadata and text without external tools."""
    result = {
        "path": str(pdf_path),
        "exists": pdf_path.exists(),
        "sizeBytes": pdf_path.stat().st_size if pdf_path.exists() else 0,
    }
    
    if not pypdf or not pdf_path.exists():
        return result
    
    try:
        reader = pypdf.PdfReader(str(pdf_path))
        result["pages"] = len(reader.pages)
        result["metadata"] = {k: str(v) for k, v in (reader.metadata or {}).items() if v}
        
        text_parts = []
        for page_num, page in enumerate(reader.pages, start=1):
            text = page.extract_text() or ""
            text_parts.append(text)
            if page_num <= 3:
                result[f"page{page_num}Sample"] = text[:500]
        
        full_text = "\n".join(text_parts)
        result["totalChars"] = len(full_text)
        result["hasChineseChars"] = any('\u4e00' <= c <= '\u9fff' for c in full_text)
        result["containsLatex"] = "$$" in full_text or "\\[" in full_text
        
        text_path = pdf_path.with_suffix(".txt")
        text_path.write_text(full_text, encoding="utf-8")
        result["textExtracted"] = str(text_path)
        
    except Exception as e:
        result["inspectionError"] = f"{type(e).__name__}: {str(e)}"
    
    return result

def check_student_isolation(student_text: str) -> dict[str, Any]:
    """Check that student PDF doesn't leak teacher content."""
    forbidden_patterns = [
        ("答案", "teacher answers"),
        ("教师批注", "teacher annotations"),
        ("解析", "solution analysis"),
        ("完整推导", "complete derivation"),
        ("最终答案", "final answer"),
        ("trace", "execution trace"),
        ("sourcePath", "source path"),
        ("assetId", "asset ID"),
        ("http://", "URL"),
        ("https://", "URL"),
        ("C:\\", "Windows path"),
        ("/mnt/", "Linux mount path"),
    ]
    
    hits = []
    for pattern, description in forbidden_patterns:
        if pattern.lower() in student_text.lower():
            hits.append({"pattern": pattern, "description": description})
    
    return {
        "passed": len(hits) == 0,
        "forbiddenHits": hits,
        "totalChars": len(student_text)
    }

def main():
    report = {
        "outputDirectory": str(OUTPUT_DIR),
        "variants": {},
        "inspectedAt": None
    }
    
    for variant in VARIANTS:
        pdf_path = OUTPUT_DIR / f"{variant}.pdf"
        print(f"Inspecting {variant}.pdf...", file=sys.stderr)
        inspection = inspect_pdf_basic(pdf_path)
        report["variants"][variant] = inspection
        
        print(f"  Pages: {inspection.get('pages', 'N/A')}", file=sys.stderr)
        print(f"  Size: {inspection.get('sizeBytes', 0):,} bytes", file=sys.stderr)
        print(f"  Chinese: {inspection.get('hasChineseChars', False)}", file=sys.stderr)
        print(f"  LaTeX: {inspection.get('containsLatex', False)}", file=sys.stderr)
    
    student_text_path = OUTPUT_DIR / "student.txt"
    if student_text_path.exists():
        print("\nChecking student isolation...", file=sys.stderr)
        student_text = student_text_path.read_text(encoding="utf-8")
        isolation = check_student_isolation(student_text)
        report["studentIsolation"] = isolation
        
        if isolation["passed"]:
            print("  ✓ Student isolation passed", file=sys.stderr)
        else:
            print(f"  ✗ Student isolation FAILED: {len(isolation['forbiddenHits'])} violations", file=sys.stderr)
            for hit in isolation["forbiddenHits"][:5]:
                print(f"    - {hit['pattern']}: {hit['description']}", file=sys.stderr)
    
    from datetime import datetime, timezone
    report["inspectedAt"] = datetime.now(timezone.utc).isoformat()
    
    report_path = OUTPUT_DIR / "inspection-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\n✓ Inspection report: {report_path}", file=sys.stderr)
    
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report.get("studentIsolation", {}).get("passed", False) else 1

if __name__ == "__main__":
    sys.exit(main())
