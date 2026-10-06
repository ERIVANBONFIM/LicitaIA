package com.licitaia.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Never delete unknown/legacy data. Missing migration fails closed instead of resetting Room. */
object DatabaseMigrations {
    val FROM_1_TO_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Version 2 introduced external identity and legacy demo tagging.
            val columns = db.query("PRAGMA table_info(users)").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            if ("provider" !in columns) db.execSQL("ALTER TABLE users ADD COLUMN provider TEXT NOT NULL DEFAULT 'LOCAL'")
            if ("externalId" !in columns) db.execSQL("ALTER TABLE users ADD COLUMN externalId TEXT")
            if ("demo" !in columns) db.execSQL("ALTER TABLE users ADD COLUMN demo INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE users SET demo = 1 WHERE lower(email) IN ('demo@licitaia.app', 'demo@licitaia.com')")
        }
    }

    /** Versão 3: edital real (PDF/texto) por licitação. Só adiciona colunas; nenhum dado é alterado. */
    val FROM_2_TO_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = db.query("PRAGMA table_info(tenders)").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            if ("editalPdfPath" !in columns) db.execSQL("ALTER TABLE tenders ADD COLUMN editalPdfPath TEXT DEFAULT NULL")
            if ("editalTextPath" !in columns) db.execSQL("ALTER TABLE tenders ADD COLUMN editalTextPath TEXT DEFAULT NULL")
            if ("editalChars" !in columns) db.execSQL("ALTER TABLE tenders ADD COLUMN editalChars INTEGER NOT NULL DEFAULT 0")
            if ("editalPages" !in columns) db.execSQL("ALTER TABLE tenders ADD COLUMN editalPages INTEGER DEFAULT NULL")
            if ("editalScanned" !in columns) db.execSQL("ALTER TABLE tenders ADD COLUMN editalScanned INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** Versão 4: modo de autenticação dos provedores de IA (chave de API ou conta Google/OAuth). Só adiciona colunas. */
    val FROM_3_TO_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = db.query("PRAGMA table_info(ai_configs)").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            if ("authMode" !in columns) db.execSQL("ALTER TABLE ai_configs ADD COLUMN authMode TEXT NOT NULL DEFAULT 'API_KEY'")
            if ("oauthAccount" !in columns) db.execSQL("ALTER TABLE ai_configs ADD COLUMN oauthAccount TEXT DEFAULT NULL")
            if ("cloudProject" !in columns) db.execSQL("ALTER TABLE ai_configs ADD COLUMN cloudProject TEXT DEFAULT NULL")
        }
    }

    /** Todas as migrações incrementais, na ordem. */
    val ALL: Array<Migration> get() = arrayOf(FROM_1_TO_2, FROM_2_TO_3, FROM_3_TO_4)
}
