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

    /** DDL da tabela `relevance_scores` exatamente como o Room a gera (schemas/.../8.json). */
    internal const val CREATE_RELEVANCE_SCORES =
        "CREATE TABLE IF NOT EXISTS `relevance_scores` (`opportunityId` TEXT NOT NULL, `companyId` INTEGER NOT NULL, " +
            "`radarSignature` TEXT NOT NULL, `score` INTEGER NOT NULL, `reason` TEXT NOT NULL, `provider` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`opportunityId`, `companyId`, `radarSignature`))"

    /**
     * Versão 8: cache das notas de relevância por IA (`relevance_scores`). Só cria a tabela nova (IF NOT EXISTS);
     * nenhuma tabela existente é alterada.
     */
    val FROM_7_TO_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_RELEVANCE_SCORES)
        }
    }

    /**
     * Versão 9: dispensas sem disputa (contratação direta). `radars.showNoDispute` (padrão 0 = ocultas) e
     * `opportunities.noDispute` (marcação no cache offline, padrão 0). Só adiciona colunas; nenhum dado é alterado.
     */
    val FROM_8_TO_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val radarColumns = db.query("PRAGMA table_info(radars)").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            if ("showNoDispute" !in radarColumns) db.execSQL("ALTER TABLE radars ADD COLUMN showNoDispute INTEGER NOT NULL DEFAULT 0")
            val opportunityColumns = db.query("PRAGMA table_info(opportunities)").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            if ("noDispute" !in opportunityColumns) db.execSQL("ALTER TABLE opportunities ADD COLUMN noDispute INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** DDL das tabelas da v10 exatamente como o Room as gera (schemas/.../10.json). */
    internal const val CREATE_COMPRASGOV_ROWS =
        "CREATE TABLE IF NOT EXISTS `comprasgov_rows` (`id` TEXT NOT NULL, `modalityCode` INTEGER NOT NULL, `uf` TEXT NOT NULL, " +
            "`publishedAt` INTEGER NOT NULL, `proposalDeadline` INTEGER NOT NULL, `sessionAt` INTEGER NOT NULL, `number` TEXT NOT NULL, " +
            "`agency` TEXT NOT NULL, `objectDescription` TEXT NOT NULL, `modality` TEXT NOT NULL, `segment` TEXT NOT NULL, " +
            "`city` TEXT NOT NULL, `estimatedValue` REAL NOT NULL, `keywords` TEXT NOT NULL, `editalUrl` TEXT, " +
            "`noDispute` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, `proposalOpening` INTEGER, PRIMARY KEY(`id`))"
    internal const val CREATE_COMPRASGOV_ROWS_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_comprasgov_rows_modalityCode_uf_publishedAt` ON `comprasgov_rows` (`modalityCode`, `uf`, `publishedAt`)"
    internal const val CREATE_COMPRASGOV_SYNC =
        "CREATE TABLE IF NOT EXISTS `comprasgov_sync` (`modalityCode` INTEGER NOT NULL, `uf` TEXT NOT NULL, " +
            "`lastFullSyncAt` INTEGER NOT NULL, `lastSyncAt` INTEGER NOT NULL, `truncated` INTEGER NOT NULL, " +
            "PRIMARY KEY(`modalityCode`, `uf`))"

    /**
     * Versão 10: cache persistente do Compras.gov.br (`comprasgov_rows`) e marcas de sincronização por modalidade/UF
     * (`comprasgov_sync`), criadas com IF NOT EXISTS e fora do backup; e `opportunities.proposalOpening` (início do
     * recebimento de propostas, anulável). Nenhum dado existente é alterado ou apagado.
     */
    val FROM_9_TO_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_COMPRASGOV_ROWS)
            db.execSQL(CREATE_COMPRASGOV_ROWS_INDEX)
            db.execSQL(CREATE_COMPRASGOV_SYNC)
            val columns = db.query("PRAGMA table_info(opportunities)").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            if ("proposalOpening" !in columns) db.execSQL("ALTER TABLE opportunities ADD COLUMN proposalOpening INTEGER DEFAULT NULL")
        }
    }

    /** DDL das tabelas da v11 exatamente como o Room as gera (schemas/.../11.json). */
    internal const val CREATE_PORTAL_MY_TENDERS =
        "CREATE TABLE IF NOT EXISTS `portal_my_tenders` (`companyId` INTEGER NOT NULL, `tenderKey` TEXT NOT NULL, `portal` TEXT NOT NULL, " +
            "`uasg` TEXT NOT NULL, `number` TEXT NOT NULL, `year` INTEGER NOT NULL, `modality` TEXT NOT NULL, `objectDescription` TEXT NOT NULL, " +
            "`openingAt` INTEGER, `situation` TEXT NOT NULL, `hasProposal` INTEGER NOT NULL, `sources` TEXT NOT NULL, `pncpControl` TEXT, " +
            "`matchedOpportunityId` TEXT, `matchedTenderId` INTEGER, `firstSeenAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`companyId`, `tenderKey`))"
    internal const val CREATE_PORTAL_MY_TENDERS_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_portal_my_tenders_companyId` ON `portal_my_tenders` (`companyId`)"
    internal const val CREATE_PORTAL_ROBOT_PLANS =
        "CREATE TABLE IF NOT EXISTS `portal_robot_plans` (`companyId` INTEGER NOT NULL, `tenderKey` TEXT NOT NULL, `itemsJson` TEXT NOT NULL, " +
            "`proposalStatus` TEXT NOT NULL, `proposalLogJson` TEXT NOT NULL, `bidJson` TEXT NOT NULL, `bidArmedAt` INTEGER, `sessionAt` INTEGER, " +
            "`liveSessionId` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`companyId`, `tenderKey`))"
    internal const val CREATE_PORTAL_ROBOT_PLANS_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_portal_robot_plans_companyId` ON `portal_robot_plans` (`companyId`)"

    /**
     * Versão 11: "Minhas licitações" do Comprasnet (`portal_my_tenders`) e planos do robô de proposta/lance
     * (`portal_robot_plans`). Só cria tabelas novas (IF NOT EXISTS); nenhuma tabela existente é alterada ou apagada.
     */
    val FROM_10_TO_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_PORTAL_MY_TENDERS)
            db.execSQL(CREATE_PORTAL_MY_TENDERS_INDEX)
            db.execSQL(CREATE_PORTAL_ROBOT_PLANS)
            db.execSQL(CREATE_PORTAL_ROBOT_PLANS_INDEX)
        }
    }

    /** Colunas da v12 em `companies` (dados da proposta em PDF), na ordem da entidade. */
    internal val COMPANY_PROPOSAL_COLUMNS = listOf(
        "street", "complement", "district", "zipCode", "phone", "email",
        "legalRepName", "legalRepCpf", "legalRepRole", "bankName", "bankAgency", "bankAccount",
    )

    /**
     * Versão 12: endereço, contato, representante legal e dados bancários da empresa (`companies`), usados na proposta
     * comercial em PDF. Só adiciona colunas TEXT NOT NULL DEFAULT '' (idempotente); nenhum dado é alterado ou apagado.
     */
    val FROM_11_TO_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = db.query("PRAGMA table_info(companies)").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            COMPANY_PROPOSAL_COLUMNS.filter { it !in columns }.forEach { column ->
                db.execSQL("ALTER TABLE companies ADD COLUMN $column TEXT NOT NULL DEFAULT ''")
            }
        }
    }

    /** DDL da tabela da v13 exatamente como o Room a gera (schemas/.../13.json). */
    internal const val CREATE_EDITAL_QUESTIONS =
        "CREATE TABLE IF NOT EXISTS `edital_questions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `companyId` INTEGER NOT NULL, " +
            "`tenderId` INTEGER NOT NULL, `question` TEXT NOT NULL, `answer` TEXT NOT NULL, `provider` TEXT NOT NULL, `model` TEXT, " +
            "`sources` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
            "FOREIGN KEY(`tenderId`) REFERENCES `tenders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
    internal const val CREATE_EDITAL_QUESTIONS_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_edital_questions_tenderId` ON `edital_questions` (`tenderId`)"

    /**
     * Versão 13: histórico do "Pergunte ao edital" (`edital_questions`, FK para `tenders` com ON DELETE CASCADE).
     * Só cria a tabela nova e o índice (IF NOT EXISTS); nenhuma tabela existente é alterada ou apagada.
     */
    val FROM_12_TO_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_EDITAL_QUESTIONS)
            db.execSQL(CREATE_EDITAL_QUESTIONS_INDEX)
        }
    }

    /** Todas as migrações incrementais, na ordem. */
    val ALL: Array<Migration> get() = arrayOf(
        FROM_1_TO_2, FROM_2_TO_3, FROM_3_TO_4, FROM_4_TO_5, FROM_5_TO_6, FROM_6_TO_7, FROM_7_TO_8, FROM_8_TO_9, FROM_9_TO_10,
        FROM_10_TO_11, FROM_11_TO_12, FROM_12_TO_13,
    )
}
