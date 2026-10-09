package com.licitaia.feature.platform

import android.content.Context
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.net.RoboEventoRequest
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.repository.MessageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sobe para a VPS o que o robô do APARELHO grava localmente durante a disputa, para a equipe acompanhar pelo site:
 * - mensagens do pregoeiro lidas no chat do portal → `POST /mensagens` (tipo "pregoeiro");
 * - lances (nosso, melhor do portal), posição e início/fim da disputa → `POST /licitacoes/:id/robo-eventos`.
 *
 * Só lê os repositórios locais (não mexe no robô). Cada item é enviado UMA vez (marcado após sucesso); o que falhar
 * (sem rede, rota ainda não instalada na VPS) é tentado de novo a cada minuto, sem perder nada.
 */
@Singleton
class PlatformRobotSync @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: PlatformRepository,
    private val messages: MessageRepository,
    private val live: LiveSessionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("platform_robot_sync", Context.MODE_PRIVATE)
    private val started = ConcurrentHashMap.newKeySet<Long>()
    private val sessionsWatched = ConcurrentHashMap.newKeySet<String>()
    private val lock = Mutex()

    /** Liga o número da compra no portal ("7/2026") à licitação da plataforma, e começa a sincronizar a empresa. */
    fun register(companyId: Long, tenderNumber: String, licitacaoId: String) {
        if (tenderNumber.isBlank() || licitacaoId.isBlank()) return
        prefs.edit().putString(mapKey(companyId, tenderNumber), licitacaoId).apply()
        ensureStarted(companyId)
    }

    /** Começa (uma vez por empresa) a observar mensagens e lances locais. */
    fun ensureStarted(companyId: Long) {
        if (!started.add(companyId)) return
        scope.launch {
            combine(messages.observeMessages(companyId), ticker()) { list, _ -> list }.collect { list ->
                list.sortedBy { it.receivedAt }.forEach { m ->
                    val lic = licitacaoDe(companyId, m.tenderNumber) ?: return@forEach
                    val k = "msg:$companyId:${m.id}"
                    if (m.id == 0L || jaEnviado(k)) return@forEach
                    repository.enviarMensagemPregoeiro(lic, m.body, m.sender.ifBlank { "Pregoeiro" }, m.receivedAt)
                        .onSuccess { marcar(k) }
                }
            }
        }
        scope.launch {
            live.sessions.collect { sessions ->
                sessions.filter { it.companyId == companyId }.forEach { s ->
                    val lic = licitacaoDe(companyId, s.tenderNumber) ?: return@forEach
                    if (!sessionsWatched.add(s.id)) return@forEach
                    scope.launch {
                        combine(live.observeEvents(s.id), ticker()) { ev, _ -> ev }.collect { events ->
                            events.sortedBy { it.timestamp }.forEach { e ->
                                val k = "ev:${s.id}:${e.id}"
                                if (e.id == 0L || jaEnviado(k)) return@forEach
                                val req = RoboEventoRequest(
                                    tipo = e.type.name, valor = e.value, ator = e.actor, descricao = e.description,
                                    item = s.itemLabel.takeIf { it.isNotBlank() }, momento = e.timestamp, idLocal = k,
                                )
                                repository.enviarRoboEvento(lic, req).onSuccess { marcar(k) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun mapKey(companyId: Long, tenderNumber: String) = "lic:$companyId:${normaliza(tenderNumber)}"

    /** "07/2026" e "7/2026" são a mesma compra. */
    private fun normaliza(n: String): String = n.trim().split("/").let { p ->
        if (p.size == 2) "${p[0].trimStart('0').ifEmpty { "0" }}/${p[1].trim()}" else n.trim()
    }

    private fun licitacaoDe(companyId: Long, tenderNumber: String): String? = prefs.getString(mapKey(companyId, tenderNumber), null)

    private fun jaEnviado(k: String): Boolean = prefs.getStringSet(SENT, emptySet())!!.contains(k)

    private suspend fun marcar(k: String) = lock.withLock {
        val atual = prefs.getStringSet(SENT, emptySet())!!.toMutableSet()
        atual.add(k)
        // Guarda só os mais recentes (o suficiente para não reenviar).
        val limitado = if (atual.size > MAX_SENT) atual.toList().takeLast(MAX_SENT).toSet() else atual
        prefs.edit().putStringSet(SENT, limitado).apply()
    }

    /** Re-tenta o que falhou a cada minuto. */
    private fun ticker(): Flow<Unit> = flow { while (true) { emit(Unit); delay(60_000) } }

    private companion object {
        const val SENT = "enviados"
        const val MAX_SENT = 5_000
    }
}
