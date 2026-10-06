package com.licitaia.domain.repository

interface BackupRepository {
    suspend fun exportBackup(destination: String, password: CharArray): Result<String>
    suspend fun restoreBackup(source: String, password: CharArray): Result<String>
}
