package com.doob.mathagent.teaching.service.rendering;

/**
 * Owns the visual contract of one handout template family.
 *
 * <p>Content generation never decides paper size, typography, or chrome. The teaching exporter selects one
 * strategy from the backend-owned template code and asks it for the complete visual fragments used by XeLaTeX.
 * Keeping these decisions behind this boundary prevents the standard A4 and Zhao continuous-page layouts from
 * changing each other when a new template is added.</p>
 */
public interface HandoutTemplateStrategy {

    /** Returns whether this renderer owns the supplied backend template identity. */
    boolean supports(String templateName);

    /** Returns the document class options for the requested audience/version. */
    String documentOptions(boolean lecture);

    /** Returns geometry options measured in the target PDF coordinate system. */
    String geometryOptions(boolean lecture);

    /** Returns renderer-owned header/footer commands; arguments are already display-safe LaTeX labels. */
    /** Builds page chrome from four independent persisted labels. */
    String headerFooterCommands(String headerLeft, String headerRight, String footerLeft, String footerRight);

    /** Returns title hierarchy commands. */
    String headingCommands();

    /** Returns the optional opening title block. */
    String titleBlock(String title, String watermark, boolean lecture);

    /** Returns body spacing commands specific to this template family. */
    String bodySizeCommand(boolean lecture);
}
