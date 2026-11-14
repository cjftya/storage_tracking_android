package com.core.storagetracking.scan.file;

import com.core.storagetracking.scan.common.PathFilter;
import com.core.storagetracking.scan.common.PathPolicy;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Independent single-worker iterative file scanner.
 * Emits FileMatch records for regular files with size >= minSizeBytes.
 * Never reads file contents and never retains unmatched file objects.
 */
public final class FileScanner {

    public interface DirectoryEntryProvider {
        BasicFileAttributes inspectRoot(Path root) throws IOException;
        DirectoryStream<Path> openDirectory(Path dir) throws IOException;
        BasicFileAttributes readAttributes(Path entry) throws IOException;
        boolean isLinkOrReparse(Path entry, BasicFileAttributes attrs);
    }

    private static final DirectoryEntryProvider DEFAULT_PROVIDER = new DirectoryEntryProvider() {
        @Override
        public BasicFileAttributes inspectRoot(Path root) throws IOException {
            return PathPolicy.inspectRoot(root);
        }

        @Override
        public DirectoryStream<Path> openDirectory(Path dir) throws IOException {
            return Files.newDirectoryStream(dir);
        }

        @Override
        public BasicFileAttributes readAttributes(Path entry) throws IOException {
            return Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public boolean isLinkOrReparse(Path entry, BasicFileAttributes attrs) {
            return PathPolicy.isLinkOrReparseDirectory(entry, attrs);
        }
    };

    private FileScanner() {
    }

    public static FileScanSummary scan(
            FileScanConfig config,
            Consumer<List<FileMatch>> onBatch,
            Consumer<FileScanProgress> onProgress,
            BooleanSupplier shouldCancel) {
        return scan(config, onBatch, onProgress, shouldCancel, DEFAULT_PROVIDER);
    }

    public static FileScanSummary scan(
            FileScanConfig config,
            Consumer<List<FileMatch>> onBatch,
            Consumer<FileScanProgress> onProgress,
            BooleanSupplier shouldCancel,
            DirectoryEntryProvider provider) {
        final long startedNanos = System.nanoTime();
        long lastFlushNanos = startedNanos;
        long lastProgressNanos = startedNanos;
        final long flushIntervalNanos = Math.max(1L, (long) (config.getFlushIntervalSeconds() * 1_000_000_000L));
        final long progressIntervalNanos = Math.max(1L, (long) (config.getProgressIntervalSeconds() * 1_000_000_000L));

        long files = 0L;
        long directories = 0L;
        long matched = 0L;
        long errors = 0L;
        String current = "";
        boolean cancelled = false;

        final List<FileMatch> batch = new ArrayList<>(config.getBatchSize());
        final PathFilter excluded = new PathFilter(config.getExcludedPaths());

        final LinkedHashSet<String> normalizedRoots = new LinkedHashSet<>();
        for (String r : config.getRoots()) {
            if (r != null && !r.trim().isEmpty()) {
                normalizedRoots.add(PathPolicy.pathKey(r));
            }
        }
        final List<String> roots = new ArrayList<>(normalizedRoots);

        final Set<String> boundaries = new HashSet<>(roots);
        for (String b : config.getBoundaryPaths()) {
            if (b != null && !b.trim().isEmpty()) {
                boundaries.add(PathPolicy.pathKey(b));
            }
        }

        final Map<String, Set<String>> boundaryNames = new HashMap<>();
        for (String boundary : boundaries) {
            String parent = PathPolicy.parentKey(boundary);
            String base = PathPolicy.baseName(boundary);
            if (parent != null && !base.isEmpty()) {
                boundaryNames.computeIfAbsent(parent, k -> new HashSet<>())
                        .add(PathPolicy.entryPathKey(base));
            }
        }

        final Consumer<FileScanProgress> safeProgress = onProgress != null ? onProgress : p -> {};
        final BooleanSupplier safeCancel = shouldCancel != null ? shouldCancel : () -> false;

        for (String root : roots) {
            if (safeCancel.getAsBoolean()) {
                cancelled = true;
                break;
            }
            if (excluded.excludesKey(root)) {
                continue;
            }
            BasicFileAttributes rootAttrs;
            try {
                rootAttrs = provider.inspectRoot(Paths.get(root));
                if (rootAttrs == null) {
                    continue;
                }
            } catch (IOException | SecurityException | InvalidPathException e) {
                errors++;
                continue;
            }

            if (rootAttrs.isRegularFile()) {
                String parent = PathPolicy.parentKey(root);
                current = parent != null ? parent : root;
                files++;
                long size = Math.max(0L, rootAttrs.size());
                if (size >= config.getMinSizeBytes()) {
                    matched++;
                    batch.add(new FileMatch(root, size));
                }
                long now = System.nanoTime();
                if (!batch.isEmpty() && (batch.size() >= config.getBatchSize()
                        || now - lastFlushNanos >= flushIntervalNanos)) {
                    onBatch.accept(Collections.unmodifiableList(new ArrayList<>(batch)));
                    batch.clear();
                    lastFlushNanos = now;
                }
                if (now - lastProgressNanos >= progressIntervalNanos) {
                    safeProgress.accept(new FileScanProgress(files, directories, matched, errors, current));
                    lastProgressNanos = now;
                }
                continue;
            }

            if (!rootAttrs.isDirectory()) {
                continue;
            }

            ArrayDeque<String> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                if (safeCancel.getAsBoolean()) {
                    cancelled = true;
                    break;
                }
                current = stack.pop();
                Set<String> mountedChildren = boundaryNames.getOrDefault(current, Collections.emptySet());
                try (DirectoryStream<Path> entries = provider.openDirectory(Paths.get(current))) {
                    directories++;
                    int index = 0;
                    for (Path entry : entries) {
                        index++;
                        try {
                            BasicFileAttributes attrs = provider.readAttributes(entry);
                            if (provider.isLinkOrReparse(entry, attrs)) {
                                // Never follow symbolic links or junctions.
                            } else if (attrs.isDirectory()) {
                                String childKey = PathPolicy.pathKey(entry.toString());
                                if (!boundaries.contains(childKey) && !excluded.excludesKey(childKey)) {
                                    stack.push(childKey);
                                }
                            } else if (attrs.isRegularFile()) {
                                Path fileName = entry.getFileName();
                                String fileNameKey = fileName != null
                                        ? PathPolicy.entryPathKey(fileName.toString())
                                        : "";
                                if (mountedChildren.isEmpty() || !mountedChildren.contains(fileNameKey)) {
                                    files++;
                                    long size = Math.max(0L, attrs.size());
                                    if (size >= config.getMinSizeBytes()) {
                                        String entryKey = PathPolicy.pathKey(entry.toString());
                                        if (!excluded.excludesFileEntry(entryKey)) {
                                            matched++;
                                            batch.add(new FileMatch(entryKey, size));
                                            if (batch.size() >= config.getBatchSize()) {
                                                long now = System.nanoTime();
                                                onBatch.accept(Collections.unmodifiableList(new ArrayList<>(batch)));
                                                batch.clear();
                                                lastFlushNanos = now;
                                                if (now - lastProgressNanos >= progressIntervalNanos) {
                                                    safeProgress.accept(new FileScanProgress(
                                                            files, directories, matched, errors, current));
                                                    lastProgressNanos = now;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (IOException | SecurityException | DirectoryIteratorException | InvalidPathException e) {
                            errors++;
                        }

                        if (index % config.getCancellationCheckInterval() == 0) {
                            long now = System.nanoTime();
                            if (!batch.isEmpty() && (batch.size() >= config.getBatchSize()
                                    || now - lastFlushNanos >= flushIntervalNanos)) {
                                onBatch.accept(Collections.unmodifiableList(new ArrayList<>(batch)));
                                batch.clear();
                                lastFlushNanos = now;
                            }
                            if (now - lastProgressNanos >= progressIntervalNanos) {
                                safeProgress.accept(new FileScanProgress(files, directories, matched, errors, current));
                                lastProgressNanos = now;
                            }
                            if (safeCancel.getAsBoolean()) {
                                cancelled = true;
                                break;
                            }
                        }
                    }
                } catch (IOException | SecurityException | DirectoryIteratorException | InvalidPathException e) {
                    errors++;
                }

                long now = System.nanoTime();
                if (!batch.isEmpty() && (batch.size() >= config.getBatchSize()
                        || now - lastFlushNanos >= flushIntervalNanos)) {
                    onBatch.accept(Collections.unmodifiableList(new ArrayList<>(batch)));
                    batch.clear();
                    lastFlushNanos = now;
                }
                if (now - lastProgressNanos >= progressIntervalNanos) {
                    safeProgress.accept(new FileScanProgress(files, directories, matched, errors, current));
                    lastProgressNanos = now;
                }
                if (cancelled) {
                    break;
                }
            }
            if (cancelled) {
                break;
            }
        }

        if (!batch.isEmpty()) {
            onBatch.accept(Collections.unmodifiableList(new ArrayList<>(batch)));
            batch.clear();
        }
        safeProgress.accept(new FileScanProgress(files, directories, matched, errors, current));
        double elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
        return new FileScanSummary(files, directories, matched, errors, elapsedSeconds, cancelled);
    }
}
