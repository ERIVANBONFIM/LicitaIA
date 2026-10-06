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

    @Test fun allMigrationsAreOrderedAndContiguous() {
        val all = DatabaseMigrations.ALL.toList()
        assertEquals(1, all.first().startVersion)
        assertEquals(7, all.last().endVersion)
        all.zipWithNext().forEach { (a, b) -> assertEquals(a.endVersion, b.startVersion) }
    }
}
