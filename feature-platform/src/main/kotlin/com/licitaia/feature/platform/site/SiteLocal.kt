package com.licitaia.feature.platform.site

import android.content.Context
import com.licitaia.core.platform.PlatformConfig
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.net.TenderDto
import com.licitaia.core.platform.session.PlatformIdentity
import com.licitaia.core.platform.session.PlatformSession
import com.licitaia.core.ui.nav.Routes
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.domain.model.PortalDeclarations
import com.licitaia.domain.portal.PortalMyTender
import com.licitaia.domain.portal.PortalRobotPlan
import com.licitaia.domain.portal.PortalRobotRepository
import com.licitaia.domain.portal.PortalTenderMatching
import com.licitaia.domain.portal.ProposalAuthorization
import com.licitaia.domain.portal.ProposalItemPlan
import com.licitaia.domain.portal.RobotProposalStatus
import com.licitaia.domain.repository.AiConfigRepository
import com.licitaia.domain.repository.PortalRepository
import com.licitaia.feature.live.automation.PortalRobotEngine
import com.licitaia.feature.live.automation.PortalSessionGate
import com.licitaia.feature.live.automation.RobotKind
import com.licitaia.feature.live.automation.RunStatus
import com.licitaia.feature.live.web.PortalWebViewHolder
import com.licitaia.feature.platform.PlatformRobotSync
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * O que o SITE pede e, dentro do app, roda NO CELULAR (com o certificado daqui), igual ao programa do PC:
 * Registrar Proposta (robô de proposta do aparelho), Portais (sessão do Comprasnet deste celular), Robô de Registro /
 * Navegadores / Logs (robôs do celular), Certificado e IA (ficam só no aparelho) e "Ver a tela do robô".
 * Nada disso vai para o robô/navegador da VPS. O resto do site continua falando direto com a VPS.
 */
@Singleton
class SiteLocal @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PlatformRepository,
    private val engine: PortalRobotEngine,
    private val roboRepo: PortalRobotRepository,
    private val robotSync: PlatformRobotSync,
    private val gate: PortalSessionGate,
    private val holder: PortalWebViewHolder,
    private val portals: PortalRepository,
    private val ai: AiConfigRepository,
) {
    /** Resposta ao site; [abrir] = tela nativa a abrir no app (portal, IA, Portais). */
    data class Resp(val status: Int, val json: String, val abrir: String? = null)

    private val prefs by lazy { context.getSharedPreferences("licitapro_site_celular", Context.MODE_PRIVATE) }

    private val ROTAS: List<Pair<String, Regex>> = listOf(
        "POST" to Regex("^/api/executor/registrar-proposta$"),
        "GET" to Regex("^/api/executor/jobs/cel-[\\w-]+$"),
        "POST" to Regex("^/api/licitacoes/[\\w-]{8,64}/registration-eligibility/refresh$"),
        "GET" to Regex("^/api/licitacoes/[\\w-]{8,64}/preparacao-registro$"),
        "POST" to Regex("^/api/vnc/session$"),
        "GET" to Regex("^/api/integracoes/status$"),
        "GET" to Regex("^/api/integracoes/comprasnet/estado-sessao$"),
        "POST" to Regex("^/api/integracoes/portal/[^/]+/(verificar-sessao|abrir-login|fechar)$"),
        "GET" to Regex("^/api/robo-registro/(status|navegadores)$"),
        "POST" to Regex("^/api/robo-registro/religar-vpn$"),
        "GET" to Regex("^/api/logs/robo$"),
        "*" to Regex("^/api/empresa/certificado$"),
        "*" to Regex("^/api/ia/provedores(/.*)?$"),
        "*" to Regex("^/__app/.*$"),
        "GET" to Regex("^/api/notificacoes$"),
    )

    // ---- avisos REAIS dos robôs do celular para o sino (VPS: POST /api/notificacoes/evento) ----
    private val escopo = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val ultimoStatus = java.util.concurrent.ConcurrentHashMap<String, RunStatus>()
    @Volatile private var avisando = false

    /** Começa a observar os robôs do celular (uma vez): proposta cadastrada, esperando você, falhou → sino. */
    private fun garantirAvisos() {
        if (avisando) return
        avisando = true
        escopo.launch {
            engine.runs.collect { runs ->
                runs.values.forEach { r ->
                    val antes = ultimoStatus.put(r.id, r.status)
                    if (antes == r.status || antes == null && r.status == RunStatus.EXECUTANDO) return@forEach
                    runCatching { avisar(r) }
                }
            }
        }
    }

    private suspend fun avisar(r: com.licitaia.feature.live.automation.RobotRun) {
        val qual = if (r.kind == RobotKind.LANCE) "Robô de lance" else "Robô de proposta"
        val (tipo, titulo, msg) = when (r.status) {
            RunStatus.AGUARDANDO_USUARIO, RunStatus.PAUSADO ->
                Triple("alerta", "$qual do celular esperando você", "${r.title}: ${r.message ?: r.step}. Abra o app para continuar.")
            RunStatus.CONCLUIDO -> {
                val plan = if (r.kind == RobotKind.PROPOSTA) roboRepo.getPlan(r.companyId, r.tenderKey) else null
                if (r.kind == RobotKind.PROPOSTA && plan?.proposalStatus == RobotProposalStatus.CADASTRADA)
                    Triple("sucesso", "Proposta cadastrada pelo celular", "${r.title}: ${r.message ?: r.log.lastOrNull().orEmpty()}")
                else Triple("info", "$qual do celular terminou", "${r.title}: ${r.message ?: r.log.lastOrNull().orEmpty()}")
            }
            RunStatus.FALHOU -> Triple("erro", "$qual do celular falhou", "${r.title}: ${r.message ?: r.log.lastOrNull().orEmpty()}")
            else -> return
        }
        vps("POST", "/api/notificacoes/evento", JSONObject().put("origem", "celular").put("tipo", tipo)
            .put("titulo", titulo).put("mensagem", msg.take(900)).toString())
    }

    /** Alertas internos do NAVEGADOR DA VPS (login gov.br, primary/standby, redundância, captcha noVNC): no app quem
     *  trabalha é o robô do celular; esses alertas só confundem (mesma regra do programa do PC). */
    private fun daVps(n: JSONObject): Boolean {
        val canal = n.optString("canal")
        val txt = n.optString("titulo") + " " + n.optString("mensagem")
        return canal.contains("captcha", true) || canal.startsWith("browser-ha") ||
            Regex("autenticac[aã]o necess[aá]ria|primary|standby|failover|novnc|redund[aâ]ncia|navegador (esta|está)|browser-0", RegexOption.IGNORE_CASE).containsMatchIn(txt)
    }

    fun handles(method: String, path: String): Boolean {
        val p = path.substringBefore('?')
        return ROTAS.any { (m, re) -> (m == "*" || m == method) && re.matches(p) }
    }

    suspend fun handle(method: String, fullPath: String, body: String): Resp = withContext(Dispatchers.IO) {
        val path = fullPath.substringBefore('?')
        runCatching { route(method, path, fullPath, body) }.getOrElse { e ->
            Resp(500, obj("error" to (e.message ?: "falhou no celular")))
        }
    }

    private suspend fun route(m: String, p: String, full: String, body: String): Resp {
        garantirAvisos()
        // ---- sino: sem os alertas do navegador da VPS; o número é o da lista que aparece ----
        if (m == "GET" && p == "/api/notificacoes") {
            val (st, txt) = vps("GET", full)
            val d = runCatching { JSONObject(txt) }.getOrNull() ?: return Resp(st, txt)
            val lista = d.optJSONArray("data") ?: return Resp(st, txt)
            val fica = JSONArray()
            for (i in 0 until lista.length()) lista.optJSONObject(i)?.let { if (!daVps(it)) fica.put(it) }
            d.put("data", fica)
            d.put("naoLidas", (0 until fica.length()).count { !fica.getJSONObject(it).optBoolean("lida", false) })
            return Resp(st, d.toString())
        }
        // ---- telas nativas e respostas da empresa (cartões que o app coloca no site) ----
        if (p == "/__app/declaracoes") {
            if (m == "POST") salvarDeclaracoes(JSONObject(body.ifBlank { "{}" }))
            if (m == "DELETE") prefs.edit().remove(K_DECL).apply()
            return Resp(200, declaracoesJson().toString())
        }
        if (p.startsWith("/__app/abrir/")) {
            val rota = when (p.removePrefix("/__app/abrir/")) {
                "ia" -> Routes.AI_SETTINGS
                "portais", "certificado" -> Routes.PORTALS
                else -> Routes.portalWeb(Portal.COMPRAS_GOV)
            }
            return Resp(200, obj("ok" to true), abrir = rota)
        }
        if (p == "/__app/status") return Resp(200, statusCelular().toString())

        // ---- Registrar Proposta → robô de proposta do celular ----
        if (m == "POST" && p == "/api/executor/registrar-proposta") return registrarProposta(JSONObject(body.ifBlank { "{}" }))
        if (m == "GET" && p.startsWith("/api/executor/jobs/cel-")) return Resp(200, jobJson(p.removePrefix("/api/executor/jobs/")).toString())
        if (m == "POST" && p.endsWith("/registration-eligibility/refresh")) {
            return Resp(200, obj("ok" to true, "origem" to "celular", "motivo" to "a compra e o prazo são conferidos pelo robô do celular ao registrar"))
        }
        if (m == "GET" && p.endsWith("/preparacao-registro")) {
            val (st, txt) = vps("GET", full)
            if (st !in 200..299) return Resp(st, txt)
            val oe = runCatching { JSONObject(txt) }.getOrNull() ?: return Resp(st, txt)
            val lib = oe.optJSONObject("liberacao") ?: JSONObject()
            lib.put("botaoLiberado", true).put("situacao", "VALIDADO_CELULAR")
                .put("rotulo", "Conferido pelo robô do celular ao registrar")
                .put("motivo", "No app, o robô do celular confere a compra e o prazo no Compras.gov antes de preencher.")
            oe.put("liberacao", lib)
            if (oe.optBoolean("registrationDataReady", false)) oe.put("prontoParaRegistrar", true)
            if (oe.optString("situacaoOperacional") == "VERIFICANDO_PORTAL") oe.put("situacaoOperacional", "VALIDADO_CELULAR")
            if (oe.optString("status") == "WAITING_PORTAL_ELIGIBILITY") oe.put("status", "VALIDADO_CELULAR")
            return Resp(200, oe.toString())
        }

        // ---- "Ver a tela do robô": a tela do Comprasnet DESTE celular ----
        if (m == "POST" && p == "/api/vnc/session") {
            return Resp(409, obj("detail" to "no app a tela do robô é a deste celular: abri o Compras.gov aqui."), abrir = Routes.portalWeb(Portal.COMPRAS_GOV))
        }

        // ---- Configurações › Portais: sessão do Comprasnet neste celular ----
        if (m == "GET" && p == "/api/integracoes/status") {
            val (st, txt) = vps("GET", full)
            val d = runCatching { JSONObject(txt) }.getOrNull() ?: return Resp(st, txt)
            val lista = d.optJSONArray("portais") ?: JSONArray()
            val ok = sessaoOk()
            for (i in 0 until lista.length()) {
                val it = lista.optJSONObject(i) ?: continue
                val nome = (it.optString("portal") + " " + it.optString("nome")).lowercase()
                if ("comprasnet" in nome || "compras" in nome) {
                    it.put("status", if (ok) "conectado" else "desconectado")
                    it.put("observacao", if (ok) "" else "Sem sessão neste celular — toque em Abrir Login (entra sozinho com o certificado).")
                }
            }
            return Resp(st, d.toString())
        }
        if (m == "GET" && p == "/api/integracoes/comprasnet/estado-sessao") {
            val ok = sessaoOk()
            return Resp(200, obj("estado" to if (ok) "ok" else "falha", "mensagem" to textoSessao(ok), "origem" to "celular"))
        }
        Regex("^/api/integracoes/portal/([^/]+)/(verificar-sessao|abrir-login|fechar)$").find(p)?.let { mm ->
            val nome = mm.groupValues[1].lowercase()
            if (!("comprasnet" in nome || "compras" in nome)) return Resp(200, obj("ok" to false, "mensagem" to "Neste celular só o Compras.gov está ligado ao robô."))
            return when (mm.groupValues[2]) {
                "abrir-login" -> Resp(200, obj("mensagem" to "Abri o Compras.gov neste celular: ele entra sozinho com o certificado."), abrir = Routes.portalWeb(Portal.COMPRAS_GOV))
                "fechar" -> { company()?.let { portals.clearWebSession(it.id, Portal.COMPRAS_GOV) }; Resp(200, obj("ok" to true, "mensagem" to "Sessão encerrada neste celular.")) }
                else -> { val ok = sessaoOk(); Resp(200, obj("ok" to ok, "mensagem" to textoSessao(ok))) }
            }
        }

        // ---- Robô de Registro / Navegadores / Logs: os robôs DESTE celular ----
        if (m == "GET" && p == "/api/robo-registro/status") return Resp(200, statusRoboRegistro().toString())
        if (m == "POST" && p == "/api/robo-registro/religar-vpn") return Resp(200, obj("ok" to false, "error" to "No app não há VPN: os robôs usam a internet deste celular."))
        if (m == "GET" && p == "/api/robo-registro/navegadores") return Resp(200, navegadores().toString())
        if (m == "GET" && p == "/api/logs/robo") return Resp(200, logs().toString())

        // ---- Certificado: fica no Android deste celular ----
        if (p == "/api/empresa/certificado") {
            if (m == "GET") return Resp(200, certificadoJson().toString())
            return Resp(400, obj("error" to "No celular o certificado A1 fica no próprio Android (Configurações › Segurança › Instalar certificado). Abri a tela de Portais do app com o passo a passo."), abrir = Routes.PORTALS)
        }

        // ---- IA: provedores e chaves só neste celular ----
        if (p.startsWith("/api/ia/provedores")) {
            if (m == "GET" && p == "/api/ia/provedores") return Resp(200, provedoresJson().toString())
            return Resp(400, obj("error" to "No celular a IA é configurada no próprio app (a chave nunca vai para a VPS). Abri a tela de IA do celular."), abrir = Routes.AI_SETTINGS)
        }
        return Resp(404, obj("error" to "rota não atendida no celular"))
    }

    // ================================================================== robô de proposta
    private suspend fun registrarProposta(b: JSONObject): Resp {
        fun recusa(msg: String) = Resp(409, obj("error" to "robo_incompleto", "detail" to msg,
            "blockingReasons" to JSONArray().put(JSONObject().put("nome", "Robô do celular").put("motivo", msg))))
        if (!b.optBoolean("termoDeclaracoesAutorizado", false)) return recusa("Marque a autorização do Termo de Aceitação antes de soltar o robô.")
        val company = company() ?: return recusa("Entre com a conta da plataforma.")
        val licId = b.optString("licitacaoId")
        val dto = repository.tenderDetail(licId).getOrElse { return recusa("Não consegui ler a licitação na VPS: ${it.message}") }
        val key = SiteCompra.key(dto) ?: return recusa("Robô de proposta só para compras do Comprasnet com UASG/número/ano reconhecíveis.")
        val decl = declaracoes() ?: return recusa("Defina as declarações da empresa (ME/EPP, equidade, integridade) em Configurações › Robô de Registro, neste celular.")
        if (engine.runs.value.values.any { it.companyId == company.id && it.active }) return recusa("Já há um robô rodando neste celular. Pare-o antes: um robô por vez.")
        val itens = repository.tenderItens(licId).getOrElse { return recusa("Não consegui ler os itens: ${it.message}") }.filter { it.numero != null }
        if (itens.isEmpty()) return recusa("Esta licitação não tem itens cadastrados.")
        val semPreco = itens.filter { (it.valorProposto?.toDoubleOrNull() ?: 0.0) <= 0 }.map { it.numero }
        if (semPreco.isNotEmpty()) return recusa("itens sem preço salvo: ${semPreco.joinToString(", ")}. Salve os preços antes de registrar.")
        val plano = itens.sortedBy { it.numero }.map { pi ->
            val preco = (pi.valorProposto!!.toDouble() * 100).roundToLong() / 100.0 // o robô digita sempre 2 casas
            ProposalItemPlan(
                itemNumber = pi.numero!!, description = pi.descricao.orEmpty(),
                quantity = pi.quantidade?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0,
                unitPrice = preco, brand = pi.marca.orEmpty(), modelVersion = pi.modelo.orEmpty(),
                floorUnitPrice = pi.valorLanceMinimo?.toDoubleOrNull()?.takeIf { it > 0 }, selected = true,
            )
        }
        // trava do PC: preço muito acima do estimado do órgão quase sempre é erro de conversão/digitação
        val absurdo = itens.mapNotNull { pi ->
            val est = pi.valor?.toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val pr = pi.valorProposto?.toDoubleOrNull() ?: return@mapNotNull null
            if (pr > est * 1.5) "${pi.numero}" else null
        }
        if (absurdo.isNotEmpty()) return recusa("preço salvo muito acima do valor estimado do órgão no(s) item(ns) ${absurdo.joinToString(", ")}. Confira o preço no site.")

        val now = System.currentTimeMillis()
        val ref = SiteCompra.ref(dto)
        roboRepo.upsertMyTenders(company.id, listOf(PortalMyTender(
            companyId = company.id, tenderKey = key, portal = Portal.COMPRAS_GOV,
            uasg = ref.uasg.orEmpty(), number = ref.number ?: "", year = ref.year ?: 0,
            modality = dto.modalidade ?: "", objectDescription = dto.objeto.ifBlank { dto.orgao },
            openingAt = parseIso(dto.dataAbertura).takeIf { it > 0 }, situation = dto.fase ?: "",
            hasProposal = !dto.urlProposta.isNullOrBlank(), sources = setOf(PortalMyTender.SOURCE_PARTICIPACOES),
            firstSeenAt = now, updatedAt = now,
        )))
        if (ref.number != null && ref.year != null) robotSync.register(company.id, "${ref.number}/${ref.year}", licId, key)
        val atual = roboRepo.getPlan(company.id, key) ?: PortalRobotPlan(companyId = company.id, tenderKey = key)
        roboRepo.savePlan(atual.copy(items = plano, sessionAt = parseIso(dto.dataAbertura).takeIf { it > 0 } ?: atual.sessionAt, updatedAt = now))
        val usuario = (repository.session.value as? PlatformSession.SignedIn)?.user?.nome?.takeIf { it.isNotBlank() } ?: "Operador"
        val auth = ProposalAuthorization(acceptTerms = true, declarations = decl, authorizedBy = usuario, authorizedAt = now)
        val runId = engine.startProposal(company.id, key, auth, updateDifferent = true).getOrElse { return recusa(it.message ?: "não consegui soltar o robô") }
        return Resp(200, obj("jobId" to "cel-$runId"), abrir = Routes.portalWeb(Portal.COMPRAS_GOV))
    }

    /** Acompanhamento no formato do GET /executor/jobs/:id da VPS (o site consulta a cada 2,5 s). */
    private suspend fun jobJson(jobId: String): JSONObject {
        val run = engine.runs.value[jobId.removePrefix("cel-")]
            ?: return JSONObject().put("id", jobId).put("estado", "UNKNOWN").put("erro", "o robô do celular não tem mais esse envio (o app foi reiniciado?)")
        if (run.active) {
            val espera = run.status != RunStatus.EXECUTANDO
            return JSONObject().put("id", jobId).put("estado", if (espera) "AGUARDANDO_VOCE_NO_CELULAR" else "EXECUTANDO_NO_CELULAR")
                .put("resultado", JSONObject().put("parcial", if (espera) "Robô do celular aguardando você na tela do portal: ${run.message ?: run.step}" else "Robô do celular: ${run.step}"))
        }
        val plan = roboRepo.getPlan(run.companyId, run.tenderKey)
        val ok = run.status == RunStatus.CONCLUIDO && plan?.proposalStatus == RobotProposalStatus.CADASTRADA
        val fim = Instant.ofEpochMilli(run.updatedAt).toString()
        val msg = run.message ?: run.log.lastOrNull().orEmpty()
        return if (ok) JSONObject().put("id", jobId).put("estado", "CONCLUIDO").put("concluidoEm", fim)
            .put("resultado", JSONObject().put("mensagem", "CONFERIDO item-a-item OK (robô do celular). $msg"))
        else JSONObject().put("id", jobId).put("estado", "CONCLUIDO").put("concluidoEm", fim).put("status", "falha")
            .put("erro", "Robô do celular: ${msg.ifBlank { run.status.label }}").put("resultado", JSONObject().put("incompleta", true).put("mensagem", msg))
    }

    // ================================================================== estado do celular
    private fun company(): Company? =
        (repository.session.value as? PlatformSession.SignedIn)?.user?.let { PlatformIdentity.session(it).activeCompany }

    private suspend fun sessaoOk(): Boolean {
        val c = company() ?: return false
        if (runCatching { gate.isLoggedNow(c.id) }.getOrDefault(false)) return true
        val s = withTimeoutOrNull(3000) { portals.observeSessions(c.id).first() }.orEmpty()
        return s.any { it.portal == Portal.COMPRAS_GOV && it.status == PortalConnectionStatus.CONECTADO }
    }

    private fun textoSessao(ok: Boolean) =
        if (ok) "Conectado neste celular (certificado A1 daqui)" else "Sem sessão neste celular — toque em Abrir Login (entra sozinho com o certificado)"

    private suspend fun temCertificado(): Boolean {
        val c = company() ?: return false
        return runCatching { holder.hasRememberedCertificate(c.id, Portal.COMPRAS_GOV) }.getOrDefault(false)
    }

    private fun certificadoJsonSync(tem: Boolean) =
        if (tem) JSONObject().put("instalado", true).put("origem", "celular").put("nomeArquivo", "Certificado A1 escolhido neste celular (fica no Android)").put("atualizadoEm", JSONObject.NULL)
        else JSONObject().put("instalado", false).put("origem", "celular").put("disponiveis", 0)

    private suspend fun certificadoJson() = certificadoJsonSync(temCertificado())

    private suspend fun iaAtiva(): Pair<Boolean, String> {
        val cfgs = withTimeoutOrNull(3000) { ai.observeConfigs().first() }.orEmpty()
        val ativo = withTimeoutOrNull(3000) { ai.observeEffective().first() }
        // "IA Demonstração" (MOCK) = nenhuma IA de verdade configurada
        val cfg = cfgs.firstOrNull { it.provider == ativo && it.isConfigured && it.provider.name != "MOCK" }
        return (cfg != null) to (cfg?.provider?.label ?: "nenhuma")
    }

    private suspend fun provedoresJson(): JSONArray {
        val cfgs = withTimeoutOrNull(3000) { ai.observeConfigs().first() }.orEmpty().filter { it.isConfigured }
        val ativo = withTimeoutOrNull(3000) { ai.observeEffective().first() }
        val arr = JSONArray()
        cfgs.forEach { c ->
            arr.put(JSONObject().put("id", c.provider.name).put("nome", c.provider.label + " (neste celular)")
                .put("tipo", "celular").put("baseURL", c.baseUrl).put("modelo", c.model).put("modeloFallback", "")
                .put("ativo", c.provider == ativo).put("apiKeyMascarada", if (c.hasApiKey) "•••• no celular" else (c.oauthAccount ?: "conta")).put("origem", "celular"))
        }
        return arr
    }

    private fun roboOcupado(): String? {
        val c = company() ?: return null
        val r = engine.runs.value.values.firstOrNull { it.companyId == c.id && it.active } ?: return null
        return when (r.kind) { RobotKind.PROPOSTA -> "robô de proposta rodando"; RobotKind.LANCE -> "robô de lance numa sala"; else -> "robô lendo o portal" }
    }

    private suspend fun statusRoboRegistro(): JSONObject {
        val ok = sessaoOk(); val cert = temCertificado(); val (iaOk, iaNome) = iaAtiva(); val ocupado = roboOcupado()
        fun peca(id: String, nome: String, okk: Boolean, det: String, como: String? = null, indet: Boolean = false) =
            JSONObject().put("id", id).put("nome", nome).put("ok", okk).put("detalhe", det).put("indeterminado", indet).apply { if (como != null) put("comoResolver", como) }
        val decl = declaracoes()
        val pecas = JSONArray()
            .put(peca("certificado", "Certificado A1 (Android deste celular)", cert, if (cert) "certificado escolhido para o Compras.gov" else "nenhum certificado escolhido ainda",
                "Instale o .pfx no Android (Configurações › Segurança) e entre uma vez no Compras.gov pelo app para escolhê-lo."))
            .put(peca("sessao", "Sessão do Compras.gov neste celular", ok, textoSessao(ok), "Em Portais e Integrações, toque em Abrir Login. O robô também entra sozinho quando começa."))
            .put(peca("declaracoes", "Declarações da empresa (neste celular)", decl != null, decl?.summary() ?: "não definidas", "Defina no cartão “Declarações da empresa” desta aba."))
            .put(peca("navegador", "Navegador do robô (app LicitaPRO)", true, "tela do portal deste celular"))
            .put(peca("ia", "IA do celular", iaOk, if (iaOk) "em uso: $iaNome" else "nenhuma IA configurada neste celular", "Em Inteligência Artificial, abra a IA do celular.", indet = !iaOk))
            .put(peca("robo", "Robôs do celular", true, ocupado?.let { "ocupado: $it" } ?: "livres (um robô por vez)"))
        val bloqueio = JSONArray()
        if (!cert) bloqueio.put(JSONObject().put("nome", "Certificado A1").put("motivo", "nenhum certificado escolhido neste celular"))
        if (decl == null) bloqueio.put(JSONObject().put("nome", "Declarações da empresa").put("motivo", "defina ME/EPP, equidade e integridade nesta aba"))
        return JSONObject().put("origem", "celular").put("serviceAlive", true).put("browserReadiness", "READY").put("pecas", pecas)
            .put("sessaoComprasnet", if (ok) "ativa" else "precisa_login").put("vnc", JSONObject().put("url", "celular"))
            .put("registrationRobotReady", JSONObject().put("ready", bloqueio.length() == 0).put("blockingReasons", bloqueio).put("indeterminateCount", 0))
    }

    private suspend fun navegadores(): JSONObject {
        val ok = sessaoOk()
        val u = (repository.session.value as? PlatformSession.SignedIn)?.user
        val cnpj = u?.empresa?.cnpj?.filter(Char::isDigit)?.takeIf { it.length == 14 }
            ?.replace(Regex("^(\\d{2})(\\d{3})(\\d{3})(\\d{4})(\\d{2})$"), "$1.$2.$3/$4-$5")
        val b = JSONObject().put("browserId", "este celular").put("role", "PRIMARY")
            .put("sessionState", if (ok) "SESSION_OK" else "SUPPLIER_LOGIN_REQUIRED").put("precisaLogin", !ok).put("usable", true)
            .put("empresa", u?.empresa?.razaoSocial ?: "").put("cnpj", cnpj ?: JSONObject.NULL)
            .put("frescor", JSONObject().put("rotulo", if (ok) "certificado A1 · sessão deste celular" else "sem sessão"))
            .put("display", "tela visível neste celular (app LicitaPRO)").put("status", textoSessao(ok))
            .put("vnc", JSONObject().put("disponivel", true).put("url", "/vnc/primary/vnc.html"))
        return JSONObject().put("origem", "celular").put("dadosFrescos", true).put("haOk", ok)
            .put("haResumo", if (ok) "Navegador do celular com sessão ativa no Compras.gov" else "Navegador do celular sem sessão: toque em Abrir Login (Portais)")
            .put("financeiro", JSONObject().put("podeEnviarLanceReal", false)).put("browsers", JSONArray().put(b))
    }

    private suspend fun logs(): JSONObject {
        val c = company()
        val runs = engine.runs.value.values.filter { c != null && it.companyId == c.id }.sortedBy { it.startedAt }
        val linhas = runs.flatMap { r -> r.log.map { r.kind to it } }.takeLast(300).reversed()
        val arr = JSONArray()
        linhas.forEach { (kind, t) ->
            val nivel = when {
                Regex("falhou|erro|não consegui|parou|indispon", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "erro"
                Regex("aguardando|precisa|captcha|tentando de novo", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "aviso"
                Regex("\\bOK\\b|cadastrad|salvo|conferid", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "sucesso"
                else -> "info"
            }
            arr.put(JSONObject().put("texto", t).put("categoria", if (kind == RobotKind.LANCE) "disputa" else "proposta").put("nivel", nivel).put("origem", "celular"))
        }
        return JSONObject().put("ok", true).put("origem", "celular").put("logs", arr)
            .put("status", JSONObject().put("sessaoAtiva", sessaoOk()).put("ultimoErroGrave", JSONObject.NULL))
    }

    private suspend fun statusCelular(): JSONObject {
        val (iaOk, iaNome) = iaAtiva()
        return JSONObject().put("sessao", sessaoOk()).put("certificado", temCertificado()).put("ia", iaOk).put("iaNome", iaNome)
            .put("declaracoes", declaracoesJson())
    }

    // ================================================================== declarações da empresa (neste celular)
    private fun declaracoes(): PortalDeclarations? {
        val j = runCatching { JSONObject(prefs.getString(K_DECL, null) ?: return null) }.getOrNull() ?: return null
        fun b(k: String): Boolean? = if (j.isNull(k) || !j.has(k)) null else j.optBoolean(k)
        return PortalDeclarations(b("meEpp"), b("genderEquity"), b("integrity")).takeIf { it.complete }
    }

    private fun declaracoesJson(): JSONObject {
        val raw = prefs.getString(K_DECL, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun salvarDeclaracoes(b: JSONObject) {
        fun v(k: String): Any = if (b.has(k) && !b.isNull(k)) b.optBoolean(k) else JSONObject.NULL
        prefs.edit().putString(K_DECL, JSONObject().put("meEpp", v("meEpp")).put("genderEquity", v("genderEquity"))
            .put("integrity", v("integrity")).put("at", System.currentTimeMillis()).toString()).apply()
    }

    // ================================================================== VPS (passa direto, com o login)
    private suspend fun vps(method: String, pathAndQuery: String, body: String? = null): Pair<Int, String> = withContext(Dispatchers.IO) {
        val token = repository.siteAuth()?.first ?: return@withContext 401 to obj("error" to "sem login")
        val base = PlatformConfig.DEFAULT_BASE_URL.removeSuffix("/").removeSuffix("/api")
        val c = (URL(base + pathAndQuery).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $token"); setRequestProperty("Accept", "application/json")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json; charset=utf-8") }
        }
        if (body != null) c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        try {
            val st = c.responseCode
            val txt = (if (st in 200..399) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            st to txt
        } finally { c.disconnect() }
    }

    private fun obj(vararg kv: Pair<String, Any?>): String {
        val o = JSONObject(); kv.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }; return o.toString()
    }

    private fun parseIso(iso: String?): Long =
        if (iso.isNullOrBlank()) 0L else runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

    private companion object { const val K_DECL = "declaracoesEmpresa" }
}

/** UASG/número/ano da compra (mesma regra da tela de licitação do app). */
internal object SiteCompra {
    data class Ref(val uasg: String?, val number: String?, val year: Int?)

    fun ref(dto: TenderDto): Ref {
        parseCompra(dto.urlProposta ?: dto.portalUrl)?.let { return it }
        val r = PortalTenderMatching.refOf(dto.id, dto.numero, dto.orgao)
        val ny = PortalTenderMatching.parseNumberYear(dto.numero) ?: PortalTenderMatching.parseNumberYear(dto.objeto)
            ?: (r.number?.let { n -> r.year?.let { y -> n to y } })
        val uasg = dto.uasg?.takeIf { it.any(Char::isDigit) } ?: r.uasg
        return Ref(uasg, ny?.first ?: r.number, ny?.second ?: r.year)
    }

    fun key(dto: TenderDto): String? = ref(dto).let { PortalTenderMatching.tenderKey(it.uasg, it.number, it.year) }

    private fun parseCompra(url: String?): Ref? {
        val c = Regex("""compra=(\d{17})""").find(url.orEmpty())?.groupValues?.get(1)
            ?: Regex("""(?<!\d)(\d{17})(?!\d)""").find(url.orEmpty())?.groupValues?.get(1) ?: return null
        val ano = c.substring(13, 17).toIntOrNull()?.takeIf { it in 1990..2100 } ?: return null
        return Ref(c.substring(0, 6), c.substring(8, 13).trimStart('0').ifEmpty { "0" }, ano)
    }
}
