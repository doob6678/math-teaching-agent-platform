package com.doob.mathagent.teaching.service.rendering.standard;

import com.doob.mathagent.teaching.service.rendering.HandoutTemplateStrategy;

/**
 * Standard A4 teacher/student handout renderer.
 *
 * <p>This is the visual contract represented by {@code verify-pdf-fix-teacher.pdf}: a centered title, topic on the
 * right side of the header, attribution in the footer, and ordinary A4 pagination. Formulas are still emitted only
 * through the shared XeLaTeX compiler; this class contains layout, never mathematical content.</p>
 */
public final class StandardHandoutTemplateStrategy implements HandoutTemplateStrategy {

    @Override
    public boolean supports(String templateName) {
        return templateName == null || templateName.isBlank()
                || !templateName.toLowerCase(java.util.Locale.ROOT).contains("zhaolixian");
    }

    @Override
    public String documentOptions(boolean lecture) {
        return lecture ? "10pt" : "11pt,a4paper";
    }

    @Override
    public String geometryOptions(boolean lecture) {
        return lecture
                ? "paperwidth=16in,paperheight=10in,top=14mm,bottom=18mm,left=18mm,right=18mm"
                : "a4paper,top=24mm,bottom=23mm,left=22mm,right=22mm";
    }

    @Override
    public String headerFooterCommands(String watermark, String headerTopic, String footerText) {
        return """
                \\fancyhf{}
                \\lhead{%s}
                \\rhead{%s}
                \\lfoot{%s}
                \\rfoot{第 \\thepage 页 / 共 \\pageref{LastPage} 页}
                \\renewcommand{\\headrulewidth}{0.4pt}
                \\renewcommand{\\footrulewidth}{0.3pt}
                """.formatted(watermark, headerTopic, footerText);
    }

    @Override
    public String headingCommands() {
        return """
                \\titleformat{\\section}
                  {\\HandoutDisplayFont\\Large\\bfseries\\color{HandoutAccent}}
                  {}{0pt}{\\makebox[0pt][r]{\\color{HandoutAccent}\\rule{4pt}{1.15em}\\hspace{0.7em}}}
                  [{\\vspace{0.2em}\\color{HandoutAccent!35}\\titlerule[0.5pt]}]
                \\titleformat{\\subsection}
                  {\\HandoutDisplayFont\\large\\bfseries\\color{HandoutAccent}}
                  {}{0pt}{\\makebox[0pt][r]{\\color{HandoutAccent!80}\\rule{3pt}{1em}\\hspace{0.6em}}}
                \\titleformat{\\paragraph}{\\HandoutDisplayFont\\normalsize\\bfseries\\color{HandoutText}}{}{0pt}{}
                \\titlespacing*{\\section}{0pt}{1.45em}{0.8em}
                \\titlespacing*{\\subsection}{0pt}{1.1em}{0.55em}
                """;
    }

    @Override
    public String titleBlock(String title, String watermark, boolean lecture) {
        if (lecture) {
            return "";
        }
        return """
                \\begin{center}
                {\\LARGE\\bfseries\\color{HandoutAccent} %s}\\\\[0.35em]
                {\\small\\color{HandoutText} %s}
                \\end{center}
                \\vspace{0.6em}
                """.formatted(title, watermark);
    }

    @Override
    public String bodySizeCommand(boolean lecture) {
        return lecture ? "\\setlength{\\parskip}{0.5em}" : "";
    }
}
