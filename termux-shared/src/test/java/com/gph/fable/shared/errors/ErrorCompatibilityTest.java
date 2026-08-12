package com.gph.fable.shared.errors;

import junit.framework.TestCase;

import java.util.Collections;
import java.util.List;

public class ErrorCompatibilityTest extends TestCase {

	public void testJavaCallerCanPassAnImmutableThrowableListToConstructor() {
		Throwable cause = new IllegalStateException("cause");
		Error error = new Error("migration", 7, "failed", Collections.singletonList(cause));

		assertEquals(7, error.getCode());
		assertEquals(Collections.singletonList(cause), error.getThrowablesList());
	}

	public void testJavaCallerCanPassAnImmutableThrowableListWhenUpdatingFailure() {
		Throwable cause = new IllegalArgumentException("cause");
		Error error = new Error();

		assertTrue(error.setStateFailed(7, "failed", Collections.singletonList(cause)));
		List<Throwable> causes = error.getThrowablesList();

		assertEquals(Collections.singletonList(cause), causes);
	}
}
