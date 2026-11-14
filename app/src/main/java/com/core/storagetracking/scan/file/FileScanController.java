package com.core.storagetracking.scan.file;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Independent worker and bounded-queue collector lifecycle for File Scan mode.
 * Does not reference Android UI classes or FolderScanner classes.
 */
public final class FileScanController {
    private static final List<FileMatch> SENTINEL = Collections.emptyList();

    public interface ScanExecutor {
        FileScanSummary execute(
                FileScanConfig config,
                java.util.function.Consumer<List<FileMatch>> onBatch,
                java.util.function.Consumer<FileScanProgress> onProgress,
                java.util.function.BooleanSupplier shouldCancel);
    }

    public static final class RunSnapshot {
        private final int runId;
        private final FileResultStore store;
        private final FileScanProgress progress;
        private final FileScanSummary summary;
        private final boolean finished;
        private final String error;
        private final double elapsedSeconds;

        public RunSnapshot(
                int runId,
                FileResultStore store,
                FileScanProgress progress,
                FileScanSummary summary,
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

        public FileResultStore getStore() {
            return store;
        }

        public FileScanProgress getProgress() {
            return progress;
        }

        public FileScanSummary getSummary() {
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
        final FileScanConfig config;
        final FileResultStore store;
        final ArrayBlockingQueue<List<FileMatch>> queue = new ArrayBlockingQueue<>(8);
        final AtomicBoolean cancel = new AtomicBoolean(false);
        final AtomicBoolean superseded = new AtomicBoolean(false);
        final Object stateLock = new Object();
        final long startedNanos = System.nanoTime();
        FileScanProgress progress = new FileScanProgress();
        FileScanSummary summary;
        boolean finished;
        String error;

        Run(int runId, FileScanConfig config, FileResultStore store) {
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

    public FileScanController() {
        this(new ReentrantLock(), FileScanner::scan);
    }

    public FileScanController(ReentrantLock scanLock) {
        this(scanLock, FileScanner::scan);
    }

    public FileScanController(ReentrantLock scanLock, ScanExecutor executor) {
        this.scanLock = scanLock != null ? scanLock : new ReentrantLock();
        this.executor = executor != null ? executor : FileScanner::scan;
    }

    public synchronized RunSnapshot start(FileScanConfig config) {
        if (closed) {
            throw new IllegalStateException("Controller is closed");
        }
        if (currentRun != null) {
            currentRun.cancel.set(true);
            currentRun.superseded.set(true);
        }
        nextId++;
        Run run = new Run(nextId, config, new FileResultStore(config.getRoots()));
        currentRun = run;

        Thread collectorThread = new Thread(() -> collect(run), "file-results-" + run.runId);
        collectorThread.setDaemon(true);
        collectorThread.start();

        Thread workerThread = new Thread(() -> work(run), "file-scan-" + run.runId);
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
            FileScanSummary summary;
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
                summary = new FileScanSummary(0L, 0L, 0L, 0L, 0.0, true);
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

    private static void deliver(Run run, List<FileMatch> batch) {
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
            List<FileMatch> batch;
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
        synchronized (run.stateLock) {
            run.finished = true;
        }
    }
}
