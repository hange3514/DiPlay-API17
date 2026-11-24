package local.headunit.diplay;

import java.io.Closeable;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bound the whole operation as well as each socket read; owned by exactly one request. */
final class NetworkDeadline implements Closeable {
    private final HttpURLConnection connection;
    private final long deadlineNs;
    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean disconnecting = new AtomicBoolean();
    private final AtomicBoolean abortScheduled = new AtomicBoolean();

    NetworkDeadline(HttpURLConnection connection, long timeoutMs) {
        if (timeoutMs < 0) throw new IllegalArgumentException("Negative request timeout");
        this.connection = connection;
        deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        // A vehicle can correct its wall clock while networking starts. Delays and
        // checks both use elapsed time, and each request owns its disconnect thread.
        scheduler = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "diplay-http-deadline");
                thread.setDaemon(true);
                return thread;
            }
        });
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.schedule(new Runnable() {
            @Override public void run() { scheduleAbort(); }
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void scheduleAbort() {
        // API17 may accept a TCP socket before HttpEngine publishes its connection.
        // A disconnect in that gap is a no-op. Retry until the request owner closes.
        if (!abortScheduled.compareAndSet(false, true)) return;
        try {
            scheduler.scheduleWithFixedDelay(new Runnable() {
                @Override public void run() { disconnect(); }
            }, 0, 100, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException alreadyClosed) { }
    }

    private void disconnect() {
        if (!disconnecting.compareAndSet(false, true)) return;
        try {
            connection.disconnect();
        } catch (RuntimeException ignored) {
        } finally {
            disconnecting.set(false);
        }
    }

    void check() throws InterruptedIOException {
        if (cancelled.get()) throw new InterruptedIOException("Request cancelled");
        if (System.nanoTime() - deadlineNs >= 0) {
            scheduleAbort();
            throw new SocketTimeoutException("Request deadline exceeded");
        }
    }

    void cancel() {
        cancelled.set(true);
        scheduleAbort();
    }

    @Override public void close() {
        scheduler.shutdown();
        disconnect();
    }
}
