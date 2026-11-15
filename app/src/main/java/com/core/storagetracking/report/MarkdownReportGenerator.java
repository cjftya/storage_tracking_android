package com.core.storagetracking.report;

import com.core.storagetracking.scan.common.PathPolicy;
import com.core.storagetracking.scan.file.FileResultNode;
import com.core.storagetracking.scan.file.FileResultStore;
import com.core.storagetracking.scan.folder.FolderNode;
import com.core.storagetracking.scan.folder.FolderResultStore;
import com.core.storagetracking.util.SizeFormatter;
import com.core.storagetracking.viewmodel.ScannerViewModel;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Generates structured Markdown (.md) storage analysis reports from either
 * FolderResultStore or FileResultStore without modifying any scanned files.
 */
public final class MarkdownReportGenerator {

    private static final int TOP_LIMIT = 25;
    private static final int MAX_TREE_DEPTH = 6;
    private static final int MAX_TREE_LINES = 1500;
    private static final int PAGE_FETCH_SIZE = 500;

    private MarkdownReportGenerator() {
    }

    public static String suggestFileName(String focusPath) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        if (focusPath != null && !focusPath.trim().isEmpty()) {
            String base = PathPolicy.baseName(PathPolicy.pathKey(focusPath));
            String safeBase = base.replaceAll("[^A-Za-z0-9._-]", "_");
            if (!safeBase.isEmpty()) {
                return "storage_report_" + safeBase + "_" + timestamp + ".md";
            }
        }
        return "storage_report_" + timestamp + ".md";
    }

    public static String generateFolderReport(
            FolderResultStore store,
            String targetFolder,
            String focusFolderPath,
            List<String> excludedPaths,
            ScannerViewModel.StatusUiInfo statusInfo,
            Locale locale) {
        boolean ko = isKorean(locale);
        StringBuilder sb = new StringBuilder(4096);
        String scopePath = focusFolderPath != null && !focusFolderPath.trim().isEmpty()
                ? PathPolicy.pathKey(focusFolderPath)
                : (targetFolder != null ? PathPolicy.pathKey(targetFolder) : "");
        FolderNode rootNode = !scopePath.isEmpty() ? store.node(scopePath) : null;
        long scopeTotalBytes = rootNode != null
                ? rootNode.getSizeBytes()
                : (statusInfo != null ? statusInfo.getBytesScanned() : 0L);

        appendHeader(sb, ko, true, targetFolder, scopePath, excludedPaths, statusInfo, scopeTotalBytes, null);

        if (rootNode == null) {
            sb.append(ko ? "\n> 선택한 경로에 대한 폴더 스캔 결과가 없습니다.\n"
                    : "\n> No folder scan results available for the selected scope.\n");
            return sb.toString();
        }

        // Top largest subfolders under scopePath
        List<FolderNode> allFolders = new ArrayList<>();
        collectFoldersBreadthBounded(store, rootNode.getPath(), allFolders, 3000);
        allFolders.sort((a, b) -> {
            int cmp = Long.compare(b.getSizeBytes(), a.getSizeBytes());
            return cmp != 0 ? cmp : a.getPath().compareTo(b.getPath());
        });

        sb.append('\n').append(ko ? "## 상위 용량 폴더" : "## Top Folders").append("\n\n");
        sb.append(ko
                ? "| # | 경로 | 크기 | 바이트 | 비중 |\n| ---: | --- | ---: | ---: | ---: |\n"
                : "| # | Path | Size | Bytes | Share |\n| ---: | --- | ---: | ---: | ---: |\n");
        int shown = 0;
        for (FolderNode node : allFolders) {
            if (node.getPath().equals(rootNode.getPath())) {
                continue;
            }
            shown++;
            if (shown > TOP_LIMIT) {
                break;
            }
            String share = formatShare(node.getSizeBytes(), scopeTotalBytes);
            sb.append("| ").append(shown)
                    .append(" | `").append(escapeInlineCode(node.getPath())).append('`')
                    .append(" | ").append(SizeFormatter.formatDisplaySize(node.getSizeBytes()))
                    .append(" | ").append(SizeFormatter.formatBytes(node.getSizeBytes()))
                    .append(" | ").append(share)
                    .append(" |\n");
        }
        if (shown == 0) {
            sb.append("| - | `").append(escapeInlineCode(rootNode.getPath())).append("` | ")
                    .append(SizeFormatter.formatDisplaySize(rootNode.getSizeBytes())).append(" | ")
                    .append(SizeFormatter.formatBytes(rootNode.getSizeBytes())).append(" | 100.0% |\n");
        }

        // Tree breakdown
        sb.append('\n').append(ko ? "## 디렉터리 트리 요약" : "## Directory Tree Breakdown").append("\n\n");
        int[] lineCount = new int[]{0};
        boolean[] truncated = new boolean[]{false};
        appendFolderTree(sb, store, rootNode, 0, scopeTotalBytes, lineCount, truncated);
        if (truncated[0]) {
            sb.append('\n').append(ko
                    ? "> 참고: 보고서 크기를 유지하기 위해 최대 깊이(" + MAX_TREE_DEPTH + ") 또는 최대 항목(" + MAX_TREE_LINES + ") 이후 하위 항목이 생략되었습니다.\n"
                    : "> Note: Deeper or remaining branches were truncated at max depth (" + MAX_TREE_DEPTH + ") or max entries (" + MAX_TREE_LINES + ").\n");
        }

        return sb.toString();
    }

    public static String generateFileReport(
            FileResultStore store,
            String targetFolder,
            String focusFolderPath,
            String minSizeMibText,
            List<String> excludedPaths,
            ScannerViewModel.StatusUiInfo statusInfo,
            Locale locale) {
        boolean ko = isKorean(locale);
        StringBuilder sb = new StringBuilder(4096);
        String scopePath = focusFolderPath != null && !focusFolderPath.trim().isEmpty()
                ? PathPolicy.pathKey(focusFolderPath)
                : (targetFolder != null ? PathPolicy.pathKey(targetFolder) : "");
        FileResultNode rootNode = !scopePath.isEmpty() ? store.node(scopePath) : null;
        long scopeTotalBytes = rootNode != null ? rootNode.getSizeBytes() : 0L;

        appendHeader(sb, ko, false, targetFolder, scopePath, excludedPaths, statusInfo, scopeTotalBytes, minSizeMibText);

        if (rootNode == null) {
            sb.append(ko ? "\n> 선택한 경로에서 조건에 일치하는 대용량 파일이 없습니다.\n"
                    : "\n> No matching large files were found for the selected scope.\n");
            return sb.toString();
        }

        List<FileResultNode> matchedFiles = new ArrayList<>();
        collectMatchedFiles(store, rootNode, matchedFiles, 5000);
        matchedFiles.sort((a, b) -> {
            int cmp = Long.compare(b.getSizeBytes(), a.getSizeBytes());
            return cmp != 0 ? cmp : a.getPath().compareTo(b.getPath());
        });

        sb.append('\n').append(ko ? "## 일치한 대용량 파일 (크기 순)" : "## Matched Large Files (by Size)").append("\n\n");
        sb.append(ko
                ? "| # | 파일명 | 경로 | 크기 | 바이트 | 비중 |\n| ---: | --- | --- | ---: | ---: | ---: |\n"
                : "| # | Name | Path | Size | Bytes | Share |\n| ---: | --- | --- | ---: | ---: | ---: |\n");
        int count = Math.min(matchedFiles.size(), TOP_LIMIT);
        for (int i = 0; i < count; i++) {
            FileResultNode file = matchedFiles.get(i);
            sb.append("| ").append(i + 1)
                    .append(" | ").append(escapeTableCell(file.getName()))
                    .append(" | `").append(escapeInlineCode(file.getPath())).append('`')
                    .append(" | ").append(SizeFormatter.formatDisplaySize(file.getSizeBytes()))
                    .append(" | ").append(SizeFormatter.formatBytes(file.getSizeBytes()))
                    .append(" | ").append(formatShare(file.getSizeBytes(), scopeTotalBytes))
                    .append(" |\n");
        }
        if (matchedFiles.isEmpty()) {
            sb.append(ko ? "| - | (일치 파일 없음) | - | 0 B | 0 | 0.0% |\n"
                    : "| - | (No matched files) | - | 0 B | 0 | 0.0% |\n");
        }

        sb.append('\n').append(ko ? "## 일치 파일 트리" : "## Matched File Tree").append("\n\n");
        int[] lineCount = new int[]{0};
        boolean[] truncated = new boolean[]{false};
        appendFileTree(sb, store, rootNode, 0, scopeTotalBytes, lineCount, truncated);
        if (truncated[0]) {
            sb.append('\n').append(ko
                    ? "> 참고: 보고서 크기를 유지하기 위해 최대 깊이(" + MAX_TREE_DEPTH + ") 또는 최대 항목(" + MAX_TREE_LINES + ") 이후 항목이 생략되었습니다.\n"
                    : "> Note: Additional entries were truncated at max depth (" + MAX_TREE_DEPTH + ") or max entries (" + MAX_TREE_LINES + ").\n");
        }

        return sb.toString();
    }

    private static void appendHeader(
            StringBuilder sb,
            boolean ko,
            boolean folderMode,
            String targetFolder,
            String scopePath,
            List<String> excludedPaths,
            ScannerViewModel.StatusUiInfo statusInfo,
            long scopeTotalBytes,
            String minSizeMibText) {
        String nowText = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date());
        sb.append(ko ? "# Storage Scanner 분석 보고서\n\n" : "# Storage Scanner Analysis Report\n\n");
        sb.append(ko ? "## 요약 정보\n\n" : "## Summary\n\n");
        sb.append(ko ? "| 항목 | 내용 |\n| --- | --- |\n" : "| Field | Value |\n| --- | --- |\n");
        sb.append("| ").append(ko ? "생성 시각" : "Generated").append(" | ").append(nowText).append(" |\n");
        sb.append("| ").append(ko ? "스캔 모드" : "Scan mode").append(" | ")
                .append(folderMode
                        ? (ko ? "폴더 스캔 (논리적 크기)" : "Folder scan (logical size)")
                        : (ko ? "대용량 파일 스캔 (일치 파일 합계)" : "Large file scan (matched size)"))
                .append(" |\n");
        if (!folderMode && minSizeMibText != null && !minSizeMibText.trim().isEmpty()) {
            sb.append("| ").append(ko ? "최소 파일 크기" : "Min file size").append(" | ")
                    .append(escapeTableCell(minSizeMibText.trim())).append(" MiB |\n");
        }
        sb.append("| ").append(ko ? "스캔 대상 폴더" : "Target folder").append(" | `")
                .append(escapeInlineCode(targetFolder != null ? targetFolder : "-")).append("` |\n");
        if (!scopePath.isEmpty() && targetFolder != null && !scopePath.equals(PathPolicy.pathKey(targetFolder))) {
            sb.append("| ").append(ko ? "보고서 기준 폴더" : "Report scope").append(" | `")
                    .append(escapeInlineCode(scopePath)).append("` |\n");
        }
        sb.append("| ").append(ko ? "보고서 범위 합계" : "Scope total").append(" | **")
                .append(SizeFormatter.formatDisplaySize(scopeTotalBytes)).append("** (")
                .append(SizeFormatter.formatBytes(scopeTotalBytes)).append(" B) |\n");

        if (statusInfo != null) {
            sb.append("| ").append(ko ? "스캔 상태" : "Scan status").append(" | ")
                    .append(formatStatusKind(statusInfo.getKind(), ko)).append(" |\n");
            sb.append("| ").append(ko ? "검사한 파일 / 폴더" : "Files / Folders scanned").append(" | ")
                    .append(String.format(Locale.US, "%,d / %,d",
                            statusInfo.getFilesScanned(), statusInfo.getDirectoriesScanned()))
                    .append(" |\n");
            if (!folderMode) {
                sb.append("| ").append(ko ? "일치 파일 수" : "Matched files").append(" | ")
                        .append(String.format(Locale.US, "%,d", statusInfo.getMatches())).append(" |\n");
            }
            sb.append("| ").append(ko ? "접근 오류 / 소요 시간" : "Errors / Elapsed").append(" | ")
                    .append(String.format(Locale.US, "%,d / %.1fs",
                            statusInfo.getErrors(), statusInfo.getElapsedSeconds()))
                    .append(" |\n");
        }

        if (excludedPaths != null && !excludedPaths.isEmpty()) {
            sb.append('\n').append(ko ? "### 제외된 경로" : "### Excluded Paths").append("\n\n");
            for (String ex : excludedPaths) {
                sb.append("- `").append(escapeInlineCode(ex)).append("`\n");
            }
        }
    }

    private static void collectFoldersBreadthBounded(
            FolderResultStore store,
            String rootPath,
            List<FolderNode> out,
            int maxCollect) {
        List<String> queue = new ArrayList<>();
        queue.add(rootPath);
        int idx = 0;
        while (idx < queue.size() && out.size() < maxCollect) {
            String path = queue.get(idx++);
            FolderNode node = store.node(path);
            if (node != null) {
                out.add(node);
            }
            int offset = 0;
            while (out.size() + queue.size() < maxCollect * 2) {
                FolderResultStore.Page page = store.childrenPage(path, offset, PAGE_FETCH_SIZE);
                List<FolderNode> children = page.getNodes();
                if (children.isEmpty()) {
                    break;
                }
                for (FolderNode child : children) {
                    queue.add(child.getPath());
                }
                offset += children.size();
                if (offset >= page.getTotalCount()) {
                    break;
                }
            }
        }
    }

    private static void collectMatchedFiles(
            FileResultStore store,
            FileResultNode startNode,
            List<FileResultNode> out,
            int maxCollect) {
        if (!startNode.isDirectory()) {
            out.add(startNode);
            return;
        }
        List<String> queue = new ArrayList<>();
        queue.add(startNode.getPath());
        int idx = 0;
        while (idx < queue.size() && out.size() < maxCollect) {
            String dirPath = queue.get(idx++);
            int offset = 0;
            while (out.size() < maxCollect) {
                FileResultStore.Page page = store.childrenPage(dirPath, offset, PAGE_FETCH_SIZE);
                List<FileResultNode> children = page.getNodes();
                if (children.isEmpty()) {
                    break;
                }
                for (FileResultNode child : children) {
                    if (child.isDirectory()) {
                        queue.add(child.getPath());
                    } else {
                        out.add(child);
                        if (out.size() >= maxCollect) {
                            break;
                        }
                    }
                }
                offset += children.size();
                if (offset >= page.getTotalCount()) {
                    break;
                }
            }
        }
    }

    private static void appendFolderTree(
            StringBuilder sb,
            FolderResultStore store,
            FolderNode node,
            int depth,
            long scopeTotalBytes,
            int[] lineCount,
            boolean[] truncated) {
        if (lineCount[0] >= MAX_TREE_LINES) {
            truncated[0] = true;
            return;
        }
        lineCount[0]++;
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        String displayName = depth == 0 ? node.getPath() : node.getName();
        sb.append("- **").append(escapeInlineCode(displayName)).append("/** — ")
                .append(SizeFormatter.formatDisplaySize(node.getSizeBytes()))
                .append(" (").append(SizeFormatter.formatBytes(node.getSizeBytes())).append(" B, ")
                .append(formatShare(node.getSizeBytes(), scopeTotalBytes)).append(")\n");

        if (depth >= MAX_TREE_DEPTH) {
            if (store.childCount(node.getPath()) > 0) {
                truncated[0] = true;
            }
            return;
        }
        int offset = 0;
        while (lineCount[0] < MAX_TREE_LINES) {
            FolderResultStore.Page page = store.childrenPage(node.getPath(), offset, PAGE_FETCH_SIZE);
            List<FolderNode> children = page.getNodes();
            if (children.isEmpty()) {
                break;
            }
            for (FolderNode child : children) {
                if (lineCount[0] >= MAX_TREE_LINES) {
                    truncated[0] = true;
                    return;
                }
                appendFolderTree(sb, store, child, depth + 1, scopeTotalBytes, lineCount, truncated);
            }
            offset += children.size();
            if (offset >= page.getTotalCount()) {
                break;
            }
        }
    }

    private static void appendFileTree(
            StringBuilder sb,
            FileResultStore store,
            FileResultNode node,
            int depth,
            long scopeTotalBytes,
            int[] lineCount,
            boolean[] truncated) {
        if (lineCount[0] >= MAX_TREE_LINES) {
            truncated[0] = true;
            return;
        }
        lineCount[0]++;
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        String displayName = depth == 0 ? node.getPath() : node.getName();
        if (node.isDirectory()) {
            sb.append("- **").append(escapeInlineCode(displayName)).append("/** — ");
        } else {
            sb.append("- `").append(escapeInlineCode(displayName)).append("` — ");
        }
        sb.append(SizeFormatter.formatDisplaySize(node.getSizeBytes()))
                .append(" (").append(SizeFormatter.formatBytes(node.getSizeBytes())).append(" B, ")
                .append(formatShare(node.getSizeBytes(), scopeTotalBytes)).append(")\n");

        if (!node.isDirectory()) {
            return;
        }
        if (depth >= MAX_TREE_DEPTH) {
            if (store.childCount(node.getPath()) > 0) {
                truncated[0] = true;
            }
            return;
        }
        int offset = 0;
        while (lineCount[0] < MAX_TREE_LINES) {
            FileResultStore.Page page = store.childrenPage(node.getPath(), offset, PAGE_FETCH_SIZE);
            List<FileResultNode> children = page.getNodes();
            if (children.isEmpty()) {
                break;
            }
            for (FileResultNode child : children) {
                if (lineCount[0] >= MAX_TREE_LINES) {
                    truncated[0] = true;
                    return;
                }
                appendFileTree(sb, store, child, depth + 1, scopeTotalBytes, lineCount, truncated);
            }
            offset += children.size();
            if (offset >= page.getTotalCount()) {
                break;
            }
        }
    }

    private static String formatShare(long part, long total) {
        if (total <= 0L) {
            return "0.0%";
        }
        double pct = Math.min(100.0, Math.max(0.0, (part * 100.0) / total));
        return String.format(Locale.US, "%.1f%%", pct);
    }

    private static String formatStatusKind(ScannerViewModel.StatusKind kind, boolean ko) {
        if (kind == null) {
            return ko ? "알 수 없음" : "Unknown";
        }
        switch (kind) {
            case COMPLETED:
                return ko ? "완료" : "Completed";
            case COMPLETED_PARTIAL:
                return ko ? "완료 (부분 합계)" : "Completed (partial totals)";
            case CANCELLED:
                return ko ? "취소됨" : "Cancelled";
            case CANCELLED_PARTIAL:
                return ko ? "취소됨 (부분 합계)" : "Cancelled (partial totals)";
            case FAILED:
                return ko ? "실패" : "Failed";
            case FAILED_PARTIAL:
                return ko ? "실패 (부분 합계)" : "Failed (partial totals)";
            case SCANNING:
            case SCANNING_STARTING:
                return ko ? "스캔 중" : "Scanning";
            case CANCELLING:
                return ko ? "취소 중" : "Cancelling";
            default:
                return kind.name();
        }
    }

    private static boolean isKorean(Locale locale) {
        return locale != null && "ko".equalsIgnoreCase(locale.getLanguage());
    }

    private static String escapeInlineCode(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("`", "'").replace("\n", " ").replace("\r", " ");
    }

    private static String escapeTableCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }
}
