package com.doob.mathagent.teaching;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Collects independent handout versions with explicit dependencies.
 */
public final class TeachingHandoutVersionCollector {

    private TeachingHandoutVersionCollector() {
    }

    /**
     * Runs all three version builders concurrently from the shared reviewed draft.
     *
     * <p>The caller is responsible for giving each builder only the permitted shared outline and evidence. Keeping the
     * versions independent prevents one large teacher artifact from consuming the lecture writer context budget.</p>
     */
    public static TeachingHandoutVersions collect(
            Supplier<String> teacherHandoutSupplier,
            Supplier<String> studentHandoutSupplier,
            Supplier<String> lectureHandoutSupplier) {
        ExecutorService versionExecutor = Executors.newFixedThreadPool(3);
        CompletableFuture<String> teacherFuture = CompletableFuture.supplyAsync(teacherHandoutSupplier, versionExecutor);
        CompletableFuture<String> studentFuture = CompletableFuture.supplyAsync(studentHandoutSupplier, versionExecutor);
        CompletableFuture<String> lectureFuture = CompletableFuture.supplyAsync(lectureHandoutSupplier, versionExecutor);
        try {
            String teacherHandoutLatex = await("teacher handout", teacherFuture);
            String studentHandoutLatex = await("student handout", studentFuture);
            String lectureHandoutLatex = await("lecture handout", lectureFuture);
            return new TeachingHandoutVersions(teacherHandoutLatex, studentHandoutLatex, lectureHandoutLatex);
        } catch (RuntimeException exception) {
            teacherFuture.cancel(true);
            studentFuture.cancel(true);
            lectureFuture.cancel(true);
            throw exception;
        } finally {
            versionExecutor.shutdownNow();
        }
    }

    /**
     * Unwraps async failures so the workflow keeps its existing exception semantics.
     */
    private static String await(String versionName, CompletableFuture<String> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to build " + versionName, cause);
        }
    }
}
