package com.core.storagetracking.scan.folder;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Independent worker and bounded-queue collector lifecycle for Folder Scan mode.
 * Does not reference Android UI classes or FileScanner classes.
 */
public final class FolderScanController {
    private static final List<DirectoryDelta> SENTINEL = Collections.emptyList();

    public interface ScanExecutor {
        FolderSummary execute(
                FolderConfig config,
                java.util.function.Consumer<List<DirectoryDelta>> onBatch,
                java.util.function.Consumer<FolderProgress> onProgress,
                java.util.function.BooleanSupplier shouldCancel);
    }

    public static final class RunSnapshot {
        private final int runId;
        private final FolderResultStore store;
        private final FolderProgress progress;
        private final FolderSummary summary;
        private final boolean finished;
        private final String error;
        private final double elapsedSeconds;

        public RunSnapshot(
                int runId,
                FolderResultStore store,
                FolderProgress progress,
                FolderSummary summary,
                boolean finished,
                String error,
                double elapsedSeconds) {
            this.runId = runId;
            this.store = store;
            this.progress = progress;
            this.summary = summary;
            this.finished = finished;
            this.error = error;
            this.elapsedSeconds = elapsedSeconds;
        }

        public int getRunId() {
            return runId;
        }

        public FolderResultStore getStore() {
            return store;
        }

        public FolderProgress getProgress() {
            return progress;
        }

        public FolderSummary getSummary() {
            return summary;
        }

        public boolean isFinished() {
            return finished;
        }

        public String getError() {
            return error;
        }

        public double getElapsedSeconds() {
            return elapsedSeconds;
        }
    }

    private static final class Run {
        final int runId;
        final FolderConfig config;
        final FolderResultStore store;
        final ArrayBlockingQueue<List<DirectoryDelta>> queue = new ArrayBlockingQueue<>(8);
        final AtomicBoolean cancel = new AtomicBoolean(false);
        final AtomicBoolean superseded = new AtomicBoolean(false);
        final Object stateLock = new Object();
        final long startedNanos = System.nanoTime();
        FolderProgress progress = new FolderProgress();
        FolderSummary summary;
        boolean finished;
        String error;

        Run(int runId, FolderConfig config, FolderResultStore store) {
            this.runId = runId;
            this.config = config;
            this.store = store;
        }
    }

    private final ReentrantLock scanLock;
    private final ScanExecutor executor;
    private Run currentRun;
    private int nextId = 0;
    private boolean closed = false;

    public FolderScanController() {
        this(new ReentrantLock(), FolderScanner::scan);
    }

    public FolderScanController(ReentrantLock scanLock) {
        this(scanLock, FolderScanner::scan);
    }

    public FolderScanController(ReentrantLock scanLock, ScanExecutor executor) {
        this.scanLock = scanLock != null ? scanLock : new ReentrantLock();
        this.executor = executor != null ? executor : FolderScanner::scan;
    }

    public synchronized RunSnapshot start(FolderConfig config) {
        if (closed) {
            throw new IllegalStateException("Controller is closed");
        }
        if (currentRun != null) {
            currentRun.cancel.set(true);
            currentRun.superseded.set(true);
        }
        nextId++;
        Run run = new Run(nextId, config, new FolderResultStore(config.getRoots()));
        currentRun = run;

        Thread collectorThread = new Thread(() -> collect(run), "folder-results-" + run.runId);
        collectorThread.setDaemon(true);
        collectorThread.start();

        Thread workerThread = new Thread(() -> work(run), "folder-scan-" + run.runId);
        workerThread.setDaemon(true);
        workerThread.start();

        return snapshot();
    }

    public synchronized void cancel() {
        if (currentRun != null) {
            currentRun.cancel.set(true);
        }
    }

    public synchronized void close() {
        closed = true;
        if (currentRun != null) {
            currentRun.cancel.set(true);
            currentRun.superseded.set(true);
        }
    }

    public synchronized RunSnapshot snapshot() {
        Run run = currentRun;
        if (run == null) {
            return null;
        }
        synchronized (run.stateLock) {
            double elapsed = run.summary != null
                    ? run.summary.getElapsedSeconds()
                    : (System.nanoTime() - run.startedNanos) / 1_000_000_000.0;
            return new RunSnapshot(
                    run.runId,
                    run.store,
                    run.progress,
                    run.summary,
                    run.finished,
                    run.error,
                    elapsed);
        }
    }

    private void work(Run run) {
        boolean acquired = false;
        try {
            while (!run.cancel.get()) {
                try {
                    acquired = scanLock.tryLock(50L, TimeUnit.MILLISECONDS);
                    if (acquired) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    run.cancel.set(true);
                    break;
                }
            }
            FolderSummary summary;
            if (acquired) {
                summary = executor.execute(
                        run.config,
                        batch -> deliver(run, batch),
                        progress -> {
                            synchronized (run.stateLock) {
                                run.progress = progress;
                            }
                        },
                        run.cancel::get);
            } else {
                summary = new FolderSummary(0L, 0L, 0L, 0L, 0.0, true);
            }
            synchronized (run.stateLock) {
                run.summary = summary;
            }
        } catch (Throwable t) {
            synchronized (run.stateLock) {
                run.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        } finally {
            if (acquired) {
                scanLock.unlock();
            }
            putSentinel(run);
        }
    }

    private static void deliver(Run run, List<DirectoryDelta> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        while (!run.superseded.get()) {
            try {
                if (run.queue.offer(batch, 50L, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void putSentinel(Run run) {
        while (true) {
            try {
                if (run.queue.offer(SENTINEL, 50L, TimeUnit.MILLISECONDS)) {
                    return;
                }
                // If queue is full and superseded, clear one slot so sentinel can always be delivered.
                if (run.superseded.get()) {
                    run.queue.poll();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void collect(Run run) {
        while (true) {
            List<DirectoryDelta> batch;
            try {
                batch = run.queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (batch == SENTINEL) {
                break;
            }
            if (!run.superseded.get()) {
                try {
                    run.store.addBatch(batch);
                } catch (Throwable t) {
                    synchronized (run.stateLock) {
                        run.error = "Result collector: " + t.getClass().getSimpleName() + ": " + t.getMessage();
                    }
                    run.cancel.set(true);
                    run.superseded.set(true);
                }
            }
        }
        try {
            boolean complete;
            synchronized (run.stateLock) {
                complete = run.error == null
                        && run.summary != null
                        && !run.summary.isCancelled()
                        && run.summary.getErrors() == 0L;
            }
            run.store.finish(complete);
        } catch (Throwable t) {
            synchronized (run.stateLock) {
                run.error = "Result finalization: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }
        synchronized (run.stateLock) {
            run.finished = true;
        }
    }
}
