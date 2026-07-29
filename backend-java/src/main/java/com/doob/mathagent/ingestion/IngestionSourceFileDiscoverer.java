package com.doob.mathagent.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Finds actual PDF/DOCX inputs and hashes their bytes before parsing. Sorting by root-relative path makes checkpoint
 * progress reproducible across runs, while hashing prevents a changed file from being mistaken for a completed one.
 */
public final class IngestionSourceFileDiscoverer {
    private static final String PDF_MEDIA_TYPE = "application/pdf";
    private static final String DOCX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /**
     * Recursively discovers supported source documents. Unsupported files are intentionally ignored rather than
     * sent to the model; a caller can report them separately without paying for an invalid parse request.
     *
     * @param inputRoot existing input directory selected for this run
     * @return deterministic, byte-hashed input list
     * @throws IOException when the directory cannot be read or a candidate cannot be hashed
     */
    public List<DiscoveredSourceFile> discover(Path inputRoot) throws IOException {
        if (inputRoot == null || !Files.isDirectory(inputRoot)) {
            throw new IllegalArgumentException("inputRoot must be an existing directory");
        }
        try (var paths = Files.walk(inputRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(path -> toDiscoveredFile(inputRoot, path))
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::get)
                    .sorted(Comparator.comparing(file -> inputRoot.relativize(file.path()).toString()))
                    .toList();
        }
    }

    /** Converts a supported path into a record, keeping extension classification independent from a model. */
    private static java.util.Optional<DiscoveredSourceFile> toDiscoveredFile(Path inputRoot, Path path) {
        String mediaType = mediaType(path);
        if (mediaType == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new DiscoveredSourceFile(
                    path.toAbsolutePath().normalize(),
                    // Progress displays need a readable base name, while the internal Path keeps the unambiguous
                    // recursive location for hashing and checkpoint identity.
                    path.getFileName().toString(),
                    mediaType,
                    sha256(path)));
        } catch (IOException exception) {
            throw new SourceDiscoveryException(path, exception);
        }
    }

    /** Maps supported extensions explicitly; MIME probing is platform dependent and unsuitable for checkpoints. */
    private static String mediaType(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return PDF_MEDIA_TYPE;
        }
        if (name.endsWith(".docx")) {
            return DOCX_MEDIA_TYPE;
        }
        return null;
    }

    /** Streams a document into SHA-256 so large exam files are never loaded as one heap allocation. */
    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(path)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Preserves the exact unreadable file in diagnostics without letting a partial discovery look successful. */
    private static final class SourceDiscoveryException extends RuntimeException {
        private SourceDiscoveryException(Path path, IOException cause) {
            super("Unable to hash source file: " + path, cause);
        }
    }
}
