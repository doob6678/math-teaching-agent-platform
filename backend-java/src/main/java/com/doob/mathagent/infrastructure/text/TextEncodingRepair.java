package com.doob.mathagent.infrastructure.text;

import java.nio.charset.StandardCharsets;

/** Repairs text that a provider or proxy decoded with the wrong single-byte charset. */
public final class TextEncodingRepair {

    private TextEncodingRepair() {
    }

    /** Repairs UTF-8-as-Latin-1 corruption only when conversion increases readable CJK content. */
    public static String repairMojibake(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (!looksLikeMojibake(value)) {
            return value;
        }
        String repaired = new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        return cjkCount(repaired) > cjkCount(value) ? repaired : value;
    }

    private static boolean looksLikeMojibake(String value) {
        return value.indexOf('\u00e9') >= 0 || value.indexOf('\u00e8') >= 0
                || value.indexOf('\u00e4') >= 0 || value.indexOf('\u00e5') >= 0
                || value.indexOf('\u00e3') >= 0 || value.indexOf('\u00ef') >= 0
                || value.indexOf('\u0098') >= 0 || value.indexOf('\u0080') >= 0
                || value.indexOf('\u0082') >= 0;
    }

    private static long cjkCount(String value) {
        return value.codePoints().filter(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF).count();
    }
}
