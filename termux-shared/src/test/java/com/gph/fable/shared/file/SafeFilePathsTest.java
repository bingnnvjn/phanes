package com.gph.fable.shared.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

public class SafeFilePathsTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void canonicalContainmentRejectsSiblingPrefixAndTraversal() throws Exception {
        File root = temporaryFolder.newFolder("home");
        File child = new File(root, "docs/file.txt");
        File siblingPrefix = temporaryFolder.newFolder("home-escape");

        assertTrue(SafeFilePaths.isWithin(root, root, true));
        assertFalse(SafeFilePaths.isWithin(root, root, false));
        assertTrue(SafeFilePaths.isWithin(root, child, false));
        assertFalse(SafeFilePaths.isWithin(root, siblingPrefix, true));
        assertFalse(SafeFilePaths.isWithin(root, new File(root, "../outside.txt"), true));
    }

    @Test
    public void canonicalContainmentRejectsSymlinkEscape() throws Exception {
        File root = temporaryFolder.newFolder("home");
        File outside = temporaryFolder.newFolder("outside");
        File link = new File(root, "linked");
        Files.createSymbolicLink(link.toPath(), outside.toPath());

        assertFalse(SafeFilePaths.isWithin(root, new File(link, "secret.txt"), true));
    }

    @Test
    public void resolveLeafAcceptsOnlyOneSafeFileName() throws Exception {
        File root = temporaryFolder.newFolder("received");

        File target = SafeFilePaths.resolveLeaf(root, "report.txt");
        assertNotNull(target);
        assertEquals(new File(root, "report.txt").getCanonicalFile(), target);

        assertNull(SafeFilePaths.resolveLeaf(root, "../outside.txt"));
        assertNull(SafeFilePaths.resolveLeaf(root, "/absolute.txt"));
        assertNull(SafeFilePaths.resolveLeaf(root, "nested/file.txt"));
        assertNull(SafeFilePaths.resolveLeaf(root, "nested\\file.txt"));
        assertNull(SafeFilePaths.resolveLeaf(root, "."));
        assertNull(SafeFilePaths.resolveLeaf(root, ".."));
        assertNull(SafeFilePaths.resolveLeaf(root, " "));
    }

    @Test
    public void resolveLeafRejectsExistingSymlinkOutsideRoot() throws Exception {
        File root = temporaryFolder.newFolder("received");
        File outside = temporaryFolder.newFile("outside.txt");
        File link = new File(root, "report.txt");
        Files.createSymbolicLink(link.toPath(), outside.toPath());

        assertNull(SafeFilePaths.resolveLeaf(root, "report.txt"));
    }

    @Test
    public void createNewLeafNeverOverwritesAnExistingFile() throws Exception {
        File root = temporaryFolder.newFolder("received");
        File existing = new File(root, "report.txt");
        Files.write(existing.toPath(), "original".getBytes());

        assertNull(SafeFilePaths.createNewLeaf(root, "report.txt"));
        assertEquals("original", new String(Files.readAllBytes(existing.toPath())));

        File created = SafeFilePaths.createNewLeaf(root, "new.txt");
        assertNotNull(created);
        assertTrue(created.isFile());
    }
}
