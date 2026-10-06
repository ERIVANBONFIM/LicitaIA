package com.licitaia.core.data.seed
import javax.inject.Inject
import javax.inject.Singleton
/** Personal edition never inserts fictitious records or removes existing data. */
@Singleton
class DatabaseSeeder @Inject constructor() {
    suspend fun ensureSeeded() = Unit
}
