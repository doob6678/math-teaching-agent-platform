package com.doob.mathagent.resources;

import java.nio.file.Path;
import java.util.Map;
import org.springframework.core.env.Environment;

/**
 * Local project resource path configuration.
 *
 * <p>These paths point to test data, design documents, reference PDFs, and local file storage. They must be
 * configured through Spring properties or environment variables so future deployments can change paths without code
 * edits.</p>
 *
 * @param projectTestDataRoot project test data directory
 * @param designSpecRoot design scheme document directory
 * @param referenceHandoutPdf reference handout PDF used for visual/layout comparison
 * @param promptDesignPdf prompt design PDF used for AI workflow design reference
 * @param localFileStorageRoot local file storage root for uploaded files and generated artifacts
 * @param teacherResourceUploadRoot managed root for teacher resource uploads */
public record ProjectResourceProperties(
        Path projectTestDataRoot,
        Path designSpecRoot,
        Path referenceHandoutPdf,
        Path promptDesignPdf,
        Path localFileStorageRoot,
        Path teacherResourceUploadRoot) {

    /** Environment variable for the project test data directory. */
    static final String PROJECT_TEST_DATA_ROOT_KEY = "MATH_AGENT_PROJECT_TEST_DATA_ROOT";

    /** Environment variable for the design scheme directory. */
    static final String DESIGN_SPEC_ROOT_KEY = "MATH_AGENT_DESIGN_SPEC_ROOT";

    /** Environment variable for the reference handout PDF. */
    static final String REFERENCE_HANDOUT_PDF_KEY = "MATH_AGENT_REFERENCE_HANDOUT_PDF";

    /** Environment variable for the prompt design PDF. */
    static final String PROMPT_DESIGN_PDF_KEY = "MATH_AGENT_PROMPT_DESIGN_PDF";

    /** Environment variable for local uploaded/generated file storage. */
    static final String LOCAL_FILE_STORAGE_ROOT_KEY = "MATH_AGENT_LOCAL_FILE_STORAGE_ROOT";

    /** Environment variable for the managed teacher resource upload root. */
    static final String TEACHER_RESOURCE_UPLOAD_ROOT_KEY = "MATH_AGENT_TEACHER_RESOURCE_UPLOAD_ROOT";

    public ProjectResourceProperties(
            Path projectTestDataRoot,
            Path designSpecRoot,
            Path referenceHandoutPdf,
            Path promptDesignPdf,
            Path localFileStorageRoot) {
        this(
                projectTestDataRoot,
                designSpecRoot,
                referenceHandoutPdf,
                promptDesignPdf,
                localFileStorageRoot,
                localFileStorageRoot.resolve("teacher-resource-uploads"));
    }

    /**
     * Normalizes every configured path to an absolute path.
     */
    public ProjectResourceProperties {
        projectTestDataRoot = normalize(projectTestDataRoot);
        designSpecRoot = normalize(designSpecRoot);
        referenceHandoutPdf = normalize(referenceHandoutPdf);
        promptDesignPdf = normalize(promptDesignPdf);
        localFileStorageRoot = normalize(localFileStorageRoot);
        teacherResourceUploadRoot = normalize(teacherResourceUploadRoot);
    }

    /**
     * Reads local resource paths from the process environment.
     *
     * @return local project resource properties
     */
    public static ProjectResourceProperties fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /**
     * Reads local resource paths from an injectable environment map.
     *
     * @param environment environment map
     * @return local project resource properties
     */
    public static ProjectResourceProperties fromEnvironment(Map<String, String> environment) {
        return fromValues(
                environment.get(PROJECT_TEST_DATA_ROOT_KEY),
                environment.get(DESIGN_SPEC_ROOT_KEY),
                environment.get(REFERENCE_HANDOUT_PDF_KEY),
                environment.get(PROMPT_DESIGN_PDF_KEY),
                environment.get(LOCAL_FILE_STORAGE_ROOT_KEY),
                environment.get(TEACHER_RESOURCE_UPLOAD_ROOT_KEY));
    }

    /**
     * Reads local resource paths from Spring properties first, then falls back to environment variable names.
     *
     * @param environment Spring environment
     * @return local project resource properties
     */
    public static ProjectResourceProperties fromSpringEnvironment(Environment environment) {
        return fromValues(
                property(environment, "math-agent.resources.project-test-data-root", PROJECT_TEST_DATA_ROOT_KEY),
                property(environment, "math-agent.resources.design-spec-root", DESIGN_SPEC_ROOT_KEY),
                property(environment, "math-agent.resources.reference-handout-pdf", REFERENCE_HANDOUT_PDF_KEY),
                property(environment, "math-agent.resources.prompt-design-pdf", PROMPT_DESIGN_PDF_KEY),
                property(environment, "math-agent.resources.local-file-storage-root", LOCAL_FILE_STORAGE_ROOT_KEY),
                property(environment, "math-agent.resources.teacher-resource-upload-root", TEACHER_RESOURCE_UPLOAD_ROOT_KEY));
    }

    /**
     * Creates properties from raw string values and validates every required path is present.
     *
     * @param projectTestDataRoot project test data directory
     * @param designSpecRoot design scheme directory
     * @param referenceHandoutPdf reference handout PDF
     * @param promptDesignPdf prompt design PDF
     * @param localFileStorageRoot local file storage root
     * @param teacherResourceUploadRoot managed teacher resource upload root
     * @return local project resource properties
     */
    private static ProjectResourceProperties fromValues(
            String projectTestDataRoot,
            String designSpecRoot,
            String referenceHandoutPdf,
            String promptDesignPdf,
            String localFileStorageRoot,
            String teacherResourceUploadRoot) {
        return new ProjectResourceProperties(
                path(projectTestDataRoot, PROJECT_TEST_DATA_ROOT_KEY),
                path(designSpecRoot, DESIGN_SPEC_ROOT_KEY),
                path(referenceHandoutPdf, REFERENCE_HANDOUT_PDF_KEY),
                path(promptDesignPdf, PROMPT_DESIGN_PDF_KEY),
                path(localFileStorageRoot, LOCAL_FILE_STORAGE_ROOT_KEY),
                pathOrDefault(
                        teacherResourceUploadRoot,
                        Path.of(localFileStorageRoot).resolve("teacher-resource-uploads").toString(),
                        TEACHER_RESOURCE_UPLOAD_ROOT_KEY));
    }

    private static Path pathOrDefault(String value, String defaultValue, String key) {
        return Path.of(value == null || value.isBlank() ? defaultValue : value);
    }

    /**
     * Reads a Spring property and falls back to an environment variable style key.
     *
     * @param environment Spring environment
     * @param propertyName Spring property name
     * @param environmentName environment variable name
     * @return configured value
     */
    private static String property(Environment environment, String propertyName, String environmentName) {
        String value = environment.getProperty(propertyName);
        return value == null || value.isBlank() ? environment.getProperty(environmentName) : value;
    }

    /**
     * Converts a required string path to a Path.
     *
     * @param value raw path value
     * @param key configuration key used in error messages
     * @return path
     */
    private static Path path(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required resource path: " + key);
        }
        return Path.of(value);
    }

    /**
     * Normalizes a configured path to an absolute path.
     *
     * @param path configured path
     * @return absolute normalized path
     */
    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
