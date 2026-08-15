package com.atakmap.android.plowtak.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnoozeStoreTest {

    @Test
    fun `bump extends from now by step minutes`() {
        val store = SnoozeStore()
        val now = 1_000_000L
        val due = store.bump("task:1", 15, now)
        assertEquals(now + 15 * 60_000L, due)
        assertEquals(due, store.dueBy("task:1"))
    }

    @Test
    fun `mergeRemote keeps max dueBy`() {
        val store = SnoozeStore()
        store.bump("a", 15, 1_000_000L)
        val changed = store.mergeRemote(
            mapOf(
                "a" to 1_000_000L + 5 * 60_000L, // shorter — ignored
                "b" to 2_000_000L
            )
        )
        assertTrue(changed)
        assertEquals(1_000_000L + 15 * 60_000L, store.dueBy("a"))
        assertEquals(2_000_000L, store.dueBy("b"))
        assertFalse(store.mergeRemote(mapOf("b" to 1_500_000L)))
    }

    @Test
    fun `pruneExpired drops past due entries`() {
        val store = SnoozeStore()
        store.mergeRemote(mapOf("old" to 500L, "future" to 2_000L))
        store.pruneExpired(1_000L)
        assertNull(store.dueBy("old"))
        assertEquals(2_000L, store.dueBy("future"))
    }

    @Test
    fun `persists and reloads`() {
        val mem = InMemoryPersistence()
        val a = SnoozeStore(mem)
        a.bump("x", 10, 1_000L)
        val b = SnoozeStore(mem)
        assertEquals(a.dueBy("x"), b.dueBy("x"))
    }

    @Test
    fun `listener fires on bump and merge`() {
        val store = SnoozeStore()
        var hits = 0
        store.addListener { hits++ }
        store.bump("a", 15, 1_000L)
        assertEquals(1, hits)
        store.mergeRemote(mapOf("b" to 2_000L))
        assertEquals(2, hits)
        assertFalse(store.mergeRemote(mapOf("b" to 1_500L)))
        assertEquals(2, hits)
    }
}
