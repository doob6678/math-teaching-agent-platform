# 阶段 06：WSL Docker DOCX 页面渲染

状态：已完成（渲染器与命令测试；尚无真实真题 DOCX 视觉验收）。

## 设计

`DocxToPdfRenderer` 使用 Docker 内的 `soffice --headless --convert-to pdf:writer_pdf_Export` 生成派生 PDF；源 DOCX 永不修改。转换带有明确超时和退出码/输出文件校验。成功后的 PDF 必须进入与原生 PDF 相同的 PDFBox/Poppler PNG 页面链路。

Dockerfile 新增 `libreoffice-writer`，替代现有教师资料同步中仅适用 Windows 的 PowerShell/Word 渲染脚本。本次改动不修改原有多平台适配逻辑，仅为真题入库增加符合 WSL/Docker 运行约束的路径。

## 测试

`DocxToPdfRendererTest` 先在类缺失时红灯，后固定 LibreOffice headless 参数。与其余核心测试合计 18 项、退出码 0。因为没有真实 DOCX 输入，尚未伪造转换产物、页图或 Luna 审查结果。
