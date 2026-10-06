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

    /**
     * Versão 5: hash encadeado na auditoria (`prevHash`, `hash`). Só adiciona colunas com default vazio:
     * eventos antigos ficam sem hash e a verificação de integridade começa no primeiro evento encadeado.
     */
    val FROM_4_TO_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = db.query("PRAGMA table_info(audit_events)").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            if ("prevHash" !in columns) db.execSQL("ALTER TABLE audit_events ADD COLUMN prevHash TEXT NOT NULL DEFAULT ''")
            if ("hash" !in columns) db.execSQL("ALTER TABLE audit_events ADD COLUMN hash TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * Versão 6:
     * 1. `companies.demo` (empresa do espaço de demonstração isolado) — só adiciona coluna, default 0.
     * 2. `ai_configs` passa a ter chave primária (provider, companyId): a tabela é recriada copiando TODAS
     *    as linhas com `companyId = 0` ("padrão do aparelho"). Nenhuma configuração é perdida; as chaves no
     *    cofre continuam com o nome antigo, que é exatamente o nome do escopo 0.
     */
    val FROM_5_TO_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val companyColumns = db.query("PRAGMA table_info(companies)").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            if ("demo" !in companyColumns) db.execSQL("ALTER TABLE companies ADD COLUMN demo INTEGER NOT NULL DEFAULT 0")

            val aiColumns = db.query("PRAGMA table_info(ai_configs)").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            if ("companyId" in aiColumns) return
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `ai_configs_v6` (`provider` TEXT NOT NULL, `companyId` INTEGER NOT NULL DEFAULT 0, " +
                    "`model` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, `authMode` TEXT NOT NULL DEFAULT 'API_KEY', " +
                    "`oauthAccount` TEXT DEFAULT NULL, `cloudProject` TEXT DEFAULT NULL, PRIMARY KEY(`provider`, `companyId`))",
            )
            db.execSQL(
                "INSERT INTO `ai_configs_v6` (`provider`, `companyId`, `model`, `baseUrl`, `authMode`, `oauthAccount`, `cloudProject`) " +
                    "SELECT `provider`, 0, `model`, `baseUrl`, `authMode`, `oauthAccount`, `cloudProject` FROM `ai_configs`",
            )
            db.execSQL("DROP TABLE `ai_configs`")
            db.execSQL("ALTER TABLE `ai_configs_v6` RENAME TO `ai_configs`")
        }
    }

    /**
     * Versão 7: `opportunities.platformName` (plataforma de origem informada pelo PNCP em `usuarioNome`).
     * Só adiciona coluna anulável; o cache existente continua válido (fica sem nome de plataforma até a próxima busca).
     */
    val FROM_6_TO_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = db.query("PRAGMA table_info(opportunities)").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            if ("platformName" !in columns) db.execSQL("ALTER TABLE opportunities ADD COLUMN platformName TEXT DEFAULT NULL")
        }
    }

    /** Todas as migrações incrementais, na ordem. */
    val ALL: Array<Migration> get() = arrayOf(FROM_1_TO_2, FROM_2_TO_3, FROM_3_TO_4, FROM_4_TO_5, FROM_5_TO_6, FROM_6_TO_7)
}
