package com.doob.mathagent.retrieval;

import java.util.Locale;

/** User-selectable strategy whose code is shared by requests, cache keys, and response diagnostics. */
public enum TextbookRetrievalMode {
    HYBRID("hybrid", "混合 RAG", "BGE 文本召回与 CLIP 页面图像召回合并后，使用 BGE 重排确认教材证据。", true, true),
    TEXT_BGE("text_bge", "文本语义检索", "使用 BGE 教材页文本索引召回，并使用 BGE 重排确认相关性。", true, false),
    FORMULA_BGE("formula_bge", "公式语义检索", "将 LaTeX 公式与主题词送入 BGE 文本索引和重排器。", true, false),
    IMAGE_CLIP("image_clip", "公式图片检索", "使用 CLIP 比较上传公式图与教材页面图，再由 BGE 重排文本证据。", false, true);

    private final String code;
    private final String label;
    private final String description;
    private final boolean usesTextPageIndex;
    private final boolean usesClipPageIndex;

    TextbookRetrievalMode(String code, String label, String description, boolean usesTextPageIndex, boolean usesClipPageIndex) {
        this.code = code;
        this.label = label;
        this.description = description;
        this.usesTextPageIndex = usesTextPageIndex;
        this.usesClipPageIndex = usesClipPageIndex;
    }

    public String code() { return code; }
    public String label() { return label; }
    public String description() { return description; }
    public boolean usesTextPageIndex() { return usesTextPageIndex; }
    public boolean usesClipPageIndex() { return usesClipPageIndex; }

    /** Converts optional HTTP input to a safe default instead of allowing an unimplemented retrieval branch. */
    public static TextbookRetrievalMode from(String rawValue) {
        String normalized = rawValue == null ? "" : rawValue.strip().toLowerCase(Locale.ROOT);
        for (TextbookRetrievalMode mode : values()) {
            if (mode.code.equals(normalized)) return mode;
        }
        return HYBRID;
    }
}
