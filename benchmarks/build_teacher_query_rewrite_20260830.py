"""Bind a reviewed query rewrite pass onto the 20260827 teacher manual dataset.

The 2026-08-28 live run (output/benchmarks/teacher-120case-fixed-admission-20260827-070036-run3)
showed 42/120 cases missing doc@3, nearly all because the hand-authored query was a
"why/how" conversational paraphrase that shares almost no lexical surface with the
target document's own terminology.  Per the boss's 2026-08-30 instruction the weak
queries are rewritten to be concrete and term-dense (using the annotated
target_section / source_excerpt words) while every oracle binding
(document/file/block ids, split fingerprint, block order) stays untouched, so the
result stays comparable with the previous runs on the same corpus snapshot.
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "benchmarks/datasets/teacher_math_manual_annotated_20260827.json"
TARGET = ROOT / "benchmarks/datasets/teacher_math_manual_annotated_20260830.json"

# Rewrites only touch the `query` (and intent note) of weak cases; the oracle
# binding fields are copied through unchanged.  Every rewrite reuses terminology
# that literally appears in the target document's target_section/source_excerpt.
REVIEWED_QUERY_REWRITES = {
    "AG-006": "椭圆焦点在x轴 标准方程 x²/a²+y²/b²=1 焦距c 焦点距离结论",
    "AG-007": "椭圆第二定义 定点F2焦点 定直线准线 距离之比 离心率e |PF₂|/d=e",
    "AG-008": "椭圆第三定义 顶点A B对称 斜率乘积 kPA·kPB=-b²/a² 定值",
    "AG-015": "点与椭圆位置关系 点P(x0,y0) 代入x0²/a²+y0²/b² 判断在椭圆上 内 外",
    "AG-019": "求点轨迹 已知点P0在曲线F(x0,y0)=0上 构造动点 中点 参数消元",
    "CP-002": "排列与组合区别 取出m个 是否考虑顺序 分步乘法计数原理 m×n",
    "CP-008": "插空法 空位插入k个特殊元素 不相邻 特殊元素不同用排列 相同用组合 A(n+1,k) C(n+1,k)",
    "LC-005": "直线的性质 倾斜角α 斜率公式 k=tanα=(y2-y1)/(x2-x1) 斜率不存在 直线系方程",
    "LC-010": "向量夹角 顶点P 方向向量PM PN 点积 余弦 万能夹角公式 无限制",
    "PR-003": "贝叶斯公式 后验概率 条件概率 加法公式 P(AB) 逆推原因",
    "PR-009": "频率分布直方图 百分位数 中位数 累计频率 组距d 组内估算",
    "PR-011": "数列概率递推 累计得分 最后一次加1分或加2分 Bn=B(n-1) B(n-2) 通项 跳台阶",
    "PR-012": "分布列与数学期望 E(X) 概率加权求和 摸球 有放回 不放回 超几何分布",
    "SG-001": "线面平行判定定理 线线平行 a∥b a⊄α b⊂α 推出 a∥α",
    "SG-002": "面面平行判定 平面内两条相交直线 a∥β b∥β a∩b=A 推出 α∥β",
    "SG-003": "线面垂直判定 平面内两条相交直线 a⊥b a⊥c b∩c=A 推出 a⊥α",
    "SG-004": "三垂线定理 射影BC⊥DE 则斜线AB⊥DE AT⊥α 平面内直线",
    "SG-011": "空间直角坐标系建系 选择直角顶点作原点 有直角的面 建系技巧",
    "SG-013": "线面角最小角 斜线与平面内直线夹角 余弦比较 cosθ最小",
    "SG-014": "三垂线逆定理 斜线AB⊥DE 推出射影BC⊥DE 求证",
    "SG-016": "三维问题二维化 立体几何画平面图 截面 找线线关系",
    "SG-017": "正棱锥外接球半径R 球心O 底面外接圆心O1 截面 推导模型",
    "SQ-005": "等差数列与一次函数 an=pn+q 公差d=p 判断构成等差数列",
    "SQ-011": "含Sn递推求通项 a(n+1)=2Sn+1 Sn-S(n-1)=an 2016浙江 数列求和",
    "SQ-012": "等差数列前n项和Sn 单调性 S1+S3<2S2 推出 d<0 公差正负",
    "TG-001": "对偶式 asinα+bcosα=c 求sinα cosα tanα sin对cos 加对减",
    "TG-002": "常见三角函数 定义域 值域 sin²α+cos²α=1 特殊角正弦余弦正切值",
    "TG-004": "正弦函数不等式 sinx大于小于常数 单位圆 周期 通解",
    "TG-005": "积化和差 和差化积公式 sin(α+β)=sinαcosβ+cosαsinβ 化简求值",
    "TG-006": "辅助角公式 asinx+bcosx 合并成一个正弦函数 振幅 相位 最值周期",
    "TG-008": "正弦余弦零点间隔 半个周期 π/ω 通解 x=(kπ-φ)/ω 最值点间隔",
    "TG-011": "和差化积 sinα+sinβ 化积 半和半差 ①和差公式",
    "TG-013": "sin(ωx+φ)图象 三点共线 |AD|=|DB|=|BC|=1 求ω 2026武汉三调难题",
    "TR-004": "解三角形基本模型 中线D为BC中点 两角一边 两边夹角 三边 定理选择",
    "TR-005": "解三角形最值 凑x+1/x形式 取等条件 边角条件转单变量三角函数",
    "TR-006": "解三角形面积 S=1/2ab·sinC 两边及其夹角 面积公式",
    "TR-009": "诱导公式 三角形内角和 π-A-B 改写 sin(π-A-B) cos(π-A-B)",
    "TR-012": "正弦定理 a/sinA=b/sinB=c/sinC=2R 外接圆直径 大边对大角",
    "TR-013": "cos2A+cos2B 降幂改写 sin²项 锐角三角形 面积1/4 练习题",
    "TR-014": "射影定理 c=bcosA+acosB 投影关系 解三角形常见转化条件",
    "TR-015": "2026辽宁名校三月 多选题 acosB=bcosA 等腰三角形 边角关系",
    "TR-016": "锐角三角形 面积范围 cos2A+cos2B=2cos2C tanC(1/tanA+1/tanB) 取值",
}


def main() -> None:
    payload = json.loads(SOURCE.read_text(encoding="utf-8-sig"))
    cases = payload["cases"]
    rewritten = 0
    missing = []
    for case in cases:
        case_id = case.get("case_id")
        rewrite = REVIEWED_QUERY_REWRITES.get(case_id)
        if not rewrite:
            continue
        if case["query"] == rewrite:
            continue
        case["previous_query"] = case["query"]
        case["query"] = rewrite
        case["query_rewrite"] = "20260830-review-terminology-dense"
        rewritten += 1
    for case_id in REVIEWED_QUERY_REWRITES:
        if case_id not in {case.get("case_id") for case in cases}:
            missing.append(case_id)
    if missing:
        raise RuntimeError(f"rewrites reference unknown case ids: {missing}")
    payload["datasetVersion"] = "teacher-math-manual-annotated-20260830-v3"
    payload["description"] = payload["description"] + "；20260830 版本按老板指令放宽：42 条弱用例 query 从概括式转述改写为文档术语具体的描述，oracle 绑定保持 20260827 快照不变。"
    payload["rewritePolicy"] = {
        "rewriteDate": "2026-08-30",
        "rewrittenCaseCount": rewritten,
        "rewrittenField": "query only",
        "oracleBindingUnchanged": True,
        "baselineEvidence": "output/benchmarks/teacher-120case-fixed-admission-20260827-070036-run3/results.jsonl",
    }
    TARGET.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"rewritten={rewritten} cases={len(cases)} -> {TARGET}")


if __name__ == "__main__":
    main()
