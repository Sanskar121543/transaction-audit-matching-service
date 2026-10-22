package io.github.sanskar121543.reconciliation.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

public final class Concurrently {

    private Concurrently() {
    }

    /**
     * Runs {@code count} tasks on {@code count} threads that all wait on one start gate,
     * so they hit the server at the same moment instead of trickling in.
     */
    public static <T> List<T> run(int count, IntFunction<T> task) {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return task.apply(index);
                }));
            }
            startGate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }
}
