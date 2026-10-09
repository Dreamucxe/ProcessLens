package com.processlens.core.system

import com.processlens.core.common.AccessLevel
import com.processlens.core.common.DataSource
import com.processlens.core.common.Observed
import com.processlens.core.common.RestrictionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for the negative cache (requirement 1 of the issue #1 spec: a denied read is
 * attempted once per session, not once per sample).
 *
 * Plain JVM: [RestrictionCache] is deliberately free of `android.*`, so the exact
 * memoisation that decides whether a screen keeps saying "Not available" is checked
 * here rather than on a device.
 */
class RestrictionCacheTest {

    private fun restricted(reason: RestrictionReason): Observed.Restricted =
        Observed.Restricted(reason, AccessLevel.SHIZUKU, "denied")

    private fun <T> value(v: T): Observed<T> = Observed.of(v, DataSource.PROC_FS)

    @Test
    fun `a terminal refusal is remembered and the read is not repeated`() {
        val cache = RestrictionCache()
        val calls = AtomicInteger(0)

        val read = {
            calls.incrementAndGet()
            restricted(RestrictionReason.PLATFORM_RESTRICTED)
        }

        val first = cache.attempt("cpu", read)
        val second = cache.attempt("cpu", read)

        assertEquals("the second attempt must not touch the filesystem", 1, calls.get())
        assertSame("the remembered refusal is handed back verbatim", first, second)
        assertTrue(cache.isRefused("cpu"))
    }

    @Test
    fun `every platform reason is terminal`() {
        val terminal = listOf(
            RestrictionReason.PLATFORM_RESTRICTED,
            RestrictionReason.NOT_PRESENT_ON_DEVICE,
            RestrictionReason.NOT_SUPPORTED_ON_API_LEVEL,
            RestrictionReason.REQUIRES_ELEVATED_ACCESS,
        )
        for (reason in terminal) {
            val cache = RestrictionCache()
            cache.attempt("k") { restricted(reason) }
            assertTrue("$reason should be cached", cache.isRefused("k"))
        }
    }

    @Test
    fun `a permission refusal is not remembered because one dialog revokes it`() {
        val cache = RestrictionCache()
        val calls = AtomicInteger(0)

        repeat(2) {
            cache.attempt("usage") {
                calls.incrementAndGet()
                restricted(RestrictionReason.PERMISSION_REQUIRED)
            }
        }

        assertEquals("a permission refusal is re-read every time", 2, calls.get())
        assertFalse(cache.isRefused("usage"))
        assertNull(cache.refusalFor("usage"))
    }

    @Test
    fun `a sampling-disabled refusal is not remembered because the user owns the switch`() {
        val cache = RestrictionCache()
        val calls = AtomicInteger(0)

        repeat(3) {
            cache.attempt("cpu") {
                calls.incrementAndGet()
                restricted(RestrictionReason.SAMPLING_DISABLED)
            }
        }

        assertEquals(3, calls.get())
        assertFalse(cache.isRefused("cpu"))
    }

    @Test
    fun `a failed read is never memoised so one bad sample is not permanent`() {
        val cache = RestrictionCache()
        val calls = AtomicInteger(0)

        repeat(2) {
            cache.attempt<String>("loadavg") {
                calls.incrementAndGet()
                Observed.Failed("empty file", "ParseException")
            }
        }

        assertEquals("a fault is retried, not remembered", 2, calls.get())
        assertFalse(cache.isRefused("loadavg"))
    }

    @Test
    fun `a successful read is never memoised`() {
        val cache = RestrictionCache()
        val calls = AtomicInteger(0)

        repeat(2) {
            cache.attempt("cpu") {
                calls.incrementAndGet()
                value(42)
            }
        }

        assertEquals(2, calls.get())
        assertFalse(cache.isRefused("cpu"))
    }

    @Test
    fun `different metrics are remembered independently`() {
        val cache = RestrictionCache()

        cache.attempt("thermal") { restricted(RestrictionReason.PLATFORM_RESTRICTED) }
        cache.attempt("meminfo") { value(100L) }

        assertEquals(setOf("thermal"), cache.refusedKeys)
    }

    @Test
    fun `clear forgets every refusal so the next read reaches the filesystem again`() {
        val cache = RestrictionCache()
        val calls = AtomicInteger(0)
        val read = {
            calls.incrementAndGet()
            restricted(RestrictionReason.PLATFORM_RESTRICTED)
        }

        cache.attempt("cpu", read)
        assertTrue(cache.isRefused("cpu"))

        cache.clear()

        assertFalse(cache.isRefused("cpu"))
        cache.attempt("cpu", read)
        assertEquals("clearing re-opens the read", 2, calls.get())
    }

    @Test
    fun `shouldAnnounce is true once per key then false forever`() {
        val cache = RestrictionCache()

        assertTrue(cache.shouldAnnounce("cpu"))
        assertFalse(cache.shouldAnnounce("cpu"))
        assertFalse(cache.shouldAnnounce("cpu"))
        assertTrue("a different metric gets its own one line", cache.shouldAnnounce("thermal"))
    }

    @Test
    fun `clear does not reset the announcement budget which is per session`() {
        val cache = RestrictionCache()

        assertTrue(cache.shouldAnnounce("cpu"))
        cache.clear()

        assertFalse("the log budget outlives an access-level change", cache.shouldAnnounce("cpu"))
    }

    @Test
    fun `refusalFor returns the exact Observed that the read produced`() {
        val cache = RestrictionCache()
        val produced = restricted(RestrictionReason.REQUIRES_ELEVATED_ACCESS)

        cache.attempt("pss") { produced }

        assertSame(produced, cache.refusalFor("pss"))
    }
}
