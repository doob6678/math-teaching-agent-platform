package com.doob.mathagent.teaching.service.rendering.zhao;

import com.doob.mathagent.teaching.service.rendering.HandoutTemplateStrategy;

/**
 * Zhao continuous-question handout renderer.
 *
 * <p>The page size, vector header mark, alternating footer tab, navy title tab, and compact spacing are deliberately
 * isolated from the standard renderer. This keeps the 582x812 reference contract stable and lets users select it
 * without changing the A4 teacher/student output.</p>
 */
public final class ZhaoHandoutTemplateStrategy implements HandoutTemplateStrategy {

    @Override
    public boolean supports(String templateName) {
        String value = templateName == null ? "" : templateName.toLowerCase(java.util.Locale.ROOT);
        // Persisted codes use snake_case while configured display names are mutable. Compare a compact code identity
        // so teacher/student labels cannot reroute the same selected template into the standard A4 renderer.
        String compactAscii = value.replaceAll("[^a-z0-9]", "");
        return compactAscii.contains("zhaolixian") || value.contains("赵礼显");
    }

    @Override
    public String documentOptions(boolean lecture) {
        return lecture ? "10pt" : "11pt";
    }

    @Override
    public String geometryOptions(boolean lecture) {
        return lecture
                ? "paperwidth=16in,paperheight=10in,top=14mm,bottom=18mm,left=18mm,right=18mm"
                : "paperwidth=582bp,paperheight=812bp,top=26mm,bottom=25mm,left=72bp,right=72bp";
    }

    @Override
    public String headerFooterCommands(String watermark, String headerTopic, String footerText) {
        return """
                \\newcommand{\\zhaopagetab}{\\tikz[baseline=-0.56ex,x=2.4ex,y=2.4ex]{
                  \\draw[HandoutText,line width=0.08ex] (0,0.22) -- (6.5,0.22);
                  \\draw[HandoutText,line width=0.08ex,rounded corners=0.18ex] (0.25,0.22) -- (0.48,1.05) -- (2.15,1.05) -- (2.38,0.22);
                  \\node[font=\\scriptsize,text=HandoutText] at (1.31,0.65) {\\thepage};}}
                \\setlength{\\headheight}{39pt}
                \\fancyhf{}
                \\chead{\\tikz[baseline=-0.65ex]{
                  \\draw[HandoutText,line width=0.08ex] (0,0) -- (15.8,0);
                  \\node[anchor=west,font=\\sffamily\\scriptsize\\bfseries,text=HandoutAccent] at (0,0.28) {%s};
                  \\draw[HandoutAccent,line width=0.14ex] (15.1,0.10) rectangle (15.55,0.55);
                  \\draw[ZhaoOrange,line width=0.11ex] (15.25,0.23) rectangle (15.70,0.68);}}
                \\lfoot{\\ifodd\\value{page}\\zhaopagetab\\fi}
                \\rfoot{\\ifodd\\value{page}\\else\\zhaopagetab\\fi}
                \\renewcommand{\\headrulewidth}{0pt}
                \\renewcommand{\\footrulewidth}{0pt}
                """.formatted(watermark);
    }

    @Override
    public String headingCommands() {
        return """
                \\newcommand{\\zhaosectiontitle}[1]{\\colorbox{HandoutAccent}{\\strut\\hspace{0.52em}\\color{white}\\HandoutDisplayFont\\bfseries #1\\hspace{0.52em}}}
                \\titleformat{\\section}{\\HandoutDisplayFont\\large\\bfseries\\color{HandoutAccent}}{}{0pt}{\\zhaosectiontitle}
                \\titleformat{\\subsection}{\\HandoutDisplayFont\\normalsize\\bfseries\\color{HandoutText}}{}{0pt}{}
                \\titleformat{\\subsubsection}{\\HandoutDisplayFont\\normalsize\\bfseries\\color{HandoutText}}{}{0pt}{}
                \\titleformat{\\paragraph}{\\HandoutDisplayFont\\normalsize\\bfseries\\color{HandoutText}}{}{0pt}{}
                \\titlespacing*{\\section}{0pt}{1.0em}{0.55em}
                \\titlespacing*{\\subsection}{0pt}{0.72em}{0.32em}
                \\titlespacing*{\\subsubsection}{0pt}{0.55em}{0.25em}
                """;
    }

    @Override
    public String titleBlock(String title, String watermark, boolean lecture) {
        return "";
    }

    @Override
    public String bodySizeCommand(boolean lecture) {
        return lecture
                ? "\\setlength{\\parskip}{0.5em}"
                : "\\setlength{\\parskip}{0.24em}\\setlist[itemize]{itemsep=0.12em,topsep=0.16em}\\setlist[enumerate]{itemsep=0.12em,topsep=0.16em}";
    }
}
