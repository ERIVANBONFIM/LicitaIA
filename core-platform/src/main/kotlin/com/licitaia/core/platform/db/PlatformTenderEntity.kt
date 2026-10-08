package com.licitaia.core.platform.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Cópia local (espelho) de uma licitação vinda da plataforma. Banco SEPARADO do app local. */
@Entity(tableName = "platform_tender")
data class PlatformTenderEntity(
    @PrimaryKey val id: String,
    val numero: String,
    val orgao: String,
    val objeto: String,
    val modalidade: String?,
    val valorEstimado: String?,
    val dataAbertura: String?,
    val dataEncerramento: String?,
    val portal: String?,
    val portalUrl: String?,
    val estado: String?,
    val cidade: String?,
    val fase: String?,
    val status: String?,
    val favorita: Boolean,
    val scoreRelevancia: Int?,
    /** ISO-8601 UTC vindo do servidor; chave da sincronização incremental aproximada. */
    val updatedAt: String?,
    val empresaId: String?,
    /** Quando este registro foi espelhado localmente (epoch ms). */
    val syncedAt: Long,
)
