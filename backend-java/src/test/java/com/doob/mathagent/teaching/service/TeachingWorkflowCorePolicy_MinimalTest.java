package com.doob.mathagent.teaching.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用真实导出的 Markdown 作为基准测试 Markdown→LaTeX 管线。
 * 目标：验证 AI 生成的完整内容能够不被误删地转换为 LaTeX。
 */
class TeachingWorkflowCorePolicy_MinimalTest {

    /**
     * 真实导出的教师版 Markdown（2375 字符），包含标题、中文、公式、评分点。
     * 来源：workflow fe814d79-7407-43a5-a9e3-3504fbdfe6a7 checkpoint teacher_writer
     */
    private static final String TEACHER_MARKDOWN = """
# 抛物线定义、标准方程与焦点弦（教师版蓝图）

## 题目1：抛物线的定义

**题目**：已知平面内一个定点 $F$ 和一条不过点 $F$ 的定直线 $l$，动点 $M$ 满足 $|MF| = d(M,l)$，其中 $d(M,l)$ 是点 $M$ 到直线 $l$ 的距离。这样的动点 $M$ 的轨迹是什么？请结合图形说明并写出几何条件。

**解题过程**：
1. 回顾点到直线的距离公式 $d(M,l)$。
2. 用几何画板演示动点 $M$ 到定点 $F$ 与到直线 $l$ 距离相等的轨迹。
3. 归纳定义：平面内与一个定点 $F$ 和一条定直线 $l$（$l$ 不过点 $F$）的距离相等的点的轨迹叫做抛物线。

**最终答案**：轨迹是抛物线。几何条件：$|MF| = d(M,l)$，其中 $F$ 为焦点，$l$ 为准线。

**评分点**：
- 正确说出轨迹为抛物线（1分）
- 写出几何条件 $|MF| = d(M,l)$（1分）
- 说明焦点与准线（1分）

**易错点**：
- 漏掉"直线 $l$ 不过点 $F$"的条件
- 误将 $d(M,l)$ 与 $|MF|$ 的关系写反

## 题目2：抛物线的标准方程

**题目**：取过焦点 $F$ 且垂直于准线 $l$ 的直线为 $x$ 轴，设焦点到准线的距离为 $p$（$p>0$）。建立直角坐标系，推导焦点在 $x$ 轴正半轴时抛物线的标准方程，并写出焦点坐标和准线方程。

**解题过程**：
1. 建立坐标系：以过焦点 $F$ 且垂直于准线 $l$ 的直线为 $x$ 轴，设垂足为 $K$，取 $|KF| = p$，以线段 $KF$ 的垂直平分线为 $y$ 轴。则焦点 $F(\\frac{p}{2},0)$，准线 $l: x=-\\frac{p}{2}$。
2. 设动点 $M(x,y)$，由抛物线的定义，$|MF| = d(M,l)$，即 $\\sqrt{(x-\\frac{p}{2})^2+y^2} = x+\\frac{p}{2}$。
3. 两边平方并化简：
   $(x-\\frac{p}{2})^2+y^2 = (x+\\frac{p}{2})^2$
   $x^2-px+\\frac{p^2}{4}+y^2 = x^2+px+\\frac{p^2}{4}$
   $y^2 = 2px$。
4. 验证：满足该方程的点的坐标满足定义，故为标准方程。

**最终答案**：标准方程为 $y^2=2px$（$p>0$），焦点坐标 $(\\frac{p}{2},0)$，准线方程 $x=-\\frac{p}{2}$。

**评分点**：
- 正确建立坐标系（1分）
- 正确列出距离等量关系（1分）
- 正确化简得到标准方程（1分）
- 正确写出焦点和准线（1分）

**易错点**：
- 忘记 $p>0$ 的条件
- 平方时出现符号错误或忽略距离非负
- 焦点坐标写为 $(p,0)$ 或 $(\\frac{p}{4},0)$

## 题目3：焦点弦及其来源

**题目**：过抛物线 $y^2=2px$（$p>0$）的焦点 $F$ 作倾斜角为 $\\theta$ 的直线，与抛物线交于 $A$、$B$ 两点。推导焦点弦长 $|AB|$ 的表达式，并说明当 $\\theta=90^\\circ$ 时得到的通径长度是多少？

**解题过程**：
1. 焦点 $F(\\frac{p}{2},0)$，设直线 $AB$ 的斜率为 $k=\\tan\\theta$（$\\theta\\neq 90^\\circ$），方程为 $y=k(x-\\frac{p}{2})$。
2. 代入 $y^2=2px$，得 $k^2(x-\\frac{p}{2})^2=2px$，整理得 $k^2x^2-(k^2p+2p)x+\\frac{k^2p^2}{4}=0$。
3. 设 $A(x_1,y_1)$，$B(x_2,y_2)$，由韦达定理：$x_1+x_2=p+\\frac{2p}{k^2}$。
4. 由抛物线定义，$|AF|=x_1+\\frac{p}{2}$，$|BF|=x_2+\\frac{p}{2}$，所以 $|AB|=x_1+x_2+p=2p+\\frac{2p}{k^2}=\\frac{2p(1+k^2)}{k^2}$。
5. 用 $\\theta$ 表示：因为 $k=\\tan\\theta$，所以 $|AB|=\\frac{2p}{\\sin^2\\theta}$。
6. 当 $\\theta=90^\\circ$ 时，直线垂直于 $x$ 轴，代入 $x=\\frac{p}{2}$ 得 $y^2=p^2$，$y=\\pm p$，故通径长 $|AB|=2p$。

**最终答案**：焦点弦长 $|AB|=\\frac{2p}{\\sin^2\\theta}$（$\\theta\\neq 0$）；通径长度为 $2p$。

**评分点**：
- 正确写出直线方程并代入（1分）
- 正确使用韦达定理（1分）
- 巧用抛物线定义转化为 $x_1+x_2+p$（1分）
- 化简为 $\\frac{2p}{\\sin^2\\theta}$（1分）
- 正确讨论 $\\theta=90^\\circ$ 得通径 $2p$（1分）

**易错点**：
- 未讨论斜率不存在的情形
- 误用一般弦长公式导致计算复杂
- 将通径长度误写为 $p$ 或 $4p$

## 总结
- 抛物线的定义：到定点与定直线距离相等的点的轨迹。
- 标准方程：$y^2=2px$（$p>0$），焦点 $(\\frac{p}{2},0)$，准线 $x=-\\frac{p}{2}$。
- 焦点弦：$|AB|=\\frac{2p}{\\sin^2\\theta}$，通径长为 $2p$。
""";

    @Test
    void teacherMarkdown_shouldPreserveAllSections() {
        String latex = TeachingWorkflowCorePolicy.renderWriterMarkdown(TEACHER_MARKDOWN, true);

        // 验证主标题
        assertThat(latex).contains("\\section{抛物线定义、标准方程与焦点弦（教师版蓝图）}");
        
        // 验证三个题目的二级标题都存在
        assertThat(latex).contains("\\subsection{题目1：抛物线的定义}");
        assertThat(latex).contains("\\subsection{题目2：抛物线的标准方程}");
        assertThat(latex).contains("\\subsection{题目3：焦点弦及其来源}");
        
        // 验证总结标题
        assertThat(latex).contains("\\subsection{总结}");
        
        // 验证关键数学公式（inline math）
        assertThat(latex).contains("$|MF| = d(M,l)$");
        assertThat(latex).contains("$y^2=2px$");
        assertThat(latex).contains("$\\frac{2p}{\\sin^2\\theta}$");
        
        // 验证中文教学内容
        assertThat(latex).contains("抛物线的定义");
        assertThat(latex).contains("评分点");
        assertThat(latex).contains("易错点");
        
        // 验证列表被正确转换
        assertThat(latex).contains("\\begin{enumerate}");
        assertThat(latex).contains("\\begin{itemize}");
        
        // 验证没有被误删成空白
        assertThat(latex.length()).isGreaterThan(2000);
    }
}
