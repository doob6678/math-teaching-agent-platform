package com.doob.mathagent.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class TeachingHandoutPdfExportServiceTest {

    @Test
    void rendersReadableChineseHandoutInsteadOfRawLatexSource() throws Exception {
        TeachingTaskResponse task = new TeachingTaskResponse(
                "task-chinese-pdf",
                "client-chinese-pdf",
                "school-a",
                "teacher",
                "teacher-001",
                TeachingTaskStatus.COMPLETED,
                "学会双曲线的大题和小题",
                "从定义开始掌握双曲线解题",
                List.of(),
                List.of(),
                List.of(),
                "\\section{学习目标}\n掌握双曲线定义与参数关系。\n\\begin{itemize}\n\\item 标准方程和渐近线\n\\end{itemize}",
                "\\section{教师版}\n双曲线定义：两焦点距离差的绝对值为定值$2a$。\n# p159\n- 页图：![p159](../../pages/p159.png)\n模型openai/gpt-5.5 tokens=1759\n\\paragraph{关键方法}\n\\begin{itemize}\n\\item 先确定$a,b,c$，再写方程。\n\\item 参数关系：$c^2=a^2+b^2$，比例式可写为$\\frac{x}{y}$。\n\\end{itemize}",
                "\\section{学生版}\n填写双曲线定义，并完成参数计算。",
                List.of(),
                null,
                List.of(),
                null,
                null);

        byte[] pdf = new TeachingHandoutPdfExportService().render(task, "teacher");

        assertThat(pdf).startsWith(new byte[] {'%', 'P', 'D', 'F'});
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("教师版讲义", "模板：标准讲义", "双曲线定义", "参数关系");
            assertThat(text).doesNotContain("\\section", "\\item", "\\frac", "c^2", "a^2", "b^2", "![p159]", "../../pages", "tokens", "gpt-5.5", "AI教师", "AI 讲义草稿", "????");
        }
    }

    @Test
    void rendersDifferentTeacherAndStudentVersionHeaders() throws Exception {
        TeachingTaskResponse task = new TeachingTaskResponse(
                "task-versioned-pdf",
                "client-versioned-pdf",
                "school-a",
                "teacher",
                "teacher-001",
                TeachingTaskStatus.COMPLETED,
                "掌握反比例函数",
                "生成学生练习和教师答案",
                List.of(),
                List.of(),
                List.of(),
                "",
                "\\section{教师版}\n教师讲解：先判断 k 的符号，再讲图像所在象限。",
                "\\section{学生版}\n学生任务：补全定义、图像和空白解答区。",
                List.of(),
                null,
                List.of(),
                null,
                null);

        byte[] teacherPdf = new TeachingHandoutPdfExportService().render(task, "teacher");
        byte[] studentPdf = new TeachingHandoutPdfExportService().render(task, "student");

        assertThat(teacherPdf).isNotEqualTo(studentPdf);
        try (PDDocument teacherDocument = Loader.loadPDF(teacherPdf);
                PDDocument studentDocument = Loader.loadPDF(studentPdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            assertThat(stripper.getText(teacherDocument)).contains("教师版讲义", "教师版", "教师讲解");
            assertThat(stripper.getText(studentDocument)).contains("学生版讲义", "学生版", "学生任务");
        }
    }
}
