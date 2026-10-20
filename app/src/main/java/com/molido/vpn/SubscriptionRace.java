package com.molido.vpn;
import java.util.*;
import java.util.concurrent.*;
public final class SubscriptionRace {
    private SubscriptionRace() {}
    public static <T> List<T> fetch(List<? extends Callable<List<T>>> sources, long timeoutMs) {
        List<T> out = new ArrayList<>();
        if (sources.isEmpty()) return out;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(sources.size(), 12), r -> {
            Thread t = new Thread(r, "subscription-source"); t.setDaemon(true); return t;
        });
        CompletionService<List<T>> done = new ExecutorCompletionService<>(pool);
        List<Future<List<T>>> pending = new ArrayList<>();
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            for (Callable<List<T>> task : sources) pending.add(done.submit(task));
            for (int left = sources.size(); left > 0; left--) {
                long wait = end - System.nanoTime();
                if (wait <= 0) break;
                Future<List<T>> f = done.poll(wait, TimeUnit.NANOSECONDS);
                if (f == null) break;
                try { out.addAll(f.get()); } catch (ExecutionException ignored) {}
                if (!out.isEmpty()) end = Math.min(end, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250));
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally {
            for (Future<List<T>> f : pending) f.cancel(true);
            pool.shutdownNow();
        }
        return out;
    }
}
