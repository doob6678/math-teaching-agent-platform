# Java Backend

Spring Boot 3.x + Java 21 后端。当前阶段提供最小可测骨架：

1. `/api/system/health` 健康检查。
2. `/api/resources/textbooks/summary` 教材资源摘要。
3. `TextbookCatalogReader` 读取旧教材项目的 `processed_books/catalog.jsonl`。
4. `TextbookResourceService` 汇总教材数量、chunk 数和页数。

## 测试

```powershell
mvn test
```

## 资源路径

教材资源来自外部目录，不复制进仓库：

```text
C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main\tchMaterial-parser-main\processed_books
```

后续通过环境变量传入：

```text
MATH_AGENT_PROCESSED_BOOKS_ROOT
```

PowerShell 示例：

```powershell
$env:MATH_AGENT_PROCESSED_BOOKS_ROOT = "C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main\tchMaterial-parser-main\processed_books"
mvn spring-boot:run
```
