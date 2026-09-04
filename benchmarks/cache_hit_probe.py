# -*- coding: utf-8 -*-
"""BUG-C1 基准探针：对比 ReAct “重建式”与“追加式”消息形态下的 DeepSeek 前缀缓存命中率。

用途（BUG-C1 修复依据，2026-09-05）：
- 生产 ai-worker-python/app/workload_runtime.py 的 ReAct 规划器此前每轮把全量累积的
  observations 重新 json.dumps 进单条 user 消息重建 [system, user]，请求前缀从
  “observations”键处开始逐轮分叉；DeepSeek 前缀缓存按字节匹配（64 token 块粒度），
  于是 evidence 与新增观察轮轮 miss，平台实测命中率仅 7.2%。
- 本脚本用与生产逐字相同的 react 规划器 system 文本（含 MATH_MARKUP_OUTPUT_CONTRACT 拼接），
  以及同形状的 problem/availableTools/evidence/observations，分别按两种形态各连发 4 轮
  真实请求，逐轮记录 usage.prompt_tokens / prompt_cache_hit_tokens / prompt_cache_miss_tokens。
- 预期结论：
  * 新·追加式第 N 轮 hit ≈ 第 N-1 轮 prompt_tokens（第 N 轮请求前缀完整包含第 N-1 轮全部消息）；
  * 旧·重建式每轮只能命中到 “observations” 键出现之前的前缀（evidence 排在其后，永远 miss），
    且新增观察本身也必然 miss。
- 说明：两种形态串行执行，共享同一段 system 文本，后执行的形态第 1 轮可能继承少量
  system 前缀命中，这是真实缓存行为，不影响第 2-4 轮的对比结论。
- 真实计费调用 DeepSeek API（总消耗约 3 万 token，成本可忽略）。仅用于 BUG-C1 的量化证据。
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request

# DeepSeek 请求参数与生产 _call_one 对齐：temperature=0 + response_format=json_object。
DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions"
ROUNDS = 4
SLEEP_SECONDS = 2

# 与 workload_runtime.py 保持逐字一致的输出排版合同（MATH_MARKUP_OUTPUT_CONTRACT）。
MATH_MARKUP_OUTPUT_CONTRACT = (
    "数学排版是硬性输出合同：conversationTitle、每张卡片的 title、summary 与 items 中，只要出现变量、"
    "函数、集合、区间、方程、不等式、分式、根式、角度或运算式，就必须将完整表达式放入 $...$；"
    "例如标题写“函数 $f(x)$ 的定义域”，不得写“函数 f(x) 的定义域”。"
    "分式一律写 $\\frac{分子}{分母}$，根式一律写 $\\sqrt{被开方整体}$；不得用 /、√、^、上标字符"
    "或裸露数学符号代替 LaTeX 结构。不要在数学公式定界符外拆开一个表达式。"
)

# 与 workload_runtime.py::_react_student_explanation 的 react 规划器 system 文本逐字一致。
SYSTEM_TEXT = (
    "你是高中数学讲解的受限 ReAct 规划器。只返回 JSON："
    "{\"decision\":\"action|final\",\"tools\":[\"search_textbook|match_knowledge_graph|search_teacher_resources\"],"
    "\"queries\":[\"短检索词\"]}。"
    "只有在确实需要已授权资料时才选 action；tools 只能来自 availableTools，queries 最多 6 个。"
    "若题目自洽则返回 final，且 tools 与 queries 为空，并同时返回 "
    "conversationTitle 和 cards。cards 使用与 compose 相同的字段，sourceUris 只能来自 evidence。"
    "不要输出推理过程或 Markdown。题干已提供全部条件且可用代数、几何或定义直接完成时，"
    "必须返回 final；不得仅为讲解通用概念而调用检索。"
    + MATH_MARKUP_OUTPUT_CONTRACT
)

# 与 workload_runtime.py::react_tool_catalog_entries 同形状的工具描述（本探针只用 2 个工具）。
TOOL_DESCRIPTIONS = [
    {"name": "search_textbook",
     "description": "检索已入库高中教材的正文块、章节目录与页码。教材版本、页码、章节结构、"
                    "教材原文的引入/例题/习题等都不在题目里，需要这类外部事实时才能取到。"},
    {"name": "search_teacher_resources",
     "description": "检索本次运行已授权的教师资料（讲义、题库、课件）正文，"
                    "题目出处、配套练习与教师讲解素材需要这类外部事实时才能取到。"},
]

# 固定题干（~300 字）：二次函数区间最值 + 恒成立 + 对数不等式，覆盖分类讨论场景。
PROBLEM = (
    "已知二次函数 f(x)=x^2-2ax+3（a∈R）。(1) 若 f(x) 在区间 [1,3] 上的最小值记为 g(a)，"
    "求 g(a) 关于 a 的解析式，并写出每一段对应的对称轴位置；(2) 设 h(x)=f(x)-g(a)，"
    "若对任意的 x1,x2∈[1,3] 且 x1≠x2，均有 |h(x1)-h(x2)|≤4 恒成立，"
    "结合第 (1) 问的结果求实数 a 的取值范围，说明最大值差与单调区间的对应关系；"
    "(3) 当 a=2 时，求不等式 f(x)>log2(x+1) 在区间 (0,3] 上的解集，"
    "要求保留分类讨论的完整过程，并逐段说明二次函数与对数函数图像交点的判断依据。"
)

# 固定检索证据（3 段，各 ~800 字），模拟 Java 授权后返回的受限文本摘要。
EVIDENCE = [
    {"sourceUri": "doc:textbook-required-1", "title": "人教A版必修一·二次函数区间最值",
     "snippet": (
         "二次函数 f(x)=x^2-2ax+3 的图像是开口向上的抛物线，对称轴为直线 x=a。讨论闭区间上的最小值时，"
         "必须比较对称轴与区间的相对位置：当 a<1 时，对称轴在区间 [1,3] 左侧，函数在 [1,3] 上单调递增，"
         "最小值为 f(1)=4-2a；当 1≤a≤3 时，对称轴落在区间内部，最小值在顶点处取得，为 g(a)=3-a^2；"
         "当 a>3 时，对称轴在区间 [1,3] 右侧，函数在 [1,3] 上单调递减，最小值为 f(3)=12-6a。"
         "综上所述，最小值函数 g(a) 是一个三段分段函数，分段点是 a=1 与 a=3。"
         "教材在本节例题中强调：区间最值问题的书写必须先画草图，标注对称轴随参数移动的三种临界位置，"
         "再逐段说明单调性，最后合并成分段函数。顶点式 f(x)=(x-a)^2+3-a^2 直接给出顶点纵坐标 3-a^2，"
         "这在判断顶点是否落入区间时比配方法书写更直接。章节末的习题提示：若把闭区间换成动区间，"
         "分类标准要从对称轴与区间端点的关系重新推导，不能照搬本题结论。"
         "另外，f(x)=x^2-2ax+3 在 R 上的最小值为 3-a^2，仅当 1≤a≤3 时该最小值才能在 [1,3] 内取到，"
         "这一条件与分段讨论的结果一致。本章知识链条：二次函数图像→对称轴→区间单调性→分段最值→恒成立转化。"
         "教材旁批还提醒：写分段函数时三段的解析式都含有参数 a，代数变形要先配方、再代入区间端点，"
         "两步不可颠倒，否则易在临界位置漏掉对定义域的检验。本节的阅读与思考栏目以弹簧振子高度为例，"
         "说明“对称轴随参数平移”在测量问题中的实际背景，帮助学生理解分段点为何恰好落在区间端点重合处。"
         "节末小结要求学生复述三段最值对应的图像特征，并把 g(a) 的草图与解析式逐一对应检查。"
     )},
    {"sourceUri": "doc:teacher-notes-2", "title": "教师讲义·恒成立问题的等价转化",
     "snippet": (
         "“对任意的 x1,x2∈[m,n] 且 x1≠x2，均有 |h(x1)-h(x2)|≤M 恒成立”的等价转化是教师讲义的重点："
         "该条件等价于 h 在区间 [m,n] 上的最大值与最小值之差不超过 M，即 h(x)max-h(x)min≤M。"
         "若 h 是二次函数，只需比较其在区间两个端点与顶点（若顶点在区间内）处的函数值，"
         "求出最大值与最小值后作差。本题中 h(x)=f(x)-g(a)，其中 g(a) 是第 (1) 问的分段最小值，"
         "因此需要先写出 g(a) 的分段解析式，再对每一段分别求 h 在 [1,3] 上的最值差。"
         "讲义提醒两个易错点：其一，g(a) 本身依赖 a，对每一段都要重新确认 h 的对称轴位置，"
         "不能默认 h 与 f 的对称轴一致；其二，|h(x1)-h(x2)|≤4 对任意两点成立等价于最值差不超过 4，"
         "不需要逐点验证。参考答案给出的取值范围需要结合分段边界（a=1、a=3）处的连续性检验，"
         "边界值是否取到由最值差恰好等于 4 时的等号条件决定。讲义建议学生用数轴标出 a 的分段区间，"
         "在每段内写出最值差的表达式并解不等式，最后取交集。配套练习：将 M=4 改为 M=5 重复上述过程，"
         "体会参数 M 与分段边界的关系。"
         "讲义的教学提示部分还给出板书建议：先固定 g(a) 的图像（三条抛物线弧），再在其上方平移高度 M，"
         "把最值差条件几何化为“竖直距离不超过 M”，学生据此能迅速判断哪一段最先违反条件。"
         "答疑记录显示，学生最常犯的错误是把 h 的对称轴仍当作 x=a 处理，忘记 g(a) 进入表达式后"
         "常数项随分段变化，导致最值差写错；讲义要求每段开头先重写 h 的完整解析式再求最值，"
         "并对 a=1、a=3 两个边界补做等号检验后再收口。"
     )},
    {"sourceUri": "doc:gaokao-bank-3", "title": "高考题库·二次函数与对数不等式交点",
     "snippet": (
         "涉及 f(x)>log2(x+1) 型不等式的求解，高考真题的标准处理是图像法结合单调性分析："
         "当 a=2 时 f(x)=x^2-4x+3=(x-1)(x-3)，在 x∈(0,3] 上先减后增，最小值在 x=2 处取得，为 -1。"
         "log2(x+1) 在 (0,3] 上单调递增，值域为 (1,2]。两个函数在区间内的大小关系需要找交点："
         "令 x^2-4x+3=log2(x+1)，由于左侧是二次式、右侧是对数式，通常先观察特殊点。"
         "x→0+ 时 f(x)→3 而 log2(x+1)→0，故左侧大于右侧；x=1 时 f(1)=0 而 log2(2)=1，左侧小于右侧，"
         "说明 (0,1) 内存在一个交点；x=3 时 f(3)=0 而 log2(4)=2，左侧仍小于右侧。"
         "因此解集为两交点之间被“反超”的区间的补集，具体端点需用二分法或图像估算。"
         "题库评析指出：此类题目不要求精确解出超越方程的根，而是通过零点存在性定理说明交点所在区间，"
         "再借助图像上下位置关系写出解集。阅卷细则要求：必须写出 x→0+、x=1、x=3 三个关键位置的函数值比较，"
         "并明确指出单调性差异保证交点唯一，否则扣分。同类变式包括把 log2 换成 ln 或把区间改为 [0,4]，"
         "处理框架完全一致：先定单调区间，再比较端点与特殊点函数值，最后用零点存在定理锁定交点区间。"
         "题库还附有评分样本：满分卷在写出 f(x)=(x-1)(x-3) 后立即标注对称轴 x=2 与区间 (0,3] 的关系，"
         "再逐点列表比较；典型失分样本则直接声称“显然有交点”而缺少函数值计算，被扣过程分。"
         "教师使用建议：讲评时先让学生独立完成三个关键点的函数值表格，再投影展示两函数图像的"
         "动态交点过程，强化“图像高低决定解集方向”的直观认识。"
     )},
]

# 固定工具观察（4 条，各 ~300 字），模拟 Java 逐轮累积传入的 observations。
OBSERVATIONS = [
    "search_textbook 返回：人教A版必修一第 2.2 节给出二次函数区间最值的标准分类框架——开口向上时，"
    "对称轴 x=a 与闭区间 [1,3] 的位置关系分三种：a<1、1≤a≤3、a>3。教材例题完整推导了 f(x)=x^2-2ax+3 "
    "在 [1,3] 上的最小值：a<1 时最小值为 f(1)=4-2a；1≤a≤3 时最小值为顶点值 3-a^2；a>3 时最小值为 "
    "f(3)=12-6a。教材特别提示分段点 a=1、a=3 需要单独检验函数值连续性，并要求画草图辅助说明。"
    "该片段还给出顶点式 (x-a)^2+3-a^2 的书写规范，可直接读出顶点纵坐标，与分段结论互相印证。",

    "search_teacher_resources 返回：教师讲义《恒成立问题的等价转化》指出，“对任意 x1≠x2 均有 "
    "|h(x1)-h(x2)|≤M”等价于 h 在闭区间上的最大值减最小值不超过 M。对二次函数只需比较端点值与"
    "区间内顶点值。讲义强调本题 h(x)=f(x)-g(a) 中的 g(a) 是分段函数，必须逐段重新确认 h 的对称轴："
    "当 a<1 时 g(a)=4-2a，h 的对称轴 x=a<1，h 在 [1,3] 递增，最值差为 h(3)-h(1)；当 1≤a≤3 时 "
    "g(a)=3-a^2，h 的对称轴仍在区间内，需比较顶点与端点；当 a>3 时 h 在 [1,3] 递减。"
    "讲义附有两道同型练习，建议最后对 a=1、a=3 两个边界做等号检验。",

    "match_knowledge_graph 返回：本题涉及的知识点主干为“二次函数→区间最值→恒成立转化→函数零点”。"
    "前置知识点包括配方法与顶点式、函数单调性的定义与图像特征；后续知识点为含参分类讨论与"
    "零点存在性定理。图谱显示“|f(x1)-f(x2)|≤M 型恒成立”与“闭区间上最值差”是同一节点的两种表述，"
    "常见错误是把逐点条件误认为只需要端点满足。与本题第 (3) 问相关的边标注：对数函数 y=log2(x+1) "
    "与二次函数的交点问题属于“超越方程的图像解法”，高考评分依赖零点存在性定理而非精确求根，"
    "建议检索教师资料中“图像法解不等式”的评分细则片段。",

    "search_textbook 返回：必修一第 4.4 节对数函数图像与性质给出 y=log2(x+1) 由 y=log2 x 向左平移 "
    "1 个单位得到，在定义域 (-1,+∞) 上单调递增。结合 f(x)=(x-1)(x-3) 在 (0,2] 递减、[2,3] 递增，"
    "教材例题示范了比较两函数值大小的三步法：先比较区间端点与特殊点（x→0+、x=1、x=3）处两函数值，"
    "再用零点存在性定理确认 f(x)=log2(x+1) 的根所在区间，最后依据两图像的上下位置关系写出不等式解集。"
    "教材注明本题不要求精确求根，只需说明唯一交点所在的大致区间，书写时必须列出每一关键点的函数值。",
]


def load_env(root: str) -> dict[str, str]:
    """从项目根 .env 读取 key=value 配置（简单解析，支持去引号，不引入任何依赖）。"""
    env: dict[str, str] = {}
    with open(os.path.join(root, ".env"), encoding="utf-8") as stream:
        for line in stream:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            env[key.strip()] = value.strip().strip('"').strip("'")
    return env


def call_deepseek(api_key: str, model: str, messages: list[dict]) -> dict:
    """真实调用 DeepSeek chat/completions，返回 usage 字段；HTTP 错误时打印响应原文后终止。"""
    payload = {
        "model": model,
        "messages": messages,
        "temperature": 0,
        "response_format": {"type": "json_object"},
    }
    request = urllib.request.Request(
        DEEPSEEK_URL,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + api_key,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            data = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        print("HTTP_ERROR status=%s body=%s" % (error.code, body), flush=True)
        raise SystemExit("DeepSeek rejected the request; aborting probe.") from error
    return data.get("usage") or {}


def rebuild_messages(round_index: int) -> list[dict]:
    """旧·重建式：每轮把累积的全部 observations 重新 json.dumps 进单条 user 消息（生产现状）。"""
    payload = {
        "problem": PROBLEM,
        "availableTools": TOOL_DESCRIPTIONS,
        "observations": OBSERVATIONS[:round_index],
        "evidence": EVIDENCE,
    }
    return [
        {"role": "system", "content": SYSTEM_TEXT},
        {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
    ]


def append_messages(round_index: int) -> list[dict]:
    """新·追加式：第 1 轮 base user 不含 observations；第 N 轮在前一轮消息列表末尾追加 observation 消息。

    每轮都重发完整历史（模拟无状态服务），因此 DeepSeek 看到的第 N 轮前缀 = 第 N-1 轮全部内容。
    """
    messages = [
        {"role": "system", "content": SYSTEM_TEXT},
        {"role": "user", "content": json.dumps({
            "problem": PROBLEM,
            "availableTools": TOOL_DESCRIPTIONS,
            "evidence": EVIDENCE,
        }, ensure_ascii=False)},
    ]
    for observation in OBSERVATIONS[:round_index - 1]:
        messages.append({
            "role": "user",
            "content": json.dumps({"observation": observation}, ensure_ascii=False),
        })
    return messages


def main() -> None:
    # Windows 控制台默认 GBK，重定向/打印中文前显式切换 UTF-8，避免 UnicodeEncodeError。
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    env = load_env(root)
    api_key = env.get("DEEPSEEK_API_KEY", "")
    model = env.get("DEEPSEEK_CHAT_MODEL", "deepseek-v4-flash")
    if not api_key:
        raise SystemExit("DEEPSEEK_API_KEY not found in project .env")

    print("model=%s url=%s rounds=%d sleep=%ds" % (model, DEEPSEEK_URL, ROUNDS, SLEEP_SECONDS), flush=True)
    print("content sizes: problem=%d chars, tools=%d, evidence=%s chars, observations=%s chars" % (
        len(PROBLEM), len(TOOL_DESCRIPTIONS),
        [len(item["snippet"]) for item in EVIDENCE],
        [len(item) for item in OBSERVATIONS]), flush=True)

    forms = [
        ("旧·重建式", rebuild_messages),
        ("新·追加式", append_messages),
    ]
    rows: list[tuple[str, int, int, int, int]] = []
    for form_name, builder in forms:
        for round_index in range(1, ROUNDS + 1):
            messages = builder(round_index)
            usage = call_deepseek(api_key, model, messages)
            prompt = int(usage.get("prompt_tokens", 0) or 0)
            hit = int(usage.get("prompt_cache_hit_tokens", 0) or 0)
            miss = int(usage.get("prompt_cache_miss_tokens", 0) or 0)
            completion = int(usage.get("completion_tokens", 0) or 0)
            rows.append((form_name, round_index, prompt, hit, miss))
            print("[%-6s] 轮次 %d | prompt_tokens=%-6d cache_hit=%-6d cache_miss=%-6d | completion_tokens=%d | prompt_chars=%d" % (
                form_name, round_index, prompt, hit, miss, completion,
                sum(len(str(m["content"])) for m in messages)), flush=True)
            if not (round_index == ROUNDS and form_name == forms[-1][0]):
                time.sleep(SLEEP_SECONDS)

    print("\n===== 汇总（hit/prompt 比例）=====", flush=True)
    for form_name, round_index, prompt, hit, miss in rows:
        ratio = ("%.1f%%" % (hit * 100.0 / prompt)) if prompt else "n/a"
        print("%s 轮 %d: prompt=%d hit=%d(%s) miss=%d" % (form_name, round_index, prompt, hit, ratio, miss), flush=True)


if __name__ == "__main__":
    main()
