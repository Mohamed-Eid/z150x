package dev.zanderp.opencfmoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Trip files are replaced through a temp file and a rename, never written in place. */
class TripStoreAtomicTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun writesANewFileAndLeavesNoTempBehind() {
        val dir = tmp.newFolder("trips")
        val f = File(dir, "1700000000000.json")
        TripStore.writeAtomic(f, """{"id":"1700000000000"}""")
        assertEquals("""{"id":"1700000000000"}""", f.readText())
        assertArrayEquals(arrayOf("1700000000000.json"), dir.list())
    }

    @Test fun replacesAnExistingFileWhole() {
        val dir = tmp.newFolder("trips")
        val f = File(dir, "trips-index.json")
        f.writeText("x".repeat(10_000))
        TripStore.writeAtomic(f, "[]")
        assertEquals("[]", f.readText())
        assertArrayEquals(arrayOf("trips-index.json"), dir.list())
    }

    @Test fun keepsNonAsciiTextAsUtf8() {
        val f = File(tmp.newFolder("trips"), "a.json")
        TripStore.writeAtomic(f, """{"name":"المعادي → الزمالك"}""")
        assertEquals("""{"name":"المعادي → الزمالك"}""", f.readText(Charsets.UTF_8))
    }

    @Test fun aFailedWriteThrowsAndLeavesTheOldFileUntouched() {
        val dir = tmp.newFolder("trips")
        val f = File(dir, "1.json")
        f.writeText("old ride")
        // The temp file's name is taken by a directory, so the write cannot even start.
        assertTrue(File(dir, "1.json.tmp").mkdir())
        try {
            TripStore.writeAtomic(f, "new ride")
            fail("expected an IOException")
        } catch (_: IOException) {
        }
        assertEquals("old ride", f.readText())
    }

    @Test fun aFailedRenameThrowsAndCleansUpTheTempFile() {
        val dir = tmp.newFolder("trips")
        // The target is a non-empty folder: the temp file is written fine but cannot replace it.
        val f = File(dir, "1.json")
        assertTrue(f.mkdir())
        File(f, "child").writeText("x")
        try {
            TripStore.writeAtomic(f, "new ride")
            fail("expected an IOException")
        } catch (_: IOException) {
        }
        assertArrayEquals(arrayOf("1.json"), dir.list())
    }

    @Test fun aMissingFolderIsReportedNotSwallowed() {
        val f = File(File(tmp.root, "no-such-folder"), "1.json")
        try {
            TripStore.writeAtomic(f, "{}")
            fail("expected an IOException")
        } catch (_: IOException) {
        }
        assertFalse(f.exists())
    }
}
