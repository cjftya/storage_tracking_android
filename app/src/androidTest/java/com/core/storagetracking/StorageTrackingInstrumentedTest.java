package com.core.storagetracking;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.core.storagetracking.scan.folder.FolderConfig;
import com.core.storagetracking.scan.folder.FolderResultStore;
import com.core.storagetracking.scan.folder.FolderScanner;
import com.core.storagetracking.scan.folder.FolderSummary;
import com.core.storagetracking.storage.StorageDiscovery;
import com.core.storagetracking.storage.StorageVolumeProvider;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class StorageTrackingInstrumentedTest {

    @Test
    public void useAppContextAndVerifyVolumeDiscoveryAndScan() throws Exception {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.core.storagetracking", appContext.getPackageName());

        StorageDiscovery discovery = StorageVolumeProvider.discoverVolumes(appContext);
        assertNotNull(discovery);
        assertTrue(discovery.getMandatoryExclusions().contains("/proc"));

        File testDir = new File(appContext.getCacheDir(), "inst_scan_test");
        if (!testDir.exists()) {
            assertTrue(testDir.mkdirs());
        }
        File sampleFile = new File(testDir, "sample.bin");
        try (FileOutputStream fos = new FileOutputStream(sampleFile)) {
            fos.write(new byte[256]);
        }

        FolderConfig cfg = new FolderConfig(Collections.singletonList(testDir.getAbsolutePath()));
        FolderResultStore store = new FolderResultStore(cfg.getRoots());
        FolderSummary summary = FolderScanner.scan(cfg, store::addBatch, p -> {}, () -> false);
        store.finish(true);

        assertEquals(256L, summary.getBytesScanned());
        assertEquals(1, store.getFolderCount());

        // Cleanup temporary test fixtures
        sampleFile.delete();
        testDir.delete();
    }
}
