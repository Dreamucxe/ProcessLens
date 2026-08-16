package com.processlens.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Small assertion helpers.
 *
 * Plain JUnit rather than a fluent assertion library, so the unit tests need nothing
 * beyond JUnit itself and run on any JDK. These four wrappers exist only because
 * `assertTrue(haystack.contains(needle))` fails with "expected true, got false" and
 * tells you nothing about which string was missing — these print the text.
 */

fun assertContains(haystack: String, needle: String) {
    assertTrue(
        "expected to contain:\n  <$needle>\nbut the text was:\n$haystack",
        haystack.contains(needle),
    )
}

fun assertDoesNotContain(haystack: String, needle: String) {
    assertTrue(
        "expected NOT to contain:\n  <$needle>\nbut the text was:\n$haystack",
        !haystack.contains(needle),
    )
}

fun assertEndsWith(value: String, suffix: String) {
    assertTrue("expected <$value> to end with <$suffix>", value.endsWith(suffix))
}

/** Asserts an ordered list of names, which is what most sorting tests want to say. */
fun assertOrder(expected: List<String>, actual: List<String>) {
    assertEquals(expected.joinToString(", "), actual.joinToString(", "))
}
