package com.doob.mathagent.teacher.feishu;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Resolves the Python interpreter used by Feishu subprocess clients.
 *
 * <p>The explicit environment variables keep local Windows development and containerized
 * execution configurable, while probing the common command names prevents one Feishu entry
 * point from succeeding and the other from failing because PATH exposes only {@code python3}.
 */
final class PythonExecutableResolver {

    static final String PYTHON_EXECUTABLE_ENVIRONMENT_VARIABLE = "MATH_AGENT_PYTHON_EXECUTABLE";
    static final String WORKER_PYTHON_ENVIRONMENT_VARIABLE = "MATH_AGENT_WORKER_PYTHON";
    private static final long PROBE_TIMEOUT_SECONDS = 2L;
    private static final List<String> CANDIDATES = List.of("python3", "python");

    private PythonExecutableResolver() {
    }

    /**
     * Returns an explicitly configured interpreter, then the first runnable PATH candidate.
     *
     * @return executable name or path accepted by {@link ProcessBuilder}
     */
    static String resolve() {
        String configured = configuredValue(PYTHON_EXECUTABLE_ENVIRONMENT_VARIABLE);
        if (configured != null) {
            return configured;
        }
        configured = configuredValue(WORKER_PYTHON_ENVIRONMENT_VARIABLE);
        if (configured != null) {
            return configured;
        }
        for (String candidate : CANDIDATES) {
            if (isRunnable(candidate)) {
                return candidate;
            }
        }
        return isWindows() ? "python" : "python3";
    }

    private static String configuredValue(String variableName) {
        String value = System.getenv(variableName);
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static boolean isRunnable(String executable) {
        Process process = null;
        try {
            process = new ProcessBuilder(executable, "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }
}
