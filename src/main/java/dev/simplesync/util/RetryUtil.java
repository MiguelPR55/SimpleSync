package dev.simplesync.util;

import dev.simplesync.SimpleSync;
import java.io.IOException;
import java.util.concurrent.Callable;

public class RetryUtil {

    public static <T> T retry(int maxAttempts, String operationName, Callable<T> action) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.call();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(operationName + " interrupted", e);
            } catch (IOException e) {
                lastError = e;
                SimpleSync.LOGGER.warn("[SimpleSync] {} attempt {}/{} failed: {}", operationName, attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        long baseDelay = 1000L * (1L << (attempt - 1));
                        long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(-baseDelay / 5, baseDelay / 5 + 1);
                        Thread.sleep(Math.max(100L, baseDelay + jitter));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException(operationName + " interrupted during retry delay", ie);
                    }
                }
            } catch (Exception e) {
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new IOException(e);
            }
        }
        throw lastError != null ? lastError : new IOException(operationName + " failed after retries");
    }

    public static void retryVoid(int maxAttempts, String operationName, RunnableWithException action) throws IOException {
        retry(maxAttempts, operationName, () -> {
            action.run();
            return null;
        });
    }

    @FunctionalInterface
    public interface RunnableWithException {
        void run() throws Exception;
    }
}
