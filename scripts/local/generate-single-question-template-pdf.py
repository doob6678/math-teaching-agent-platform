from pathlib import Path
from reportlab.lib.pagesizes import A4
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen.canvas import Canvas
from reportlab.lib import colors
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "output" / "pdf" / "single-question-template-v2.pdf"
OUT.parent.mkdir(parents=True, exist_ok=True)
pdfmetrics.registerFont(TTFont("SimHei", "C:/Windows/Fonts/simhei.ttf"))
pdfmetrics.registerFont(TTFont("MathSymbols", "C:/Windows/Fonts/DejaVuSans.ttf"))
W, H = A4
NAVY = colors.HexColor("#173B63")
TEAL = colors.HexColor("#177E89")
INK = colors.HexColor("#202B36")
MUTED = colors.HexColor("#64748B")
PALE = colors.HexColor("#F5F8FB")
BLUE_PALE = colors.HexColor("#EAF2FA")
GREEN_PALE = colors.HexColor("#EAF7F4")
AMBER_PALE = colors.HexColor("#FFF6E5")
RED_PALE = colors.HexColor("#FFF0F0")

def txt(c, x, y, value, size=10, color=INK, font="SimHei"):
    c.setFont(font, size); c.setFillColor(color); c.drawString(x, y, value)

def wrap(c, x, y, value, width=480, size=10, leading=16, color=INK):
    c.setFont("SimHei", size); c.setFillColor(color); line = ""
    for ch in value:
        if ch == "\n" or c.stringWidth(line + ch, "SimHei", size) > width:
            c.drawString(x, y, line); y -= leading; line = "" if ch == "\n" else ch
        else: line += ch
    if line: c.drawString(x, y, line); y -= leading
    return y

def title(c, subtitle):
    txt(c, 42, 800, "单题讲义模板 v2", 22, NAVY)
    txt(c, 42, 778, subtitle, 10, MUTED)
    c.setStrokeColor(TEAL); c.setLineWidth(2.5); c.line(42, 764, 552, 764)

def bar(c, y, number, label):
    c.setFillColor(NAVY); c.roundRect(42, y-7, 510, 28, 5, fill=1, stroke=0)
    txt(c, 56, y+1, number, 10, colors.white, "Helvetica-Bold")
    txt(c, 92, y+1, label, 11, colors.white)
    return y - 42

def formula(c, x, y, kind="problem", scale=1.0):
    # Use upright variables for this classroom template; the user-facing handout should not look slanted.
    f = 15 * scale; ob = "Helvetica"
    if kind == "problem":
        txt(c, x, y, "f(x) = x", f, INK, ob); txt(c, x+43*scale, y+9*scale, "2", 9*scale, TEAL, "Helvetica-Bold")
        txt(c, x+54*scale, y, " - 2ax + 1", f, INK, ob); txt(c, x+150*scale, y, ",   x", f, INK, ob)
        txt(c, x+178*scale, y, "∈", f, TEAL, "MathSymbols")
        txt(c, x+192*scale, y, " [0, 2]", f, INK, ob)
    elif kind == "vertex":
        txt(c, x, y, "f(x) = (x - a)", f, INK, ob); txt(c, x+112*scale, y+9*scale, "2", 9*scale, TEAL, "Helvetica-Bold")
        txt(c, x+123*scale, y, " + 1 - a", f, INK, ob); txt(c, x+189*scale, y+9*scale, "2", 9*scale, TEAL, "Helvetica-Bold")
    elif kind == "middle":
        txt(c, x, y, "1 - a", f, INK, ob); txt(c, x+50*scale, y+9*scale, "2", 9*scale, TEAL, "Helvetica-Bold")
        txt(c, x+64*scale, y, " =", f, INK, ob); fraction(c, x+91*scale, y+2*scale, "1", "2", 13*scale)
    elif kind == "answer":
        txt(c, x, y, "a =", f, INK, ob); fraction(c, x+35*scale, y+2*scale, "√2", "2", 15*scale)

def fraction(c, x, y, numerator, denominator, size=14):
    c.setFillColor(INK)
    radical = numerator == "√2"
    numerator_width = 30 if radical else c.stringWidth(numerator, "MathSymbols", size)
    width = max(numerator_width, c.stringWidth(denominator, "MathSymbols", size)) + 10
    if radical:
        # Draw the radical explicitly so its overbar visibly covers the 2 on every PDF viewer/font stack.
        c.setStrokeColor(INK); c.setLineWidth(1.1)
        c.line(x+2, y+10, x+6, y+4); c.line(x+6, y+4, x+11, y+19); c.line(x+11, y+19, x+width-2, y+19)
        c.setFont("MathSymbols", size); c.drawString(x+15, y+7, "2")
    else:
        c.setFont("MathSymbols", size); c.drawCentredString(x + width/2, y+8, numerator)
    c.setStrokeColor(INK); c.setLineWidth(.9); c.line(x, y+5, x+width, y+5)
    c.setFont("MathSymbols", size)
    c.drawCentredString(x + width/2, y-12, denominator)

def render_latex_answer_image():
    """Render the final answer through a real TeX mathtext engine, so the radical vinculum covers the 2."""
    path = OUT.parent / "single-question-template-v2-answer.png"
    fig = plt.figure(figsize=(2.4, 0.75), dpi=240)
    fig.patch.set_alpha(0)
    fig.text(0.02, 0.18, r"$\mathrm{a}=\frac{\sqrt{2}}{2}$", fontsize=27, color="#202B36")
    fig.savefig(path, transparent=True, bbox_inches="tight", pad_inches=0.02)
    plt.close(fig)
    return path

def card(c, x, y, w, h, fill, heading, body, heading_color=TEAL):
    c.setFillColor(fill); c.roundRect(x, y-h, w, h, 7, fill=1, stroke=0)
    txt(c, x+14, y-22, heading, 10.5, heading_color, "Helvetica-Bold")
    yy = y-44
    for line in body: yy = wrap(c, x+14, yy, line, w-28, 9.5, 15)

c = Canvas(str(OUT), pagesize=A4); c.setTitle("单题讲义模板 v2")
answer_image = render_latex_answer_image()
title(c, "公式块、分类讨论、答案高亮与 AI 结构化流程")
y = bar(c, 724, "01", "原题")
c.setFillColor(PALE); c.roundRect(42, y-104, 510, 104, 7, fill=1, stroke=0)
wrap(c, 58, y-26, "已知函数在闭区间上取最小值，求参数 a。原题保留自然语言，公式单独渲染。", 478, 11, 17)
formula(c, 104, y-70, "problem", 1.05)
y -= 132
y = bar(c, y, "02", "题号级解析")
wrap(c, 58, y, "先配方，再按顶点是否落在区间内分类讨论。每个分支都写出端点或顶点的实际代入式。", 478, 10, 16); y -= 30
card(c, 52, y, 240, 88, BLUE_PALE, "情况 A   a < 0", ["函数在 [0,2] 上递增。", "最小值：f(0) = 1。"], NAVY)
card(c, 312, y, 240, 88, GREEN_PALE, "情况 B   0 <= a <= 2", ["顶点 x=a 落在区间内。", "最小值：1-a 的平方。"], TEAL)
y -= 106
card(c, 52, y, 240, 88, RED_PALE, "情况 C   a > 2", ["函数在 [0,2] 上递减。", "最小值：f(2) = 5-4a。"], colors.HexColor("#B42318"))
c.setFillColor(AMBER_PALE); c.roundRect(312, y-88, 240, 88, 7, fill=1, stroke=0)
txt(c, 326, y-22, "统一配方", 10.5, colors.HexColor("#9A6700"), "Helvetica-Bold"); formula(c, 326, y-51, "vertex", .78)
y -= 120
y = bar(c, y, "03", "中间分支推导")
formula(c, 76, y-10, "middle", 1.0)
wrap(c, 58, y-50, "因此 a=√2/2；再检查 a 的范围，结论与当前分支一致。", 478, 10, 16)
c.showPage()

title(c, "答案区与生成契约")
y = bar(c, 724, "04", "最终答案")
c.setFillColor(colors.HexColor("#E8F5F1")); c.roundRect(72, y-118, 450, 118, 9, fill=1, stroke=0)
txt(c, 92, y-30, "答案", 11, TEAL, "Helvetica-Bold"); c.drawImage(str(answer_image), 92, y-101, width=125, height=42, mask="auto")
txt(c, 280, y-77, "选用独立公式块，避免与正文挤在同一行。", 9.5, MUTED)
y -= 150; y = bar(c, y, "05", "评分点")
c.setFillColor(BLUE_PALE); c.roundRect(62, y-130, 470, 130, 7, fill=1, stroke=0)
for i, line in enumerate(["1  写出配方并说明顶点位置（2 分）", "2  完成三种区间情况的端点/顶点代入（2 分）", "3  列出 1-a 的平方=1/2 并检查参数范围（1 分）"]):
    txt(c, 82, y-30-i*30, line, 10, INK, "SimHei")
y -= 164; y = bar(c, y, "06", "实际任务提示词")
prompt = ["你是高中数学教师，只处理题号“例题 01”，不得生成整卷答案。", "输入原题：f(x)=x^2-2ax+1 在 x∈[0,2] 上最小值为 1/2，求 a。", "严格输出 JSON：question_no、problem、analysis、answer、scoring_points、formula_latex。", "平方使用上标语义，分式使用 \\frac{分子}{分母}，根式使用 \\sqrt{被开方数}。", "模型只负责理解和结构化，最终 PDF 由公式渲染器和页面模板生成。", "无法核验时 answer 为空并标记 review_required=true，禁止猜答案。"]
c.setFillColor(PALE); c.roundRect(42, y-142, 510, 142, 7, fill=1, stroke=0)
yy = y-20
for line in prompt: yy = wrap(c, 56, yy, line, 482, 8.5, 13, colors.HexColor("#334E68"))
c.showPage()

title(c, "流程记录与结构化拼接")
y = bar(c, 724, "07", "生成流程")
steps = [("01", "收集", "读取用户权限范围内的单题原文、题号和来源引用。"), ("02", "识别", "题型智能体只返回条件、目标、分支和公式候选。"), ("03", "求解", "解题智能体生成题号级步骤、答案与评分点。"), ("04", "校验", "检查分式、根号、上下标、括号和答案可推出性。"), ("05", "拼接", "模板只消费结构化 JSON，不直接拼接整段模型文本。"), ("06", "发布", "分别生成教师版、学生版，并记录耗时和审查状态。")]
for number, name, detail in steps:
    c.setFillColor(BLUE_PALE); c.circle(72, y-10, 16, fill=1, stroke=0); txt(c, 64, y-14, number, 7.5, NAVY, "Helvetica-Bold")
    txt(c, 104, y-5, name, 10.5, NAVY, "Helvetica-Bold"); wrap(c, 160, y-5, detail, 370, 9.5, 15); y -= 42
y -= 14; y = bar(c, y, "08", "结构化记录")
c.setFillColor(PALE); c.roundRect(42, y-118, 510, 118, 7, fill=1, stroke=0)
record = ['{"question_no":"例题 01", "source_refs":["template-single-question-v2"],', ' "agent_elapsed_ms": 0, "formula_check": "passed",', ' "answer_check": "passed", "review_required": false}']
yy = y-22
for line in record: yy = wrap(c, 58, yy, line, 482, 9, 15, colors.HexColor("#334E68"))
txt(c, 42, 46, "验收原则：题目、解析、答案和公式都按题号绑定；模型不直接控制页面布局。", 8.5, MUTED)
c.save(); print(OUT)
