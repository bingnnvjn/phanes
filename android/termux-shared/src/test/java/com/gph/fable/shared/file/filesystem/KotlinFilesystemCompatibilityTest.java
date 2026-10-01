package com.gph.fable.shared.file.filesystem;

import junit.framework.TestCase;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class KotlinFilesystemCompatibilityTest extends TestCase {

	public void testJavaCallersKeepStaticFilesystemUtilityApis() {
		Set<FilePermission> permissions = FilePermissions.fromString("rwxr-x---");

		assertEquals("rwxr-x---", FilePermissions.toString(permissions));
		assertEquals("regular,directory,symlink",
			FileTypes.convertFileTypeFlagsToNamesString(FileTypes.FILE_TYPE_NORMAL_FLAGS));
		assertEquals(FileType.NO_EXIST, FileTypes.getFileType(null, false));
		assertEquals(90_000L, FileTime.from(90, TimeUnit.SECONDS).toMillis());
	}

	public void testInvalidPermissionModeIsRejected() {
		try {
			FilePermissions.fromString("rwxr-x");
			fail("Expected invalid permission mode to be rejected");
		} catch (IllegalArgumentException expected) {
			assertEquals("Invalid mode", expected.getMessage());
		}
	}

	public void testNullPathKeepsCheckedIOExceptionContract() {
		try {
			FileAttributes.get(null, true);
			fail("Expected null path to be rejected");
		} catch (IOException expected) {
			assertEquals("The path is null or empty", expected.getMessage());
		}
	}
}
