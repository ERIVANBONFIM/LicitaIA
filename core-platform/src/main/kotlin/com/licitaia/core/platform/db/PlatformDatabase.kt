package com.licitaia.core.platform.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Banco local da PLATAFORMA, separado do banco do app local (LicitaDatabase). Isolar evita migrações no
 * banco atual e mantém os dois modos convivendo sem risco. Guarda só a cópia de leitura e a fila offline.
 */
@Database(
    entities = [PlatformTenderEntity::class, PlatformMutationEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PlatformDatabase : RoomDatabase() {
    abstract fun tenderDao(): PlatformTenderDao
    abstract fun mutationDao(): PlatformMutationDao

    companion object {
        const val NAME = "licitapro_platform.db"
    }
}
