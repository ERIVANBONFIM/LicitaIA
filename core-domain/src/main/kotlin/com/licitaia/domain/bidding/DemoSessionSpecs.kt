package com.licitaia.domain.bidding

import com.licitaia.domain.model.BidRule
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.model.LiveSessionSpec
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.RobotMode

/** Sessões de pregão FICTÍCIAS usadas na demonstração (dados locais, sem portal real). */
object DemoSessionSpecs {

    /** As 3 sessões iniciais: Compras.gov, BLL e Licitanet, com modos e estratégias diferentes. */
    fun initial(companyId: Long): List<LiveSessionSpec> = listOf(
        LiveSessionSpec(
            companyId = companyId,
            portal = Portal.COMPRAS_GOV,
            tenderNumber = "PE 90045/2026",
            agency = "Universidade Federal do Triângulo Mineiro",
            itemLabel = "Item 1 — Link dedicado 1 Gbps (12 meses)",
            objectDescription = "Link de internet dedicado de 1 Gbps com proteção anti-DDoS para o campus sede",
            rule = BidRule(
                mode = RobotMode.AUTOMATICO_LIMITADO, strategy = BidStrategy.ACOMPANHAR_CONCORRENTE,
                initialPrice = 186_000.0, floorPrice = 151_500.0, costPrice = 129_000.0, reductionValue = 450.0,
                minMarginPct = 12.0, lossLimit = 0.0, minIntervalSeconds = 6, authorizationThresholdPct = 3.0,
            ),
            competitors = 5,
        ),
        LiveSessionSpec(
            companyId = companyId,
            portal = Portal.BLL,
            tenderNumber = "PE 38/2026",
            agency = "Prefeitura Municipal de Toledo",
            itemLabel = "Lote 1 — Switches gerenciáveis camada 3",
            objectDescription = "Switches gerenciáveis camada 3 e transceptores SFP+ para o datacenter municipal",
            rule = BidRule(
                mode = RobotMode.SUPERVISIONADO, strategy = BidStrategy.CONSERVADORA,
                initialPrice = 124_800.0, floorPrice = 104_000.0, costPrice = 91_500.0, reductionValue = 300.0,
                minMarginPct = 10.0, lossLimit = 0.0, minIntervalSeconds = 10, authorizationThresholdPct = 5.0,
            ),
            competitors = 3,
        ),
        LiveSessionSpec(
            companyId = companyId,
            portal = Portal.LICITANET,
            tenderNumber = "PE 27/2026",
            agency = "Prefeitura Municipal de Uberaba",
            itemLabel = "Item 1 — Link dedicado 2 Gbps + bloco IPv4 /28",
            objectDescription = "Provedor de acesso à internet com link dedicado de 2 Gbps e bloco IPv4 /28",
            rule = BidRule(
                mode = RobotMode.AUTOMATICO_LIMITADO, strategy = BidStrategy.AGRESSIVA,
                initialPrice = 228_000.0, floorPrice = 183_000.0, costPrice = 158_000.0, reductionValue = 400.0,
                minMarginPct = 11.0, lossLimit = 0.0, minIntervalSeconds = 5, authorizationThresholdPct = 4.0,
            ),
            competitors = 4,
        ),
    )

    private val extras: List<LiveSessionSpec> = listOf(
        LiveSessionSpec(
            companyId = 0,
            portal = Portal.PORTAL_COMPRAS_PUBLICAS,
            tenderNumber = "PE 31/2026",
            agency = "Prefeitura Municipal de Caruaru",
            itemLabel = "Item 1 — Link dedicado 1 Gbps (centro administrativo)",
            objectDescription = "Link de internet dedicado 1 Gbps para o centro administrativo e 300 Mbps para 12 secretarias",
            rule = BidRule(
                mode = RobotMode.SUPERVISIONADO, strategy = BidStrategy.PERSONALIZADA,
                initialPrice = 352_000.0, floorPrice = 288_000.0, costPrice = 251_000.0, reductionValue = 900.0,
                minMarginPct = 10.0, lossLimit = 0.0, minIntervalSeconds = 8, authorizationThresholdPct = 5.0,
            ),
            competitors = 4,
        ),
        LiveSessionSpec(
            companyId = 0,
            portal = Portal.BLL,
            tenderNumber = "PE 19/2026",
            agency = "Câmara Municipal de Joinville",
            itemLabel = "Item 1 — Link dedicado 500 Mbps simétrico",
            objectDescription = "Link de internet dedicado 500 Mbps simétrico com link redundante de 200 Mbps",
            rule = BidRule(
                mode = RobotMode.AUTOMATICO_LIMITADO, strategy = BidStrategy.ACOMPANHAR_CONCORRENTE,
                initialPrice = 96_000.0, floorPrice = 78_500.0, costPrice = 66_000.0, reductionValue = 200.0,
                minMarginPct = 12.0, lossLimit = 0.0, minIntervalSeconds = 6, authorizationThresholdPct = 4.0,
            ),
            competitors = 6,
        ),
        LiveSessionSpec(
            companyId = 0,
            portal = Portal.COMPRAS_GOV,
            tenderNumber = "PE 90301/2026",
            agency = "Fundação Universidade Federal do Pampa",
            itemLabel = "Item 1 — PABX virtual com 600 ramais",
            objectDescription = "Serviço de telefonia IP em nuvem (PABX virtual) com 600 ramais e tronco SIP",
            rule = BidRule(
                mode = RobotMode.MANUAL, strategy = BidStrategy.CONSERVADORA,
                initialPrice = 297_600.0, floorPrice = 246_000.0, costPrice = 214_000.0, reductionValue = 700.0,
                minMarginPct = 10.0, lossLimit = 0.0, minIntervalSeconds = 10, authorizationThresholdPct = 5.0,
            ),
            competitors = 4,
        ),
        LiveSessionSpec(
            companyId = 0,
            portal = Portal.LICITANET,
            tenderNumber = "PE 61/2026",
            agency = "Prefeitura Municipal de Patos de Minas",
            itemLabel = "Lote único — Rede metropolitana lan-to-lan",
            objectDescription = "Interligação de 48 prédios públicos por rede metropolitana em fibra óptica",
            rule = BidRule(
                mode = RobotMode.SUPERVISIONADO, strategy = BidStrategy.AGRESSIVA,
                initialPrice = 734_000.0, floorPrice = 602_000.0, costPrice = 528_000.0, reductionValue = 1_500.0,
                minMarginPct = 10.0, lossLimit = 0.0, minIntervalSeconds = 8, authorizationThresholdPct = 5.0,
            ),
            competitors = 5,
        ),
    )

    /** Sessão extra para o botão "Nova sessão de demonstração"; [index] gira pelo conjunto disponível. */
    fun extra(companyId: Long, index: Int): LiveSessionSpec =
        extras[Math.floorMod(index, extras.size)].copy(companyId = companyId)
}
