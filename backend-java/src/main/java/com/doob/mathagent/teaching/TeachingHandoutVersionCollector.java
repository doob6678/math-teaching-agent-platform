package com.doob.mathagent.teaching;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Collects independent handout versions with explicit dependencies.
 */
public final class TeachingHandoutVersionCollector {

    private static final int VERSION_PARALLELISM = 3;
    private static final int VERSION_QUEUE_CAPACITY = 48;
    /** Shared bounded pool prevents every request from allocating three new worker threads. */
    private static final ExecutorService VERSION_EXECUTOR = new ThreadPoolExecutor(
            VERSION_PARALLELISM,
            VERSION_PARALLELISM,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(VERSION_QUEUE_CAPACITY),
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.CallerRunsPolicy());

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
        CompletableFuture<String> teacherFuture = CompletableFuture.supplyAsync(teacherHandoutSupplier, VERSION_EXECUTOR);
        CompletableFuture<String> studentFuture = CompletableFuture.supplyAsync(studentHandoutSupplier, VERSION_EXECUTOR);
        CompletableFuture<String> lectureFuture = CompletableFuture.supplyAsync(lectureHandoutSupplier, VERSION_EXECUTOR);
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
