package com.licitaia.core.data.seed

import androidx.room.withTransaction
import com.licitaia.ai.api.TenderAnalysisRequest
import com.licitaia.ai.mock.MockAIProvider
import com.licitaia.core.data.db.BidEventEntity
import com.licitaia.core.data.db.CompanyEntity
import com.licitaia.core.data.db.LicitaDatabase
import com.licitaia.core.data.db.LiveSessionEntity
import com.licitaia.core.data.db.MessageEntity
import com.licitaia.core.data.db.NotificationEntity
import com.licitaia.core.data.db.UserEntity
import com.licitaia.core.data.db.toColumns
import com.licitaia.core.data.db.toDomain
import com.licitaia.core.data.db.toEntity
import com.licitaia.domain.demo.DemoAccount
import com.licitaia.domain.model.BidEventType
import com.licitaia.domain.model.BidRule
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.model.CompanyDocument
import com.licitaia.domain.model.CompetitionRecord
import com.licitaia.domain.model.DocumentType
import com.licitaia.domain.model.LiveStatus
import com.licitaia.domain.model.ManualTenderDraft
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.ProposalItem
import com.licitaia.domain.model.ProposalStatus
import com.licitaia.domain.model.Radar
import com.licitaia.domain.model.ReplyStatus
import com.licitaia.domain.model.RobotMode
import com.licitaia.domain.model.RobotStatus
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.model.UserRole
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Espaço de demonstração ISOLADO. Nada aqui roda sozinho: só o botão "Explorar demonstração" chama
 * [seedDemoWorkspace], que é idempotente (uma empresa demo por aparelho). Todos os dados ficam na
 * empresa `demo = true` e pertencem exclusivamente ao usuário `demo = true`; [purgeDemoWorkspace]
 * apaga tudo por `companyId` (menos a trilha de auditoria, que é append-only e fica órfã/invisível).
 *
 * As licitações são "manuais" (`MANUAL:` em opportunityId): nenhuma linha vai para o cache global de
 * oportunidades, então usuários reais nunca encontram dados fictícios na busca offline.
 */
@Singleton
class DatabaseSeeder @Inject constructor(private val db: LicitaDatabase) {

    data class DemoWorkspace(val companyId: Long, val userId: Long)

    /** Compatibilidade: a edição pessoal nunca semeia nada automaticamente. */
    suspend fun ensureSeeded() = Unit

    /** Empresa e usuário demo existentes (ambos), ou null. */
    suspend fun existingDemoWorkspace(): DemoWorkspace? {
        val company = db.companyDao().getAll().firstOrNull { it.demo } ?: return null
        val user = db.userDao().getAll().firstOrNull { it.demo && company.id in it.companyIds } ?: return null
        return DemoWorkspace(company.id, user.id)
    }

    /** Cria o espaço demo se ainda não existir; sempre devolve um espaço completo. */
    suspend fun seedDemoWorkspace(now: Long = System.currentTimeMillis()): DemoWorkspace {
        existingDemoWorkspace()?.let { return it }
        return db.withTransaction {
            // Restos de um espaço incompleto (ex.: usuário apagado) são removidos antes de recriar.
            db.companyDao().getAll().filter { it.demo }.forEach { purgeCompany(it.id) }
            db.userDao().getAll().filter { it.demo }.forEach { db.userDao().delete(it.id) }

            val companyId = db.companyDao().upsert(
                CompanyEntity(
                    name = DemoAccount.COMPANY_NAME, tradeName = DemoAccount.COMPANY_TRADE_NAME, cnpj = DemoAccount.COMPANY_CNPJ,
                    segment = Segment.TELECOM_ISP, uf = "MG", city = "Uberlândia", preferredAi = null, demo = true,
                ),
            )
            val userId = db.userDao().upsert(
                UserEntity(
                    name = DemoAccount.USER_NAME, email = DemoAccount.EMAIL, role = UserRole.ADMIN, companyIds = listOf(companyId),
                    passwordHash = "", provider = "LOCAL", externalId = null, demo = true,
                ),
            )
            seedContent(companyId, now)
            DemoWorkspace(companyId, userId)
        }
    }

    /** Apaga empresa demo, todos os dados por companyId e o usuário demo. Idempotente. */
    suspend fun purgeDemoWorkspace() {
        db.withTransaction {
            db.companyDao().getAll().filter { it.demo }.forEach { purgeCompany(it.id) }
            db.userDao().getAll().filter { it.demo }.forEach { db.userDao().delete(it.id) }
        }
    }

    private suspend fun purgeCompany(companyId: Long) {
        db.radarDao().deleteByCompany(companyId)
        db.documentDao().deleteByCompany(companyId)
        db.tenderAnalysisDao().deleteByCompany(companyId)
        db.proposalDao().deleteByCompany(companyId)
        db.tenderDao().getByCompany(companyId).forEach { db.tenderDao().delete(it.id) }
        db.portalSessionDao().deleteByCompany(companyId)
        db.messageDao().deleteByCompany(companyId)
        db.competitionDao().deleteByCompany(companyId)
        db.bidEventDao().deleteByCompany(companyId)
        db.liveSessionDao().deleteByCompany(companyId)
        db.notificationDao().deleteByCompany(companyId)
        db.aiConfigDao().deleteByCompany(companyId)
        db.companyDao().delete(companyId)
    }

    // ------------------------------------------------------------------ conteúdo de exemplo

    private suspend fun seedContent(companyId: Long, now: Long) {
        val company = db.companyDao().getById(companyId)!!.toDomain()

        // Radar salvo (roda contra o PNCP real quando o usuário pedir).
        db.radarDao().upsert(
            Radar(
                companyId = companyId, name = "Links dedicados e fibra — Sudeste", segment = Segment.TELECOM_ISP,
                keywords = listOf("link dedicado", "fibra óptica", "internet", "lan-to-lan"), forbiddenKeywords = listOf("satélite"),
                allPortals = true, ufs = listOf("MG", "SP", "ES"), modality = Modality.PREGAO_ELETRONICO,
                minValue = 50_000.0, maxValue = 1_500_000.0, minScore = 60, requireLocalSupport = false, active = true,
                createdAt = now - 21 * DAY,
            ).toEntity(),
        )

        // Documentos com validades variadas (válido, vence em breve, vencido, sem anexo).
        val documents = listOf(
            doc(companyId, DocumentType.CONTRATO_SOCIAL, "Contrato social consolidado (12ª alteração)", "JUCEMG", now - 400 * DAY, null, "demo://contrato-social.pdf", now),
            doc(companyId, DocumentType.CNPJ, "Comprovante de inscrição e situação cadastral", "Receita Federal", now - 20 * DAY, now + 160 * DAY, "demo://cnpj.pdf", now),
            doc(companyId, DocumentType.CERTIDAO_FEDERAL, "Certidão negativa de débitos federais e dívida ativa", "RFB/PGFN", now - 150 * DAY, now + 30 * DAY, "demo://cnd-federal.pdf", now),
            doc(companyId, DocumentType.CERTIDAO_ESTADUAL, "Certidão de débitos tributários estaduais — MG", "SEF/MG", now - 100 * DAY, now + 12 * DAY, "demo://cnd-estadual.pdf", now),
            doc(companyId, DocumentType.CERTIDAO_MUNICIPAL, "Certidão negativa municipal — Uberlândia", "Prefeitura de Uberlândia", now - 200 * DAY, now - 8 * DAY, "demo://cnd-municipal.pdf", now),
            doc(companyId, DocumentType.FGTS, "Certificado de regularidade do FGTS (CRF)", "Caixa", now - 10 * DAY, now + 20 * DAY, "demo://crf.pdf", now),
            doc(companyId, DocumentType.TRABALHISTA, "Certidão negativa de débitos trabalhistas", "TST", now - 60 * DAY, now + 120 * DAY, "demo://cndt.pdf", now),
            doc(companyId, DocumentType.BALANCO, "Balanço patrimonial 2025 (registrado)", "Contabilidade", now - 90 * DAY, now + 275 * DAY, "demo://balanco-2025.pdf", now),
            doc(companyId, DocumentType.SCM, "Ato de autorização SCM nº 3.412/2019", "Anatel", now - 2_000 * DAY, null, "demo://scm.pdf", now),
            doc(companyId, DocumentType.ATESTADO, "Atestado de capacidade técnica — Prefeitura de Araguari (link 1 Gbps)", "Prefeitura de Araguari", now - 300 * DAY, null, "demo://atestado-araguari.pdf", now),
            doc(companyId, DocumentType.CREA_CRT, "Certidão de registro no CREA-MG", "CREA-MG", null, null, null, now),
            doc(companyId, DocumentType.PROCURACAO, "Procuração do representante nos portais", "Tabelionato", now - 30 * DAY, now + 335 * DAY, "demo://procuracao.pdf", now),
        )
        documents.forEach { db.documentDao().upsert(it.toEntity()) }
        val domainDocs = db.documentDao().getByCompany(companyId).map { it.toDomain() }

        // Licitações de interesse (cadastro "manual": fora do cache global de oportunidades).
        val heuristics = MockAIProvider(latencyMs = 0L..0L) { now }
        val tenders = listOf(
            tender(companyId, Portal.COMPRAS_GOV, "90045/2026", "Universidade Federal do Triângulo Mineiro",
                "Contratação de link de internet dedicado de 1 Gbps com proteção anti-DDoS para o campus sede, por 12 meses",
                Segment.TELECOM_ISP, "MG", "Uberaba", 186_000.0, now + 5 * DAY, now + 6 * DAY, TenderStatus.PROPOSTA_EM_ELABORACAO, now - 9 * DAY, now),
            tender(companyId, Portal.LICITANET, "27/2026", "Prefeitura Municipal de Uberaba",
                "Contratação de provedor de acesso à internet com link dedicado de 2 Gbps e bloco IPv4 /28",
                Segment.TELECOM_ISP, "MG", "Uberaba", 228_000.0, now + 4 * DAY, now + 5 * DAY, TenderStatus.AGUARDANDO_APROVACAO, now - 12 * DAY, now),
            tender(companyId, Portal.LICITANET, "61/2026", "Prefeitura Municipal de Patos de Minas",
                "Interligação de 48 prédios públicos por rede metropolitana em fibra óptica (lan-to-lan)",
                Segment.TELECOM_ISP, "MG", "Patos de Minas", 734_000.0, now + 9 * DAY, now + 10 * DAY, TenderStatus.ANALISADA, now - 3 * DAY, now),
            tender(companyId, Portal.BLL, "19/2026", "Câmara Municipal de Joinville",
                "Link de internet dedicado 500 Mbps simétrico com link redundante de 200 Mbps",
                Segment.TELECOM_ISP, "SC", "Joinville", 96_000.0, now + 2 * DAY, now + 3 * DAY, TenderStatus.EM_DISPUTA, now - 15 * DAY, now),
            tender(companyId, Portal.PORTAL_COMPRAS_PUBLICAS, "166/2026", "Prefeitura Municipal de Linhares",
                "Fornecimento de internet em fibra óptica com gerência proativa para 52 pontos da administração",
                Segment.TELECOM_ISP, "ES", "Linhares", 318_000.0, now + 6 * DAY, now + 7 * DAY, TenderStatus.INTERESSE, now - 1 * DAY, now),
            tender(companyId, Portal.PNCP, "90301/2026", "Fundação Universidade Federal do Pampa",
                "Serviço de telefonia IP em nuvem (PABX virtual) com 600 ramais e tronco SIP",
                Segment.TELECOM_ISP, "RS", "Bagé", 297_600.0, now + 7 * DAY, now + 8 * DAY, TenderStatus.ANALISADA, now - 6 * DAY, now),
        )
        val tenderIds = tenders.map { db.tenderDao().upsert(it.toEntity()) }
        // Análise heurística local (o mesmo motor usado quando não há IA real) para as já analisadas.
        tenders.zip(tenderIds).filter { (t, _) -> t.status != TenderStatus.INTERESSE }.forEach { (t, id) ->
            val saved = t.copy(id = id)
            val analysis = heuristics.analyzeTender(TenderAnalysisRequest(saved, company, domainDocs, null, now))
            db.tenderAnalysisDao().upsert(analysis.copy(tenderId = id, aiFields = emptySet()).toEntity())
        }

        // Propostas: rascunho em elaboração (UFTM) e versão em revisão (Uberaba).
        db.proposalDao().upsert(
            Proposal(
                tenderId = tenderIds[0], companyId = companyId, version = 1,
                items = listOf(
                    ProposalItem("Link dedicado 1 Gbps full-duplex com SLA 99,8% — mensalidade", "mês", 12.0, 13_900.0),
                    ProposalItem("Proteção anti-DDoS volumétrica até 10 Gbps — mensalidade", "mês", 12.0, 1_350.0),
                    ProposalItem("Instalação, ativação e roteador de borda em comodato", "serviço", 1.0, 4_800.0),
                ),
                deliveryDays = 30, validityDays = 60,
                notes = "Fibra própria até o campus sede. Equipe de campo em Uberaba (atendimento em até 4 h).",
                status = ProposalStatus.RASCUNHO, createdBy = DemoAccount.USER_NAME, createdAt = now - 2 * DAY,
            ).toEntity(),
        )
        db.proposalDao().upsert(
            Proposal(
                tenderId = tenderIds[1], companyId = companyId, version = 1,
                items = listOf(
                    ProposalItem("Link dedicado 2 Gbps com bloco IPv4 /28 — mensalidade", "mês", 12.0, 16_400.0),
                    ProposalItem("Instalação e ativação", "serviço", 1.0, 6_200.0),
                ),
                deliveryDays = 20, validityDays = 60, notes = "Versão inicial; aguardando revisão da diretoria.",
                status = ProposalStatus.REJEITADA, createdBy = DemoAccount.USER_NAME, createdAt = now - 5 * DAY,
                approvedBy = "Diretoria (demo)", approvedAt = now - 4 * DAY, rejectionReason = "Margem abaixo de 10% no item 1; revisar custo de transporte.",
            ).toEntity(),
        )
        db.proposalDao().upsert(
            Proposal(
                tenderId = tenderIds[1], companyId = companyId, version = 2,
                items = listOf(
                    ProposalItem("Link dedicado 2 Gbps com bloco IPv4 /28 — mensalidade", "mês", 12.0, 17_150.0),
                    ProposalItem("Instalação e ativação", "serviço", 1.0, 5_900.0),
                ),
                deliveryDays = 20, validityDays = 60, notes = "Custo de transporte renegociado; margem 11,4%.",
                status = ProposalStatus.EM_REVISAO, createdBy = DemoAccount.USER_NAME, createdAt = now - 3 * DAY,
            ).toEntity(),
        )

        // Sessão de pregão assistida (Joinville) com histórico de lances.
        val sessionId = "ls-demo-${companyId}-joinville"
        val rule = BidRule(
            mode = RobotMode.MANUAL, strategy = BidStrategy.CONSERVADORA, initialPrice = 96_000.0, floorPrice = 78_500.0,
            costPrice = 66_000.0, reductionValue = 200.0, minMarginPct = 12.0, lossLimit = 0.0, minIntervalSeconds = 6,
            authorizationThresholdPct = 4.0, simulation = true,
        )
        db.liveSessionDao().upsert(
            LiveSessionEntity(
                id = sessionId, companyId = companyId, tenderId = tenderIds[3], portal = Portal.BLL, tenderNumber = "PE 19/2026",
                agency = "Câmara Municipal de Joinville", itemLabel = "Item 1 — Link dedicado 500 Mbps simétrico",
                objectDescription = "Link de internet dedicado 500 Mbps simétrico com link redundante de 200 Mbps",
                status = LiveStatus.PAUSADA, robotStatus = RobotStatus.PAUSADO, position = 2, competitors = 4,
                ourLastBid = 89_400.0, bestBid = 88_900.0, rule = rule.toColumns(), remainingSeconds = null,
                captchaPending = false, captchaSince = null, pendingAuthorization = null, unreadMessages = 1, lastError = null,
                startedAt = now - 50 * MINUTE, updatedAt = now - 6 * MINUTE,
            ),
        )
        db.bidEventDao().insertAll(
            listOf(
                event(sessionId, now - 50 * MINUTE, BidEventType.SESSION_OPENED, null, "Sistema", "Sessão assistida aberta (lances registrados manualmente)"),
                event(sessionId, now - 44 * MINUTE, BidEventType.OUR_BID, 94_000.0, DemoAccount.USER_NAME, "Lance inicial registrado"),
                event(sessionId, now - 38 * MINUTE, BidEventType.COMPETITOR_BID, 92_500.0, "Concorrente A", "Lance registrado"),
                event(sessionId, now - 31 * MINUTE, BidEventType.OUR_BID, 91_200.0, DemoAccount.USER_NAME, "Lance registrado"),
                event(sessionId, now - 24 * MINUTE, BidEventType.COMPETITOR_BID, 90_100.0, "Concorrente B", "Lance registrado"),
                event(sessionId, now - 16 * MINUTE, BidEventType.OUR_BID, 89_400.0, DemoAccount.USER_NAME, "Lance registrado"),
                event(sessionId, now - 9 * MINUTE, BidEventType.COMPETITOR_BID, 88_900.0, "Concorrente A", "Lance registrado"),
                event(sessionId, now - 8 * MINUTE, BidEventType.POSITION_CHANGED, null, "Sistema", "Posição atual: 2º"),
                event(sessionId, now - 6 * MINUTE, BidEventType.MESSAGE, null, "Pregoeiro", "Mensagem recebida do pregoeiro"),
            ),
        )

        // Mensagens do pregoeiro.
        db.messageDao().upsert(
            MessageEntity(
                companyId = companyId, sessionId = sessionId, portal = Portal.BLL, tenderNumber = "PE 19/2026", sender = "Pregoeiro — Câmara de Joinville",
                body = "Sr. licitante, favor informar em até 30 minutos se o link redundante de 200 Mbps utilizará rota física distinta da principal, conforme item 4.3 do Termo de Referência.",
                receivedAt = now - 6 * MINUTE, read = false, urgent = true, responseDeadline = now + 24 * MINUTE,
                aiSummary = null, suggestedReply = null, replyDraft = null, replyStatus = ReplyStatus.NENHUMA, repliedAt = null,
            ),
        )
        db.messageDao().upsert(
            MessageEntity(
                companyId = companyId, sessionId = null, portal = Portal.LICITANET, tenderNumber = "PE 27/2026", sender = "Pregoeira — Prefeitura de Uberaba",
                body = "Solicitamos esclarecimento sobre a comprovação do bloco IPv4 /28: será aceito bloco alocado pelo NIC.br em nome da licitante ou somente ASN próprio?",
                receivedAt = now - 2 * DAY, read = true, urgent = false, responseDeadline = now + 1 * DAY,
                aiSummary = "Pergunta se o bloco IPv4 /28 pode ser alocado pelo NIC.br em nome da licitante ou se exige ASN próprio.",
                suggestedReply = "Informamos que a Demo Telecom possui ASN próprio (AS 2659xx) e bloco IPv4 alocado diretamente pelo NIC.br, atendendo integralmente ao item 5.2 do edital.",
                replyDraft = "Informamos que a Demo Telecom possui ASN próprio e bloco IPv4 alocado diretamente pelo NIC.br, atendendo integralmente ao item 5.2 do edital.",
                replyStatus = ReplyStatus.RASCUNHO, repliedAt = null,
            ),
        )
        db.messageDao().upsert(
            MessageEntity(
                companyId = companyId, sessionId = null, portal = Portal.COMPRAS_GOV, tenderNumber = "PE 90045/2026", sender = "Pregoeiro — UFTM",
                body = "Confirmar prazo de ativação do link em até 30 dias corridos após assinatura do contrato.",
                receivedAt = now - 7 * DAY, read = true, urgent = false, responseDeadline = null,
                aiSummary = "Pede confirmação do prazo de ativação de 30 dias corridos.",
                suggestedReply = "Confirmamos o prazo de ativação em até 30 dias corridos contados da assinatura do contrato.",
                replyDraft = "Confirmamos o prazo de ativação em até 30 dias corridos contados da assinatura do contrato.",
                replyStatus = ReplyStatus.ENVIADA_SIMULADA, repliedAt = now - 7 * DAY + 2 * HOUR,
            ),
        )

        // Histórico de concorrência.
        db.competitionDao().insertAll(
            listOf(
                competition(companyId, Portal.COMPRAS_GOV, "PE 90210/2025", "Instituto Federal do Triângulo Mineiro", "Link dedicado 1 Gbps — campus Ituiutaba", now - 120 * DAY, 5, 174_000.0, 149_800.0, 149_800.0, true, 13.2, 41, "Dois concorrentes agressivos nos primeiros 10 min; fechamento no último minuto"),
                competition(companyId, Portal.BLL, "PE 77/2025", "Prefeitura Municipal de Araguari", "Internet fibra para 42 escolas", now - 95 * DAY, 4, 312_000.0, 268_500.0, 268_500.0, true, 11.8, 36, "Concorrente local parou cedo; disputa final com operadora nacional"),
                competition(companyId, Portal.LICITANET, "PE 102/2025", "Prefeitura Municipal de Patrocínio", "Rede metropolitana lan-to-lan 18 prédios", now - 70 * DAY, 6, 540_000.0, 418_900.0, 452_000.0, false, 9.5, 58, "Vencedor fechou 7% abaixo do nosso piso; provável fibra já instalada"),
                competition(companyId, Portal.PORTAL_COMPRAS_PUBLICAS, "PE 55/2025", "Prefeitura Municipal de Frutal", "Link dedicado 300 Mbps + Wi-Fi em praças", now - 48 * DAY, 3, 128_000.0, 109_900.0, 109_900.0, true, 14.1, 22, "Poucos lances; concorrentes sem presença local"),
                competition(companyId, Portal.COMPRAS_GOV, "PE 90333/2025", "Universidade Federal de Uberlândia", "Telefonia IP 400 ramais", now - 20 * DAY, 7, 262_000.0, 198_000.0, 221_400.0, false, 8.9, 73, "Operadora nacional com preço de referência agressivo; desistimos no piso"),
            ).map { it.toEntity() },
        )

        // Notificações recentes.
        db.notificationDao().insert(
            NotificationEntity(
                companyId = companyId, category = NotificationCategory.MENSAGENS, title = "Mensagem urgente do pregoeiro — BLL PE 19/2026",
                body = "Responder em 30 minutos sobre a rota física do link redundante.", createdAt = now - 6 * MINUTE, read = false, critical = true,
                route = "messages", sessionId = sessionId,
            ),
        )
        db.notificationDao().insert(
            NotificationEntity(
                companyId = companyId, category = NotificationCategory.DOCUMENTOS, title = "Certidão municipal vencida",
                body = "A certidão negativa municipal venceu há 8 dias. Renove antes da sessão de Uberaba.", createdAt = now - 3 * HOUR, read = false, critical = false,
                route = "documents", sessionId = null,
            ),
        )
        db.notificationDao().insert(
            NotificationEntity(
                companyId = companyId, category = NotificationCategory.RADAR, title = "Radar: 3 novas oportunidades",
                body = "Links dedicados e fibra — Sudeste encontrou 3 licitações com score ≥ 60.", createdAt = now - 1 * DAY, read = true, critical = false,
                route = "radar", sessionId = null,
            ),
        )
    }

    // ------------------------------------------------------------------ fábricas

    private fun doc(
        companyId: Long, type: DocumentType, title: String, issuer: String, issuedAt: Long?, expiresAt: Long?, attachment: String?, now: Long,
    ) = CompanyDocument(
        companyId = companyId, type = type, title = title, issuer = issuer, issuedAt = issuedAt, expiresAt = expiresAt,
        attachmentUri = attachment, tags = listOf("demonstração"), notes = if (attachment == null) "Pendente de emissão." else "",
        createdAt = now - 30 * DAY,
    )

    private fun tender(
        companyId: Long, portal: Portal, number: String, agency: String, obj: String, segment: Segment, uf: String, city: String,
        value: Double, deadline: Long, sessionAt: Long, status: TenderStatus, createdAt: Long, now: Long,
    ): Tender {
        val draft = ManualTenderDraft(portal, "PE $number", agency, obj, Modality.PREGAO_ELETRONICO, segment, uf, city, value, deadline, sessionAt, portal.publicUrl)
        return Tender(
            companyId = companyId, opportunityId = draft.opportunityId(), portal = portal, number = "PE $number", agency = agency,
            objectDescription = obj, modality = Modality.PREGAO_ELETRONICO, segment = segment, uf = uf, city = city, estimatedValue = value,
            proposalDeadline = deadline, sessionAt = sessionAt, status = status, editalRegistered = status != TenderStatus.INTERESSE,
            createdAt = createdAt, updatedAt = now - 1 * HOUR,
        )
    }

    private fun event(sessionId: String, at: Long, type: BidEventType, value: Double?, actor: String, description: String) =
        BidEventEntity(sessionId = sessionId, timestamp = at, type = type, value = value, actor = actor, description = description)

    private fun competition(
        companyId: Long, portal: Portal, number: String, agency: String, summary: String, date: Long, competitors: Int,
        estimated: Double, closing: Double, ourFinal: Double, won: Boolean, margin: Double, bids: Int, behavior: String,
    ) = CompetitionRecord(
        companyId = companyId, portal = portal, tenderNumber = number, agency = agency, segment = Segment.TELECOM_ISP, objectSummary = summary,
        date = date, competitors = competitors, estimatedValue = estimated, closingValue = closing, ourFinalBid = ourFinal, won = won,
        ourMarginPct = margin, bidsCount = bids, behavior = behavior,
    )

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
