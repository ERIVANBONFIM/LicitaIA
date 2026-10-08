package com.licitaia.core.platform

import com.licitaia.core.platform.net.TenderDto
import com.licitaia.core.platform.net.TenderPageDto
import com.licitaia.core.platform.sync.PlatformTenderSync
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformTenderSyncTest {

    private fun tender(id: String, updatedAt: String) =
        TenderDto(id = id, numero = id, orgao = "o", objeto = "x", updatedAt = updatedAt)

    @Test fun `pulls all pages and upserts by id`() = runTest {
        val dao = FakeTenderDao()
        val sync = PlatformTenderSync(dao) { 1L }
        val pages = mapOf(
            1 to TenderPageDto(listOf(tender("a", "2026-10-08T03:00:00Z"), tender("b", "2026-10-08T02:00:00Z")), total = 3, page = 1, totalPages = 2),
            2 to TenderPageDto(listOf(tender("c", "2026-10-08T01:00:00Z")), total = 3, page = 2, totalPages = 2),
        )
        val result = sync.sync { page -> pages.getValue(page) }

        assertEquals(3, result.fetched)
        assertEquals(3, result.totalLocal)
        assertEquals(2, result.pages)
        assertEquals(setOf("a", "b", "c"), dao.rows.keys)
    }

    @Test fun `stops early when reaching a known updatedAt (incremental)`() = runTest {
        val dao = FakeTenderDao()
        // Já conhecemos "b" (updatedAt 02:00). Segunda carga traz algo mais novo + o conhecido.
        val sync = PlatformTenderSync(dao) { 1L }
        sync.sync { TenderPageDto(listOf(tender("b", "2026-10-08T02:00:00Z")), total = 1, page = 1, totalPages = 1) }

        var pagesRequested = 0
        val pages = mapOf(
            1 to TenderPageDto(listOf(tender("d", "2026-10-08T05:00:00Z"), tender("b", "2026-10-08T02:00:00Z")), total = 10, page = 1, totalPages = 5),
            2 to TenderPageDto(listOf(tender("z", "2026-10-07T00:00:00Z")), total = 10, page = 2, totalPages = 5),
        )
        val result = sync.sync { page -> pagesRequested++; pages.getValue(page) }

        assertEquals("deveria parar na página 1 ao reencontrar o conhecido", 1, pagesRequested)
        assertEquals(1, result.pages)
        assertEquals(true, dao.rows.containsKey("d"))
    }

    @Test fun `stops on empty page`() = runTest {
        val dao = FakeTenderDao()
        val sync = PlatformTenderSync(dao) { 1L }
        val result = sync.sync { TenderPageDto(emptyList(), total = 0, page = 1, totalPages = 1) }
        assertEquals(0, result.fetched)
        assertEquals(1, result.pages)
    }
}
