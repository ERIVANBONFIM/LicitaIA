package com.licitaia.core.platform

import com.licitaia.core.platform.queue.MutationType
import com.licitaia.core.platform.queue.OfflineMutationQueue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineMutationQueueTest {

    @Test fun `enqueue keeps FIFO order and payload`() = runTest {
        val dao = FakeMutationDao()
        var t = 100L
        val queue = OfflineMutationQueue(dao) { t++ }

        queue.enqueue(MutationType.FAVORITAR, "t1")
        queue.enqueue(MutationType.FASE, "t2", payload = """{"fase":"analise"}""")

        val pending = queue.pending()
        assertEquals(2, pending.size)
        assertEquals("FAVORITAR", pending[0].type)
        assertEquals("t1", pending[0].targetId)
        assertEquals("""{"fase":"analise"}""", pending[1].payload)
    }

    @Test fun `markSent removes and discard removes`() = runTest {
        val dao = FakeMutationDao()
        val queue = OfflineMutationQueue(dao) { 1L }
        queue.enqueue(MutationType.OCULTAR, "a")
        val second = queue.enqueue(MutationType.ARQUIVAR, "b")

        queue.markSent(queue.pending().first())
        assertEquals(1, queue.count())
        assertEquals("b", queue.pending().first().targetId)

        queue.discard(dao.rows.first { it.id == second })
        assertEquals(0, queue.count())
    }

    @Test fun `markFailed increments attempts and keeps in queue`() = runTest {
        val dao = FakeMutationDao()
        val queue = OfflineMutationQueue(dao) { 1L }
        queue.enqueue(MutationType.FAVORITAR, "a")
        val m = queue.pending().first()
        queue.markFailed(m, "timeout")

        val after = queue.pending().first()
        assertEquals(1, after.attempts)
        assertEquals("timeout", after.lastError)
        assertEquals(1, queue.count())
    }
}
