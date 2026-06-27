# Python AI Worker

Python worker 承担 OCR、公式识别、CLIP、多模态预处理、评测脚本等 Java 后端不适合直接实现的任务。第一阶段只提供可测试的健康检查和环境变量配置读取。

## 本地测试

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m unittest discover -s tests
```

密钥只从环境变量读取，不写入仓库。
