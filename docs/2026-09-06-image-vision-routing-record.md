# 图片路由验收与修复记录（2026-09-06，API 级）

本记录自 AGENTS.md 移出：该文件只放长期指令，运行验收记录归 docs/。

- 根因复核（DB 证据）：老板带图失败轮 explanation 1c52defd（03:20:50 UTC）request_json 偏好 glm/glm-5.3-flash，DB 行 ai_provider_name=deepseek——旧部署静默降级把带图轮路由到 deepseek-v4-flash，其 reasoningTrace 自述收到 "Unsupported Image"、消息内无实际图片；叠加旧 anthropic_compat 对多模态 content 列表 str() 拍平，glm 通道本也丢图。
- 测试图：生产语料 c2 只收录 B 版 8 本书，人教A版"2.函数的实际应用"（习题3.2 B组）整页不存在（磁盘/语料/教师资料全查），老板照片未落盘；经老板批准改用真实扫描页 processed_books/renjiao_bbixiu1math/pages/p093.png（含蓄水池最低造价题），未自绘任何图。
- 场景 A（自动路由，explanation 2ba97d74）：openai/gpt-5.6-terra，答案含页上真实数据（C(t)=20t/(t²+4)、4800m³/150/120 元、最低造价 297600 元），sources 命中第 93 页，视觉+检索链路成立。
- 场景 B（显式 glm）两次修复：
  1. 显式偏好 glm 的轮次被 ReAct 决策轮的 finalDraft 短路（决策轮按 2026-09-01 性能决定走默认 fast_text 路由，finalDraft 直接成为可见答案，绑定模型从未执行）。修复：StudentExplanationService.executeReactTools 在 request 带显式偏好时丢弃 finalDraft、强制 compose（generate() 传 preferred* 进 providerRoute）。
  2. compose 已真走 glm 但两次 "provider response is not a JSON object"（真实端点探针确证：compose 请求与 _stream_call_json 均不设 max_tokens，glm 预算=桥接下限 2048，低档思考实测吃掉全部预算致 stop=max_tokens、JSON 截断）。修复：.env MATH_AGENT_GLM_MIN_MAX_TOKENS=8192。终验 explanation cb40a4c6：DB 行 ai_provider_name=glm/glm-5.3-flash，completion 3671，逐题真实解出页上 11/12/13 题——桥接 OpenAI→Anthropic image 块在 z.ai 实测可看图。
- 结论（max_tokens 调查）：该链路不存在签名 token 预算字段（Java payload 与 worker 都无），glm 预算唯一来源即部署下限；显式 glm 轮约 2.5 分钟，老板知情接受。
- 场景 C（deepseek+图）：修复前 400 BAD_REQUEST 消息"所选模型 deepseek/deepseek-v4-flash 不支持图片输入，请切换到视觉模型或移除题图"，但晚于 react（白跑一次默认路由并计费）；补入口早筛（StudentExplanationService.explain 在 imageDataUrl 就绪后、任何模型调用前按 supportsVision 拒绝，SSE 回归 error 事件出现在第 6 行、ai_usage_event 零新增）。
- 回归：worker 桥接 unittest（tests/test_anthropic_compat.py AnthropicImageBridgeTests 4 例 + 原 12 函数例）全绿；Java `mvn test -Dtest=AiProviderCatalogVisionTest,PythonMigratedWorkloadClientVisionRouteTest,Student*` 全绿（13:27 运行，Failures 0）。前端产物 grep "不支持图片输入" 命中 /usr/share/nginx/html/assets/index-h7VCPPqA.js。
- 遗留：glm 答案被超长卡片（>8192）截断时 worker 会静默轮换 fallback，DB provider 变 terra，建议后续加 recoveryEvents 告警；A 版教材整页语料缺失（见上）。
