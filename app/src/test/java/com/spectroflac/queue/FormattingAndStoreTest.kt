package com.spectroflac.queue

import com.spectroflac.util.formatEta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FormatEtaTest {
    @Test
    fun `reads the way people say it`() {
        assertEquals("a few seconds", formatEta(0))
        assertEquals("a few seconds", formatEta(4_000))
        assertEquals("about 15 s", formatEta(14_000))
        assertEquals("about 55 s", formatEta(54_000))
        assertEquals("about 1 min 00 s", formatEta(59_000))
        assertEquals("about 4 min 20 s", formatEta(260_000))
        assertEquals("about 25 min", formatEta(25 * 60_000L + 20_000))
        assertEquals("about 1 h 05 min", formatEta(65 * 60_000L))
        assertEquals("about 3 h 00 min", formatEta(3 * 3_600_000L))
    }

    @Test
    fun `never negative or empty`() {
        assertTrue(formatEta(-5_000).isNotEmpty())
    }
}

class QueueStoreTest {
    private fun temp(): File = File.createTempFile("queue", ".json").also { it.deleteOnExit() }

    @Test
    fun `saves and restores the unfinished files`() {
        val store = QueueStore(temp())
        val files = listOf(NewFile("content://a", "a.flac", 100, 5), NewFile("content://b", "b é.flac", 200, 0))
        store.save(files)
        assertEquals(files, store.load())
    }

    @Test
    fun `an empty queue removes the file and loads as empty`() {
        val file = temp()
        val store = QueueStore(file)
        store.save(listOf(NewFile("content://a", "a.flac", 1)))
        store.save(emptyList())
        assertTrue(!file.exists())
        assertEquals(emptyList<NewFile>(), store.load())
    }

    @Test
    fun `a corrupt file loads as empty instead of crashing`() {
        val file = temp()
        file.writeText("not json {{{")
        assertEquals(emptyList<NewFile>(), QueueStore(file).load())
    }

    @Test
    fun `clear deletes the saved queue`() {
        val file = temp()
        val store = QueueStore(file)
        store.save(listOf(NewFile("content://a", "a.flac", 1)))
        store.clear()
        assertTrue(!file.exists())
    }
}
