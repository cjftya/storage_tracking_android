package com.core.storagetracking;

import com.core.storagetracking.report.MarkdownReportGenerator;
import com.core.storagetracking.scan.common.PathPolicy;
import com.core.storagetracking.scan.file.FileMatch;
import com.core.storagetracking.scan.file.FileResultStore;
import com.core.storagetracking.scan.folder.DirectoryDelta;
import com.core.storagetracking.scan.folder.FolderResultStore;
import com.core.storagetracking.viewmodel.ScannerViewModel;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MarkdownReportGeneratorTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testFolderReportGenerationInKoreanAndFocusedScope() throws Exception {
        File root = tempFolder.newFolder("storage");
        String rootKey = PathPolicy.pathKey(root.getAbsolutePath());
        String mediaKey = PathPolicy.pathKey(new File(root, "Movies").getAbsolutePath());
        String clipKey = PathPolicy.pathKey(new File(new File(root, "Movies"), "Clips").getAbsolutePath());
        String docsKey = PathPolicy.pathKey(new File(root, "Docs").getAbsolutePath());

        FolderResultStore store = new FolderResultStore(Collections.singletonList(rootKey));
        store.addBatch(Arrays.asList(
                new DirectoryDelta(mediaKey, 800L),
                new DirectoryDelta(clipKey, 200L),
                new DirectoryDelta(docsKey, 500L)));
        store.finish(true);

        ScannerViewModel.StatusUiInfo status = new ScannerViewModel.StatusUiInfo(
                ScannerViewModel.StatusKind.COMPLETED,
                true,
                3L,
                4L,
                4L,
                1500L,
                0L,
                0L,
                1.2,
                "");

        String fullKo = MarkdownReportGenerator.generateFolderReport(
                store, rootKey, null, Collections.singletonList(docsKey), status, Locale.KOREAN);
        assertTrue(fullKo.contains("# Storage Scanner 분석 보고서"));
        assertTrue(fullKo.contains("## 상위 용량 폴더"));
        assertTrue(fullKo.contains("## 디렉터리 트리 요약"));
        assertTrue(fullKo.contains("1,500 B"));
        assertTrue(fullKo.toLowerCase(Locale.ROOT).contains("movies"));
        assertTrue(fullKo.contains("### 제외된 경로"));

        String focusedEn = MarkdownReportGenerator.generateFolderReport(
                store, rootKey, mediaKey, Collections.emptyList(), status, Locale.US);
        assertTrue(focusedEn.contains("# Storage Scanner Analysis Report"));
        assertTrue(focusedEn.contains("Report scope"));
        assertTrue(focusedEn.contains("1,000 B"));
        assertTrue(focusedEn.toLowerCase(Locale.ROOT).contains("clips"));
        assertFalse(focusedEn.contains(docsKey));
    }

    @Test
    public void testFileReportGeneration() throws Exception {
        File root = tempFolder.newFolder("files");
        String rootKey = PathPolicy.pathKey(root.getAbsolutePath());
        String moviePath = PathPolicy.pathKey(new File(root, "video|hd.mp4").getAbsolutePath());

        FileResultStore store = new FileResultStore(Collections.singletonList(rootKey));
        store.addBatch(Collections.singletonList(new FileMatch(moviePath, 2_097_152L)));

        ScannerViewModel.StatusUiInfo status = new ScannerViewModel.StatusUiInfo(
                ScannerViewModel.StatusKind.COMPLETED,
                false,
                10L,
                1L,
                0L,
                0L,
                1L,
                0L,
                0.5,
                "");

        String md = MarkdownReportGenerator.generateFileReport(
                store, rootKey, null, "1", Collections.emptyList(), status, Locale.KOREAN);
        assertTrue(md.contains("대용량 파일 스캔"));
        assertTrue(md.contains("1 MiB"));
        assertTrue(md.toLowerCase(Locale.ROOT).contains("video\\|hd.mp4"));
        assertTrue(md.contains("2,097,152"));
    }
}
