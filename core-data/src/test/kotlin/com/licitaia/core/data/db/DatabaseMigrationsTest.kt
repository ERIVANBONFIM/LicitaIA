package com.licitaia.core.data.db

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseMigrationsTest {
    @Test fun addsIdentityColumnsWithoutDeletingLegacyRows() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val cursor = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(users)") } returns cursor
        every { cursor.moveToNext() } returnsMany listOf(true, true, false)
        every { cursor.getColumnIndexOrThrow("name") } returns 0
        every { cursor.getString(0) } returnsMany listOf("id", "email")
        DatabaseMigrations.FROM_1_TO_2.migrate(db)
        verify { db.execSQL("ALTER TABLE users ADD COLUMN provider TEXT NOT NULL DEFAULT 'LOCAL'") }
        verify { db.execSQL("ALTER TABLE users ADD COLUMN externalId TEXT") }
        verify { db.execSQL("ALTER TABLE users ADD COLUMN demo INTEGER NOT NULL DEFAULT 0") }
        verify(exactly = 0) { db.execSQL(match { it.contains("DROP", true) || it.contains("DELETE", true) }) }
    }
    @Test fun knownColumnsAreNotAddedTwice() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val cursor = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(users)") } returns cursor
        every { cursor.moveToNext() } returnsMany listOf(true, true, true, false)
        every { cursor.getColumnIndexOrThrow("name") } returns 0
        every { cursor.getString(0) } returnsMany listOf("provider", "externalId", "demo")
        DatabaseMigrations.FROM_1_TO_2.migrate(db)
        verify(exactly = 0) { db.execSQL(match { it.startsWith("ALTER TABLE") }) }
    }

    @Test fun version3AddsEditalColumnsWithoutTouchingRows() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val cursor = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(tenders)") } returns cursor
        every { cursor.moveToNext() } returnsMany listOf(true, true, false)
        every { cursor.getColumnIndexOrThrow("name") } returns 0
        every { cursor.getString(0) } returnsMany listOf("id", "companyId")
        DatabaseMigrations.FROM_2_TO_3.migrate(db)
        verify { db.execSQL("ALTER TABLE tenders ADD COLUMN editalPdfPath TEXT DEFAULT NULL") }
        verify { db.execSQL("ALTER TABLE tenders ADD COLUMN editalTextPath TEXT DEFAULT NULL") }
        verify { db.execSQL("ALTER TABLE tenders ADD COLUMN editalChars INTEGER NOT NULL DEFAULT 0") }
        verify { db.execSQL("ALTER TABLE tenders ADD COLUMN editalPages INTEGER DEFAULT NULL") }
        verify { db.execSQL("ALTER TABLE tenders ADD COLUMN editalScanned INTEGER NOT NULL DEFAULT 0") }
        verify(exactly = 0) { db.execSQL(match { it.contains("DROP", true) || it.contains("DELETE", true) || it.contains("UPDATE", true) }) }
    }

    @Test fun version3IsIdempotentWhenColumnsExist() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val cursor = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(tenders)") } returns cursor
        every { cursor.moveToNext() } returnsMany listOf(true, true, true, true, true, false)
        every { cursor.getColumnIndexOrThrow("name") } returns 0
        every { cursor.getString(0) } returnsMany listOf("editalPdfPath", "editalTextPath", "editalChars", "editalPages", "editalScanned")
        DatabaseMigrations.FROM_2_TO_3.migrate(db)
        verify(exactly = 0) { db.execSQL(match { it.startsWith("ALTER TABLE") }) }
    }

    @Test fun version4AddsAiAuthColumnsWithoutTouchingRows() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val cursor = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(ai_configs)") } returns cursor
        every { cursor.moveToNext() } returnsMany listOf(true, true, true, false)
        every { cursor.getColumnIndexOrThrow("name") } returns 0
        every { cursor.getString(0) } returnsMany listOf("provider", "model", "baseUrl")
        DatabaseMigrations.FROM_3_TO_4.migrate(db)
        verify { db.execSQL("ALTER TABLE ai_configs ADD COLUMN authMode TEXT NOT NULL DEFAULT 'API_KEY'") }
        verify { db.execSQL("ALTER TABLE ai_configs ADD COLUMN oauthAccount TEXT DEFAULT NULL") }
        verify { db.execSQL("ALTER TABLE ai_configs ADD COLUMN cloudProject TEXT DEFAULT NULL") }
        verify(exactly = 0) { db.execSQL(match { it.contains("DROP", true) || it.contains("DELETE", true) || it.contains("UPDATE", true) }) }
    }

    @Test fun version4IsIdempotentWhenColumnsExist() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val cursor = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(ai_configs)") } returns cursor
        every { cursor.moveToNext() } returnsMany listOf(true, true, true, false)
        every { cursor.getColumnIndexOrThrow("name") } returns 0
        every { cursor.getString(0) } returnsMany listOf("authMode", "oauthAccount", "cloudProject")
        DatabaseMigrations.FROM_3_TO_4.migrate(db)
        verify(exactly = 0) { db.execSQL(match { it.startsWith("ALTER TABLE") }) }
    }

    @Test fun version5AddsAuditHashColumnsWithEmptyDefaultAndKeepsRows() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val cursor = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(audit_events)") } returns cursor
        every { cursor.moveToNext() } returnsMany listOf(true, true, true, false)
        every { cursor.getColumnIndexOrThrow("name") } returns 0
        every { cursor.getString(0) } returnsMany listOf("id", "timestamp", "details")
        DatabaseMigrations.FROM_4_TO_5.migrate(db)
        verify { db.execSQL("ALTER TABLE audit_events ADD COLUMN prevHash TEXT NOT NULL DEFAULT ''") }
        verify { db.execSQL("ALTER TABLE audit_events ADD COLUMN hash TEXT NOT NULL DEFAULT ''") }
        // Eventos antigos ficam sem hash: nada é recalculado, apagado ou reescrito.
        verify(exactly = 0) { db.execSQL(match { it.contains("DROP", true) || it.contains("DELETE", true) || it.contains("UPDATE", true) }) }
    }

    @Test fun version5IsIdempotentWhenColumnsExist() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val cursor = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(audit_events)") } returns cursor
        every { cursor.moveToNext() } returnsMany listOf(true, true, false)
        every { cursor.getColumnIndexOrThrow("name") } returns 0
        every { cursor.getString(0) } returnsMany listOf("prevHash", "hash")
        DatabaseMigrations.FROM_4_TO_5.migrate(db)
        verify(exactly = 0) { db.execSQL(match { it.startsWith("ALTER TABLE") }) }
    }

    @Test fun version6AddsCompanyDemoFlagAndRecreatesAiConfigsKeepingRows() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val companies = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(companies)") } returns companies
        every { companies.moveToNext() } returnsMany listOf(true, true, false)
        every { companies.getColumnIndexOrThrow("name") } returns 0
        every { companies.getString(0) } returnsMany listOf("id", "name")
        val ai = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(ai_configs)") } returns ai
        every { ai.moveToNext() } returnsMany listOf(true, true, true, false)
        every { ai.getColumnIndexOrThrow("name") } returns 0
        every { ai.getString(0) } returnsMany listOf("provider", "model", "baseUrl")
        val sql = mutableListOf<String>()
        every { db.execSQL(capture(sql)) } returns Unit

        DatabaseMigrations.FROM_5_TO_6.migrate(db)

        assertEquals("ALTER TABLE companies ADD COLUMN demo INTEGER NOT NULL DEFAULT 0", sql[0])
        // Recria ai_configs com chave (provider, companyId) copiando TODAS as linhas como padrão do aparelho (0).
        val create = sql[1]
        assertTrue(create.startsWith("CREATE TABLE IF NOT EXISTS `ai_configs_v6`"))
        assertTrue(create.contains("`companyId` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(create.contains("PRIMARY KEY(`provider`, `companyId`)"))
        val copy = sql[2]
        assertTrue(copy.startsWith("INSERT INTO `ai_configs_v6`"))
        assertTrue(copy.contains("SELECT `provider`, 0, `model`, `baseUrl`, `authMode`, `oauthAccount`, `cloudProject` FROM `ai_configs`"))
        assertEquals("DROP TABLE `ai_configs`", sql[3])
        assertEquals("ALTER TABLE `ai_configs_v6` RENAME TO `ai_configs`", sql[4])
        assertEquals(5, sql.size)
        // A cópia acontece ANTES do DROP: nenhuma linha se perde.
        assertTrue(sql.indexOfFirst { it.startsWith("INSERT") } < sql.indexOfFirst { it.startsWith("DROP") })
        // Nenhuma outra tabela é apagada nem alterada.
        assertTrue(sql.none { it.contains("DELETE", true) })
        assertTrue(sql.none { it.contains("DROP", true) && !it.contains("ai_configs") })
    }

    @Test fun version6IsIdempotentWhenAlreadyMigrated() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val companies = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(companies)") } returns companies
        every { companies.moveToNext() } returnsMany listOf(true, false)
        every { companies.getColumnIndexOrThrow("name") } returns 0
        every { companies.getString(0) } returns "demo"
        val ai = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(ai_configs)") } returns ai
        every { ai.moveToNext() } returnsMany listOf(true, true, false)
        every { ai.getColumnIndexOrThrow("name") } returns 0
        every { ai.getString(0) } returnsMany listOf("provider", "companyId")
        DatabaseMigrations.FROM_5_TO_6.migrate(db)
        verify(exactly = 0) { db.execSQL(any()) }
    }

    @Test fun version7AddsPlatformNameToOpportunityCacheOnly() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val cursor = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(opportunities)") } returns cursor
        every { cursor.moveToNext() } returnsMany listOf(true, true, false)
        every { cursor.getColumnIndexOrThrow("name") } returns 0
        every { cursor.getString(0) } returnsMany listOf("id", "portal")
        val sql = mutableListOf<String>()
        every { db.execSQL(capture(sql)) } returns Unit
        DatabaseMigrations.FROM_6_TO_7.migrate(db)
        assertEquals(listOf("ALTER TABLE opportunities ADD COLUMN platformName TEXT DEFAULT NULL"), sql)
    }

    @Test fun version7IsIdempotent() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val cursor = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(opportunities)") } returns cursor
        every { cursor.moveToNext() } returnsMany listOf(true, false)
        every { cursor.getColumnIndexOrThrow("name") } returns 0
        every { cursor.getString(0) } returns "platformName"
        DatabaseMigrations.FROM_6_TO_7.migrate(db)
        verify(exactly = 0) { db.execSQL(any()) }
    }

    @Test fun version8OnlyCreatesRelevanceScoreCache() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sql = mutableListOf<String>()
        every { db.execSQL(capture(sql)) } returns Unit
        DatabaseMigrations.FROM_7_TO_8.migrate(db)
        assertEquals(1, sql.size)
        val create = sql.single()
        assertTrue(create.startsWith("CREATE TABLE IF NOT EXISTS `relevance_scores`"))
        assertTrue(create.contains("PRIMARY KEY(`opportunityId`, `companyId`, `radarSignature`)"))
        listOf("`score` INTEGER NOT NULL", "`reason` TEXT NOT NULL", "`provider` TEXT NOT NULL", "`createdAt` INTEGER NOT NULL")
            .forEach { assertTrue(it, create.contains(it)) }
        assertTrue(sql.none { it.contains("DROP", true) || it.contains("DELETE", true) || it.contains("ALTER", true) })
    }

    /** A DDL da migração precisa ser idêntica à exportada pelo Room (senão a validação do schema falha ao abrir o banco). */
    @Test fun version8DdlMatchesExportedSchema() {
        val schema = listOf("schemas/com.licitaia.core.data.db.LicitaDatabase/8.json", "core-data/schemas/com.licitaia.core.data.db.LicitaDatabase/8.json")
            .map { java.io.File(it) }.firstOrNull { it.exists() } ?: return
        val expected = DatabaseMigrations.CREATE_RELEVANCE_SCORES.replace("relevance_scores", "\${TABLE_NAME}")
        assertTrue("8.json sem a DDL esperada", schema.readText().contains(expected.replace("\"", "\\\"")))
        assertTrue(schema.readText().contains("\"version\": 8"))
    }

    @Test fun version9AddsNoDisputeColumnsWithDefaultHidden() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val radars = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(radars)") } returns radars
        every { radars.moveToNext() } returnsMany listOf(true, true, false)
        every { radars.getColumnIndexOrThrow("name") } returns 0
        every { radars.getString(0) } returnsMany listOf("id", "requireLocalSupport")
        val opportunities = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(opportunities)") } returns opportunities
        every { opportunities.moveToNext() } returnsMany listOf(true, true, false)
        every { opportunities.getColumnIndexOrThrow("name") } returns 0
        every { opportunities.getString(0) } returnsMany listOf("id", "platformName")
        val sql = mutableListOf<String>()
        every { db.execSQL(capture(sql)) } returns Unit
        DatabaseMigrations.FROM_8_TO_9.migrate(db)
        assertEquals(
            listOf(
                "ALTER TABLE radars ADD COLUMN showNoDispute INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE opportunities ADD COLUMN noDispute INTEGER NOT NULL DEFAULT 0",
            ),
            sql,
        )
        assertEquals(8, DatabaseMigrations.FROM_8_TO_9.startVersion)
        assertEquals(9, DatabaseMigrations.FROM_8_TO_9.endVersion)
    }

    @Test fun version9IsIdempotent() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val radars = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(radars)") } returns radars
        every { radars.moveToNext() } returnsMany listOf(true, false)
        every { radars.getColumnIndexOrThrow("name") } returns 0
        every { radars.getString(0) } returns "showNoDispute"
        val opportunities = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(opportunities)") } returns opportunities
        every { opportunities.moveToNext() } returnsMany listOf(true, false)
        every { opportunities.getColumnIndexOrThrow("name") } returns 0
        every { opportunities.getString(0) } returns "noDispute"
        DatabaseMigrations.FROM_8_TO_9.migrate(db)
        verify(exactly = 0) { db.execSQL(any()) }
    }

    /** As colunas da v9 no schema exportado pelo Room têm o mesmo default da migração (senão a validação falha). */
    @Test fun version9SchemaMatchesMigration() {
        val schema = listOf("schemas/com.licitaia.core.data.db.LicitaDatabase/9.json", "core-data/schemas/com.licitaia.core.data.db.LicitaDatabase/9.json")
            .map { java.io.File(it) }.firstOrNull { it.exists() } ?: return
        val text = schema.readText()
        assertTrue(text.contains("\"version\": 9"))
        assertTrue(text.contains("`showNoDispute` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(text.contains("`noDispute` INTEGER NOT NULL DEFAULT 0"))
    }

    @Test fun version10OnlyCreatesComprasGovCacheTables() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val opportunities = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(opportunities)") } returns opportunities
        every { opportunities.moveToNext() } returnsMany listOf(true, true, false)
        every { opportunities.getColumnIndexOrThrow("name") } returns 0
        every { opportunities.getString(0) } returnsMany listOf("id", "noDispute")
        val sql = mutableListOf<String>()
        every { db.execSQL(capture(sql)) } returns Unit
        DatabaseMigrations.FROM_9_TO_10.migrate(db)
        assertEquals(9, DatabaseMigrations.FROM_9_TO_10.startVersion)
        assertEquals(10, DatabaseMigrations.FROM_9_TO_10.endVersion)
        assertEquals(4, sql.size)
        assertEquals("ALTER TABLE opportunities ADD COLUMN proposalOpening INTEGER DEFAULT NULL", sql[3])
        sql.removeAt(3)
        assertTrue(sql[0].startsWith("CREATE TABLE IF NOT EXISTS `comprasgov_rows`"))
        assertTrue(sql[0].contains("PRIMARY KEY(`id`)"))
        assertTrue(sql[1].startsWith("CREATE INDEX IF NOT EXISTS `index_comprasgov_rows_modalityCode_uf_publishedAt`"))
        assertTrue(sql[2].startsWith("CREATE TABLE IF NOT EXISTS `comprasgov_sync`"))
        assertTrue(sql[2].contains("PRIMARY KEY(`modalityCode`, `uf`)"))
        // Nenhuma tabela existente é tocada.
        assertTrue(sql.none { it.contains("DROP", true) || it.contains("DELETE", true) || it.contains("ALTER", true) || it.contains("UPDATE", true) })
    }

    /** A DDL da v10 precisa ser idêntica à exportada pelo Room (senão a validação do schema falha ao abrir o banco). */
    @Test fun version10DdlMatchesExportedSchema() {
        val schema = listOf("schemas/com.licitaia.core.data.db.LicitaDatabase/10.json", "core-data/schemas/com.licitaia.core.data.db.LicitaDatabase/10.json")
            .map { java.io.File(it) }.firstOrNull { it.exists() } ?: return
        val text = schema.readText()
        assertTrue(text.contains("\"version\": 10"))
        listOf(
            DatabaseMigrations.CREATE_COMPRASGOV_ROWS.replace("`comprasgov_rows`", "`\${TABLE_NAME}`"),
            DatabaseMigrations.CREATE_COMPRASGOV_ROWS_INDEX.replace("`comprasgov_rows`", "`\${TABLE_NAME}`"),
            DatabaseMigrations.CREATE_COMPRASGOV_SYNC.replace("`comprasgov_sync`", "`\${TABLE_NAME}`"),
        ).forEach { assertTrue("10.json sem: $it", text.contains(it)) }
        assertTrue(text.contains("`proposalOpening` INTEGER DEFAULT NULL"))
    }

    @Test fun version10IsIdempotentForOpportunityColumn() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val opportunities = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(opportunities)") } returns opportunities
        every { opportunities.moveToNext() } returnsMany listOf(true, false)
        every { opportunities.getColumnIndexOrThrow("name") } returns 0
        every { opportunities.getString(0) } returns "proposalOpening"
        val sql = mutableListOf<String>()
        every { db.execSQL(capture(sql)) } returns Unit
        DatabaseMigrations.FROM_9_TO_10.migrate(db)
        assertTrue(sql.none { it.startsWith("ALTER") })
    }

    @Test fun version11OnlyCreatesPortalRobotTables() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sql = mutableListOf<String>()
        every { db.execSQL(capture(sql)) } returns Unit
        DatabaseMigrations.FROM_10_TO_11.migrate(db)
        assertEquals(10, DatabaseMigrations.FROM_10_TO_11.startVersion)
        assertEquals(11, DatabaseMigrations.FROM_10_TO_11.endVersion)
        assertEquals(4, sql.size)
        assertTrue(sql[0].startsWith("CREATE TABLE IF NOT EXISTS `portal_my_tenders`"))
        assertTrue(sql[0].contains("PRIMARY KEY(`companyId`, `tenderKey`)"))
        assertTrue(sql[1].startsWith("CREATE INDEX IF NOT EXISTS `index_portal_my_tenders_companyId`"))
        assertTrue(sql[2].startsWith("CREATE TABLE IF NOT EXISTS `portal_robot_plans`"))
        assertTrue(sql[3].startsWith("CREATE INDEX IF NOT EXISTS `index_portal_robot_plans_companyId`"))
        // Incremental e não destrutiva: só CREATE (a coluna `updatedAt` não conta como UPDATE).
        assertTrue(sql.all { it.startsWith("CREATE ") })
        assertTrue(sql.none { s -> listOf("DROP", "DELETE", "ALTER", "UPDATE", "INSERT").any { Regex("\\b$it\\s", RegexOption.IGNORE_CASE).containsMatchIn(s) } })
    }

    /** A DDL da v11 precisa ser idêntica à exportada pelo Room (senão a validação do schema falha ao abrir o banco). */
    @Test fun version11DdlMatchesExportedSchema() {
        val schema = listOf("schemas/com.licitaia.core.data.db.LicitaDatabase/11.json", "core-data/schemas/com.licitaia.core.data.db.LicitaDatabase/11.json")
            .map { java.io.File(it) }.firstOrNull { it.exists() } ?: return
        val text = schema.readText()
        assertTrue(text.contains("\"version\": 11"))
        listOf(
            DatabaseMigrations.CREATE_PORTAL_MY_TENDERS.replace("`portal_my_tenders`", "`\${TABLE_NAME}`"),
            DatabaseMigrations.CREATE_PORTAL_MY_TENDERS_INDEX.replace("`portal_my_tenders`", "`\${TABLE_NAME}`"),
            DatabaseMigrations.CREATE_PORTAL_ROBOT_PLANS.replace("`portal_robot_plans`", "`\${TABLE_NAME}`"),
            DatabaseMigrations.CREATE_PORTAL_ROBOT_PLANS_INDEX.replace("`portal_robot_plans`", "`\${TABLE_NAME}`"),
        ).forEach { assertTrue("11.json sem: $it", text.contains(it)) }
    }

    @Test fun version12AddsCompanyProposalColumnsWithEmptyDefault() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val companies = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(companies)") } returns companies
        every { companies.moveToNext() } returnsMany listOf(true, true, true, false)
        every { companies.getColumnIndexOrThrow("name") } returns 0
        every { companies.getString(0) } returnsMany listOf("id", "name", "demo")
        val sql = mutableListOf<String>()
        every { db.execSQL(capture(sql)) } returns Unit
        DatabaseMigrations.FROM_11_TO_12.migrate(db)
        assertEquals(11, DatabaseMigrations.FROM_11_TO_12.startVersion)
        assertEquals(12, DatabaseMigrations.FROM_11_TO_12.endVersion)
        val expected = listOf(
            "street", "complement", "district", "zipCode", "phone", "email",
            "legalRepName", "legalRepCpf", "legalRepRole", "bankName", "bankAgency", "bankAccount",
        ).map { "ALTER TABLE companies ADD COLUMN $it TEXT NOT NULL DEFAULT ''" }
        assertEquals(expected, sql)
        // Incremental e não destrutiva: só ALTER TABLE ... ADD COLUMN.
        assertTrue(sql.none { s -> listOf("DROP", "DELETE", "UPDATE", "INSERT").any { Regex("\\b$it\\b", RegexOption.IGNORE_CASE).containsMatchIn(s) } })
    }

    @Test fun version12IsIdempotent() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val companies = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(companies)") } returns companies
        every { companies.moveToNext() } returnsMany List(12) { true } + false
        every { companies.getColumnIndexOrThrow("name") } returns 0
        every { companies.getString(0) } returnsMany DatabaseMigrations.COMPANY_PROPOSAL_COLUMNS
        DatabaseMigrations.FROM_11_TO_12.migrate(db)
        verify(exactly = 0) { db.execSQL(any()) }
    }

    /** As colunas da v12 no schema exportado pelo Room têm o mesmo default da migração (senão a validação falha). */
    @Test fun version12SchemaMatchesMigration() {
        val schema = listOf("schemas/com.licitaia.core.data.db.LicitaDatabase/12.json", "core-data/schemas/com.licitaia.core.data.db.LicitaDatabase/12.json")
            .map { java.io.File(it) }.firstOrNull { it.exists() } ?: return
        val text = schema.readText()
        assertTrue(text.contains("\"version\": 12"))
        DatabaseMigrations.COMPANY_PROPOSAL_COLUMNS.forEach { assertTrue("12.json sem: $it", text.contains("`$it` TEXT NOT NULL DEFAULT ''")) }
    }

    @Test fun version13OnlyCreatesEditalQuestionsTable() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sql = mutableListOf<String>()
        every { db.execSQL(capture(sql)) } returns Unit
        DatabaseMigrations.FROM_12_TO_13.migrate(db)
        assertEquals(12, DatabaseMigrations.FROM_12_TO_13.startVersion)
        assertEquals(13, DatabaseMigrations.FROM_12_TO_13.endVersion)
        assertEquals(2, sql.size)
        assertTrue(sql[0].startsWith("CREATE TABLE IF NOT EXISTS `edital_questions`"))
        assertTrue(sql[0].contains("FOREIGN KEY(`tenderId`) REFERENCES `tenders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE"))
        assertTrue(sql[1].startsWith("CREATE INDEX IF NOT EXISTS `index_edital_questions_tenderId`"))
        // Incremental e não destrutiva: só CREATE.
        assertTrue(sql.all { it.startsWith("CREATE ") })
        assertTrue(sql.none { s -> listOf("DROP", "DELETE FROM", "ALTER", "UPDATE `", "INSERT").any { s.contains(it, ignoreCase = true) } })
    }

    /** A DDL da v13 precisa ser idêntica à exportada pelo Room (senão a validação do schema falha ao abrir o banco). */
    @Test fun version13DdlMatchesExportedSchema() {
        val schema = listOf("schemas/com.licitaia.core.data.db.LicitaDatabase/13.json", "core-data/schemas/com.licitaia.core.data.db.LicitaDatabase/13.json")
            .map { java.io.File(it) }.firstOrNull { it.exists() } ?: return
        val text = schema.readText()
        assertTrue(text.contains("\"version\": 13"))
        listOf(
            DatabaseMigrations.CREATE_EDITAL_QUESTIONS.replace("`edital_questions`", "`\${TABLE_NAME}`"),
            DatabaseMigrations.CREATE_EDITAL_QUESTIONS_INDEX.replace("`edital_questions`", "`\${TABLE_NAME}`"),
        ).forEach { assertTrue("13.json sem: $it", text.contains(it)) }
    }

    @Test fun version14AddsNullableColumnsAndCreatesFlagTables() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val opportunities = mockk<Cursor>(relaxed = true)
        val tenders = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(opportunities)") } returns opportunities
        every { db.query("PRAGMA table_info(tenders)") } returns tenders
        every { opportunities.moveToNext() } returnsMany listOf(true, true, false)
        every { opportunities.getColumnIndexOrThrow("name") } returns 0
        every { opportunities.getString(0) } returnsMany listOf("id", "proposalOpening")
        every { tenders.moveToNext() } returnsMany listOf(true, false)
        every { tenders.getColumnIndexOrThrow("name") } returns 0
        every { tenders.getString(0) } returnsMany listOf("id")
        val sql = mutableListOf<String>()
        every { db.execSQL(capture(sql)) } returns Unit
        DatabaseMigrations.FROM_13_TO_14.migrate(db)
        assertEquals(13, DatabaseMigrations.FROM_13_TO_14.startVersion)
        assertEquals(14, DatabaseMigrations.FROM_13_TO_14.endVersion)
        assertEquals(
            listOf(
                "ALTER TABLE opportunities ADD COLUMN uasg TEXT DEFAULT NULL",
                "ALTER TABLE opportunities ADD COLUMN officialSituation TEXT DEFAULT NULL",
                "ALTER TABLE opportunities ADD COLUMN previousProposalDeadline INTEGER DEFAULT NULL",
                "ALTER TABLE tenders ADD COLUMN uasg TEXT DEFAULT NULL",
                "ALTER TABLE tenders ADD COLUMN officialSituation TEXT DEFAULT NULL",
            ),
            sql.take(5),
        )
        assertEquals(
            listOf(
                DatabaseMigrations.CREATE_OPPORTUNITY_FLAGS, DatabaseMigrations.CREATE_OPPORTUNITY_FLAGS_INDEX,
                DatabaseMigrations.CREATE_OPPORTUNITY_FIRST_SEEN, DatabaseMigrations.CREATE_TENDER_STATUS_WATCH,
                DatabaseMigrations.CREATE_TENDER_STATUS_WATCH_INDEX,
            ),
            sql.drop(5),
        )
        // Incremental e não destrutiva: só ALTER ... ADD COLUMN e CREATE ... IF NOT EXISTS.
        assertTrue(sql.none { s -> listOf("DROP", "DELETE", "UPDATE", "INSERT").any { Regex("\\b$it\\b", RegexOption.IGNORE_CASE).containsMatchIn(s) } })
    }

    @Test fun version14IsIdempotent() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        listOf("opportunities", "tenders").forEach { table ->
            val cursor = mockk<Cursor>(relaxed = true)
            val names = DatabaseMigrations.V14_COLUMNS.getValue(table).map { it.first }
            every { db.query("PRAGMA table_info($table)") } returns cursor
            every { cursor.moveToNext() } returnsMany List(names.size) { true } + false
            every { cursor.getColumnIndexOrThrow("name") } returns 0
            every { cursor.getString(0) } returnsMany names
        }
        val sql = mutableListOf<String>()
        every { db.execSQL(capture(sql)) } returns Unit
        DatabaseMigrations.FROM_13_TO_14.migrate(db)
        assertTrue(sql.none { it.startsWith("ALTER") })
        assertTrue(sql.all { it.contains("IF NOT EXISTS") })
    }

    /** A DDL da v14 precisa ser idêntica à exportada pelo Room. */
    @Test fun version14DdlMatchesExportedSchema() {
        val schema = listOf("schemas/com.licitaia.core.data.db.LicitaDatabase/14.json", "core-data/schemas/com.licitaia.core.data.db.LicitaDatabase/14.json")
            .map { java.io.File(it) }.firstOrNull { it.exists() } ?: return
        val text = schema.readText()
        assertTrue(text.contains("\"version\": 14"))
        listOf(
            DatabaseMigrations.CREATE_OPPORTUNITY_FLAGS.replace("`opportunity_flags`", "`\${TABLE_NAME}`"),
            DatabaseMigrations.CREATE_OPPORTUNITY_FLAGS_INDEX.replace("`opportunity_flags`", "`\${TABLE_NAME}`"),
            DatabaseMigrations.CREATE_OPPORTUNITY_FIRST_SEEN.replace("`opportunity_first_seen`", "`\${TABLE_NAME}`"),
            DatabaseMigrations.CREATE_TENDER_STATUS_WATCH.replace("`tender_status_watch`", "`\${TABLE_NAME}`"),
            DatabaseMigrations.CREATE_TENDER_STATUS_WATCH_INDEX.replace("`tender_status_watch`", "`\${TABLE_NAME}`"),
            "`uasg` TEXT DEFAULT NULL", "`officialSituation` TEXT DEFAULT NULL", "`previousProposalDeadline` INTEGER DEFAULT NULL",
        ).forEach { assertTrue("14.json sem: $it", text.contains(it)) }
    }

    @Test fun version15AddsCompanyDeclarationColumnsWithEmptyDefault() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val companies = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(companies)") } returns companies
        every { companies.moveToNext() } returnsMany listOf(true, true, false)
        every { companies.getColumnIndexOrThrow("name") } returns 0
        every { companies.getString(0) } returnsMany listOf("id", "bankAccount")
        val sql = mutableListOf<String>()
        every { db.execSQL(capture(sql)) } returns Unit
        DatabaseMigrations.FROM_14_TO_15.migrate(db)
        assertEquals(14, DatabaseMigrations.FROM_14_TO_15.startVersion)
        assertEquals(15, DatabaseMigrations.FROM_14_TO_15.endVersion)
        assertEquals(
            listOf("declMeEpp", "declGenderEquity", "declIntegrity").map { "ALTER TABLE companies ADD COLUMN $it TEXT NOT NULL DEFAULT ''" },
            sql,
        )
        assertTrue(sql.none { s -> listOf("DROP", "DELETE", "UPDATE", "INSERT").any { Regex("\\b$it\\b", RegexOption.IGNORE_CASE).containsMatchIn(s) } })
    }

    @Test fun version15IsIdempotent() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val companies = mockk<Cursor>(relaxed = true)
        every { db.query("PRAGMA table_info(companies)") } returns companies
        every { companies.moveToNext() } returnsMany List(3) { true } + false
        every { companies.getColumnIndexOrThrow("name") } returns 0
        every { companies.getString(0) } returnsMany DatabaseMigrations.COMPANY_DECLARATION_COLUMNS
        DatabaseMigrations.FROM_14_TO_15.migrate(db)
        verify(exactly = 0) { db.execSQL(any()) }
    }

    /** As colunas da v15 no schema exportado pelo Room têm o mesmo default da migração. */
    @Test fun version15SchemaMatchesMigration() {
        val schema = listOf("schemas/com.licitaia.core.data.db.LicitaDatabase/15.json", "core-data/schemas/com.licitaia.core.data.db.LicitaDatabase/15.json")
            .map { java.io.File(it) }.firstOrNull { it.exists() } ?: return
        val text = schema.readText()
        assertTrue(text.contains("\"version\": 15"))
        DatabaseMigrations.COMPANY_DECLARATION_COLUMNS.forEach { assertTrue("15.json sem: $it", text.contains("`$it` TEXT NOT NULL DEFAULT ''")) }
    }

    @Test fun allMigrationsAreOrderedAndContiguous() {
        val all = DatabaseMigrations.ALL.toList()
        assertEquals(1, all.first().startVersion)
        assertEquals(15, all.last().endVersion)
        all.zipWithNext().forEach { (a, b) -> assertEquals(a.endVersion, b.startVersion) }
    }
}
