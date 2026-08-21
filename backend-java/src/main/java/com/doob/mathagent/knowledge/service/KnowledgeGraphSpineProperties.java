package com.doob.mathagent.knowledge.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the display-safe high-school math knowledge graph spine.
 *
 * <p>The spine is intentionally small and curated. It is used as the default frontend graph and
 * RAG routing prior instead of exposing noisy OCR/page/formula fragments.</p>
 */
@ConfigurationProperties(prefix = "math-agent.knowledge.graph-spine")
public class KnowledgeGraphSpineProperties {

    /**
     * Whether the backend should seed the curated graph spine during application startup.
     */
    private boolean seedEnabled = true;

    /**
     * Tenant id used for shared seed data.
     */
    private String tenantId = RequestSubject.DEFAULT_TENANT_ID;

    /**
     * Permission scope for the curated graph. MATH_VIP keeps it available to math teaching flows
     * without mixing it with teacher-private documents.
     */
    private String permissionScope = "MATH_VIP";

    /**
     * Maximum number of method nodes imported from the curated source. The production default exceeds the complete
     * v0.2 source so a display budget can never silently remove a searchable teaching method.
     */
    private int methodNodeLimit = 120;

    /**
     * Classpath Markdown source for the curated graph spine.
     */
    private String sourceLocation = "classpath:knowledge/graph-spine-v0.2.md";

    /**
     * Returns whether startup seeding is enabled.
     */
    public boolean isSeedEnabled() {
        return seedEnabled;
    }

    /**
     * Updates whether startup seeding is enabled.
     */
    public void setSeedEnabled(boolean seedEnabled) {
        this.seedEnabled = seedEnabled;
    }

    /**
     * Returns the shared tenant id for seed rows.
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Updates the shared tenant id for seed rows.
     */
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * Returns the permission scope assigned to seed rows.
     */
    public String getPermissionScope() {
        return permissionScope;
    }

    /**
     * Updates the permission scope assigned to seed rows.
     */
    public void setPermissionScope(String permissionScope) {
        this.permissionScope = permissionScope;
    }

    /** Returns the configured method node limit. */
    public int getMethodNodeLimit() {
        return methodNodeLimit;
    }

    /** Updates the configured method node limit. */
    public void setMethodNodeLimit(int methodNodeLimit) {
        this.methodNodeLimit = methodNodeLimit;
    }

    /**
     * Returns the Markdown source location.
     */
    public String getSourceLocation() {
        return sourceLocation;
    }

    /**
     * Updates the Markdown source location.
     */
    public void setSourceLocation(String sourceLocation) {
        this.sourceLocation = sourceLocation;
    }
}
