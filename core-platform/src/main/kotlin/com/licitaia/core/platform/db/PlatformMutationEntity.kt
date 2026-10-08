package com.licitaia.core.platform.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Ação feita offline, aguardando envio à plataforma (fila FIFO). Estrutura pronta para crescer:
 * favoritar/ocultar/arquivar/fase/itens/config do robô (contrato §4.4). As chamadas são idempotentes
 * por estado final, então reenviar é seguro.
 */
@Entity(tableName = "platform_mutation")
data class PlatformMutationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Ex.: "FAVORITAR", "OCULTAR", "FASE". */
    val type: String,
    /** Id da licitação (ou outro recurso) alvo. */
    val targetId: String,
    /** Payload JSON opcional (ex.: `{"fase":"analise"}`). */
    val payload: String?,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)
