package dev.nozzlenaut.androidklipper.usb

import dev.nozzlenaut.androidklipper.usb.UsbStableIds.Candidate
import dev.nozzlenaut.androidklipper.usb.UsbStableIds.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbStableIdsTest {
    private class MapStore : UsbStableIds.Store {
        val map = mutableMapOf<String, String>()
        override fun get(deviceName: String) = map[deviceName]
        override fun put(deviceName: String, value: String) { map[deviceName] = value }
        override fun remove(deviceName: String) { map.remove(deviceName) }
    }

    private fun candidate(path: String, serial: String?) = Candidate(path, 0x1d50, 0x614e, serial)

    @Test
    fun aSerialIsUsedAndRemembered() {
        val store = MapStore()
        val ids = UsbStableIds(store, epoch = "7")
        val r = ids.resolve(candidate("/dev/bus/usb/001/004", "ABC123"))
        assertEquals("ABC123", r.id)
        assertEquals(Source.SERIAL, r.source)
        assertEquals("7|ABC123", store.map["/dev/bus/usb/001/004"])
    }

    @Test
    fun aMissingSerialFallsBackToTheCacheInTheSameEpoch() {
        val store = MapStore()
        val ids = UsbStableIds(store, epoch = "7")
        ids.resolve(candidate("/dev/bus/usb/001/004", "ABC123"))
        val r = ids.resolve(candidate("/dev/bus/usb/001/004", null))
        assertEquals("ABC123", r.id)
        assertEquals(Source.CACHED, r.source)
    }

    @Test
    fun aBlankSerialCountsAsMissing() {
        val ids = UsbStableIds(MapStore(), epoch = "7")
        ids.resolve(candidate("p", "XYZ"))
        assertEquals(Source.CACHED, ids.resolve(candidate("p", "  ")).source)
    }

    @Test
    fun aCacheEntryFromAnotherBootIsIgnored() {
        val store = MapStore()
        UsbStableIds(store, epoch = "7").resolve(candidate("p", "ABC123"))
        val r = UsbStableIds(store, epoch = "8").resolve(candidate("p", null))
        assertEquals(Source.PATH_FALLBACK, r.source)
        assertEquals("1d50:614e:p", r.id)
    }

    @Test
    fun legacyBareSerialEntriesAreIgnored() {
        val store = MapStore().apply { map["p"] = "OLDSERIAL" }
        val r = UsbStableIds(store, epoch = "7").resolve(candidate("p", null))
        assertEquals(Source.PATH_FALLBACK, r.source)
    }

    @Test
    fun replugAtTheSamePathDoesNotInheritTheOldBoardsSerial() {
        // The bug this guards against: board A unplugged, board B enumerates on the
        // same device path, B's serial read fails, and B silently becomes A.
        val store = MapStore()
        val ids = UsbStableIds(store, epoch = "7")
        ids.resolve(candidate("/dev/bus/usb/001/004", "BOARD-A"))
        ids.forget("/dev/bus/usb/001/004") // ACTION_USB_DEVICE_DETACHED
        val r = ids.resolve(candidate("/dev/bus/usb/001/004", null))
        assertEquals(Source.PATH_FALLBACK, r.source)
        assertNull(store.map["/dev/bus/usb/001/004"])
    }

    @Test
    fun forgetOnlyAffectsThatDevice() {
        val store = MapStore()
        val ids = UsbStableIds(store, epoch = "1")
        ids.resolve(candidate("a", "SA"))
        ids.resolve(candidate("b", "SB"))
        ids.forget("a")
        assertEquals(Source.PATH_FALLBACK, ids.resolve(candidate("a", null)).source)
        assertEquals("SB", ids.resolve(candidate("b", null)).id)
    }

    @Test
    fun duplicateIdsAreReportedWithTheirPaths() {
        val rs = listOf(
            UsbStableIds.Resolution("p1", "S1", Source.SERIAL),
            UsbStableIds.Resolution("p2", "S1", Source.CACHED),
            UsbStableIds.Resolution("p3", "S3", Source.SERIAL)
        )
        assertEquals(mapOf("S1" to listOf("p1", "p2")), UsbStableIds.duplicates(rs))
    }

    @Test
    fun distinctIdsHaveNoDuplicates() {
        val rs = listOf(
            UsbStableIds.Resolution("p1", "S1", Source.SERIAL),
            UsbStableIds.Resolution("p2", "S2", Source.SERIAL)
        )
        assertTrue(UsbStableIds.duplicates(rs).isEmpty())
    }

    @Test
    fun aStaleCacheEntryOnASecondIdenticalBoardIsCaughtAsADuplicate() {
        // Two identical boards. Board 2's serial read fails and its path has a stale
        // entry holding board 1's serial: both would map to the same MCU.
        val store = MapStore().apply { map["p2"] = "7|S1" }
        val ids = UsbStableIds(store, epoch = "7")
        val r1 = ids.resolve(candidate("p1", "S1"))
        val r2 = ids.resolve(candidate("p2", null))
        assertEquals(Source.CACHED, r2.source)
        assertEquals(setOf("S1"), UsbStableIds.duplicates(listOf(r1, r2)).keys)
    }

    @Test
    fun onlyPathFallbacksNeedMapping() {
        assertTrue(UsbStableIds.needsMapping(UsbStableIds.Resolution("p", "x", Source.PATH_FALLBACK)))
        assertFalse(UsbStableIds.needsMapping(UsbStableIds.Resolution("p", "x", Source.SERIAL)))
        assertFalse(UsbStableIds.needsMapping(UsbStableIds.Resolution("p", "x", Source.CACHED)))
    }
}
