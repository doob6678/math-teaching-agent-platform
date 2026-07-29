package com.doob.mathagent.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Explicit filesystem contract for the WSL/Docker ingestion run. Paths are intentionally configuration-file values:
 * examination input must not depend on per-shell GAOKAO/INPUT environment variables that make a run irreproducible.
 */
@ConfigurationProperties(prefix = "math-agent.ingestion")
public class IngestionProperties {
    /** Docker bind-mounted root containing the configured real-paper source collection. */
    private String inputRoot = "/app/data/gaokao-input";

    /** Docker bind-mounted evidence destination retained outside the backend container lifecycle. */
    private String evidenceRoot = "/app/data/gaokao-evidence";

    /** Verified page-level Luna derivative bound; original page PNG remains stored beside this JPEG. */
    private int initialReviewMaxLongEdgePixels = 960;

    /** Verified JPEG quality for page-level screening, not for geometry or formula final review. */
    private float initialReviewJpegQuality = 0.82F;

    /** Explicit 2024 source whitelist; an ingestion command must never sweep unrelated PDFs in the mounted year. */
    private List<String> selectedSourceFileNames = List.of();

    public String getInputRoot() {
        return inputRoot;
    }

    public void setInputRoot(String inputRoot) {
        this.inputRoot = requirePath(inputRoot, "inputRoot");
    }

    public String getEvidenceRoot() {
        return evidenceRoot;
    }

    public void setEvidenceRoot(String evidenceRoot) {
        this.evidenceRoot = requirePath(evidenceRoot, "evidenceRoot");
    }

    public int getInitialReviewMaxLongEdgePixels() { return initialReviewMaxLongEdgePixels; }

    public void setInitialReviewMaxLongEdgePixels(int value) {
        if (value < 1) throw new IllegalArgumentException("initialReviewMaxLongEdgePixels must be positive");
        this.initialReviewMaxLongEdgePixels = value;
    }

    public float getInitialReviewJpegQuality() { return initialReviewJpegQuality; }

    public void setInitialReviewJpegQuality(float value) {
        if (value <= 0 || value > 1) throw new IllegalArgumentException("initialReviewJpegQuality must be in (0,1]");
        this.initialReviewJpegQuality = value;
    }

    public List<String> getSelectedSourceFileNames() { return selectedSourceFileNames; }

    public void setSelectedSourceFileNames(List<String> value) {
        selectedSourceFileNames = value == null ? List.of() : value.stream().filter(name -> name != null && !name.isBlank()).map(String::strip).toList();
    }

    /** Rejects empty configuration early rather than letting a batch command resolve an accidental current directory. */
    private static String requirePath(String path, String name) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(name + " must be configured");
        }
        return path.strip();
    }
}
