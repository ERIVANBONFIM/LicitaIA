package com.licitaia.core.data.repository

import com.licitaia.ai.api.AiGateway
import com.licitaia.core.data.db.TenderDao
import com.licitaia.core.data.db.TenderEntity
import com.licitaia.core.data.edital.EditalDownloader
import com.licitaia.domain.model.AppSettings
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.OfficialFile
import com.licitaia.domain.model.OfficialLinks
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Tenho interesse" NÃO dispara análise por IA (nem download) com a configuração padrão. */
class InterestWithoutAutoAnalysisTest {

    private val opportunity = Opportunity(
        id = "PNCP:20918579000183-1-000016/2025", portal = Portal.COMPRAS_GOV, number = "90016/2025", agency = "Prefeitura",
        objectDescription = "Link dedicado de internet", modality = Modality.PREGAO_ELETRONICO, segment = Segment.TELECOM_ISP,
        uf = "MG", city = "BH", estimatedValue = 50_000.0, publishedAt = 1L, proposalDeadline = 2L, sessionAt = 2L,
        editalUrl = "https://pncp.gov.br/app/editais/20918579000183/2025/16",
    )

    private fun repository(auto: Boolean, tenderDao: TenderDao, gateway: AiGateway, downloader: EditalDownloader, scope: CoroutineScope): TenderRepositoryImpl {
        val settings = mockk<SettingsRepository> { every { settings } returns flowOf(AppSettings(autoAnalyzeOnInterest = auto)) }
        return TenderRepositoryImpl(
            tenderDao = tenderDao, analysisDao = mockk(relaxed = true), proposalDao = mockk(relaxed = true),
            questionDao = mockk(relaxed = true), flagDao = mockk(relaxed = true), opportunityDao = mockk(relaxed = true),
            companyDao = mockk(relaxed = true), documentDao = mockk(relaxed = true), notificationDao = mockk(relaxed = true),
            registry = mockk(relaxed = true), gateway = gateway, audit = mockk(relaxed = true), access = mockk(relaxed = true),
            editalStore = mockk(relaxed = true), pdfExtractor = mockk(relaxed = true), pdfOcrEngine = mockk(relaxed = true),
            editalDownloader = downloader, scope = scope, settings = settings,
        )
    }

    @Test
    fun `interesse salva com status INTERESSE e nao chama IA nem baixa edital`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val saved = slot<TenderEntity>()
        val tenderDao = mockk<TenderDao>(relaxed = true) {
            coEvery { getByOpportunity(any(), any()) } returns null
            coEvery { getByStatus(any()) } returns emptyList()
            coEvery { upsert(capture(saved)) } returns 7L
        }
        val gateway = mockk<AiGateway>(relaxed = true)
        val downloader = mockk<EditalDownloader>(relaxed = true)
        val repo = repository(auto = false, tenderDao = tenderDao, gateway = gateway, downloader = downloader, scope = scope)

        val id = repo.markInterest(companyId = 1, opportunity = opportunity)
        delay(200)

        assertEquals(7L, id)
        assertEquals(TenderStatus.INTERESSE.name, saved.captured.status.toString().let { s -> TenderStatus.entries.first { it.name == s || it.toString() == s }.name })
        coVerify(exactly = 0) { gateway.current() }
        coVerify(exactly = 0) { downloader.download(any(), any()) }
        coVerify(exactly = 0) { tenderDao.updateStatus(7L, TenderStatus.EM_ANALISE, any()) }
        scope.cancel()
    }

    @Test
    fun `cache de links oficiais ida e volta`() {
        val links = OfficialLinks(
            originUrl = "https://bllcompras.com/Process/1", pncpUrl = "https://pncp.gov.br/app/editais/1/2025/1",
            files = listOf(OfficialFile("Edital.pdf", "Edital", "https://pncp.gov.br/a/1", publishedAt = 5L, sequence = 1)),
            fetchedAt = 9L,
        )
        assertEquals(links, OfficialLinksStore.decode(OfficialLinksStore.encode(links)))
        assertNull(OfficialLinksStore.decode("lixo"))
    }
}
