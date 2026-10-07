package com.licitaia.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Migração 12→13 num SQLite REAL (sqlite-jdbc): banco v12 criado a partir do 12.json exportado pelo Room + migração
 * deve ficar igual ao v13 do 13.json (mesmas colunas, FK e índice que o Room valida ao abrir o banco), e apagar a
 * licitação apaga em cascata só as perguntas dela.
 */
class EditalQuestionsMigrationSqliteTest {
    private lateinit var migrated: Connection
    private lateinit var fresh: Connection

    private fun schema(version: Int): JsonObject? =
        listOf("schemas/com.licitaia.core.data.db.LicitaDatabase/$version.json", "core-data/schemas/com.licitaia.core.data.db.LicitaDatabase/$version.json")
            .map(::File).firstOrNull { it.exists() }
            ?.let { Json.parseToJsonElement(it.readText()).jsonObject["database"]!!.jsonObject }

    /** Cria todas as tabelas e índices de um schema exportado pelo Room. */
    private fun Connection.create(schema: JsonObject) = createStatement().use { st ->
        schema["entities"]!!.jsonArray.forEach { element ->
            val entity = element.jsonObject
            val table = entity["tableName"]!!.jsonPrimitive.content
            st.execute(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            entity["indices"]?.jsonArray?.forEach { index ->
                st.execute(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
    }

    @Before fun setUp() {
        val v12 = schema(12)
        val v13 = schema(13)
        assumeTrue("schemas exportados ausentes", v12 != null && v13 != null)
        migrated = DriverManager.getConnection("jdbc:sqlite::memory:")
        fresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        migrated.create(v12!!)
        fresh.create(v13!!)
        // Migração real executada no SQLite via SupportSQLiteDatabase simulado.
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        every { db.execSQL(any<String>()) } answers { migrated.createStatement().use { it.execute(firstArg<String>()) } }
        DatabaseMigrations.FROM_12_TO_13.migrate(db)
    }

    @After fun tearDown() {
        if (::migrated.isInitialized) migrated.close()
        if (::fresh.isInitialized) fresh.close()
    }

    /** O que o Room compara na validação: colunas, chaves estrangeiras e índices. */
    private fun Connection.describe(table: String): String = createStatement().use { st ->
        buildString {
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) append("col ${rs.getString("name")} ${rs.getString("type")} nn=${rs.getInt("notnull")} pk=${rs.getInt("pk")} def=${rs.getString("dflt_value")}\n")
            }
            st.executeQuery("PRAGMA foreign_key_list(`$table`)").use { rs ->
                while (rs.next()) append("fk ${rs.getString("table")} ${rs.getString("from")}->${rs.getString("to")} upd=${rs.getString("on_update")} del=${rs.getString("on_delete")}\n")
            }
            val indices = mutableListOf<String>()
            st.executeQuery("PRAGMA index_list(`$table`)").use { rs -> while (rs.next()) indices += rs.getString("name") }
            indices.sorted().forEach { index ->
                append("idx $index:")
                st.executeQuery("PRAGMA index_info(`$index`)").use { rs -> while (rs.next()) append(" ${rs.getString("name")}") }
                append('\n')
            }
        }
    }

    @Test fun migratedSchemaEqualsExportedVersion13() {
        val expected = fresh.describe("edital_questions")
        assertTrue(expected.contains("fk tenders tenderId->id upd=NO ACTION del=CASCADE"))
        assertTrue(expected.contains("idx index_edital_questions_tenderId: tenderId"))
        assertEquals(expected, migrated.describe("edital_questions"))
        // Tabelas existentes intactas.
        listOf("tenders", "tender_analyses", "proposals", "companies").forEach { table ->
            assertEquals(table, fresh.describe(table), migrated.describe(table))
        }
    }

    @Test fun deletingTenderCascadesOnlyItsQuestions() {
        migrated.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        insertTender(id = 1, companyId = 1)
        insertTender(id = 2, companyId = 2)
        insertQuestion(tenderId = 1, companyId = 1, question = "Prazo?")
        insertQuestion(tenderId = 1, companyId = 1, question = "Pagamento?")
        insertQuestion(tenderId = 2, companyId = 2, question = "Habilitação?")
        assertEquals(3, count())
        migrated.createStatement().use { it.execute("DELETE FROM tenders WHERE id = 1") }
        assertEquals(1, count())
        migrated.createStatement().use { st ->
            st.executeQuery("SELECT companyId, question FROM edital_questions").use { rs ->
                rs.next()
                assertEquals(2L, rs.getLong(1))
                assertEquals("Habilitação?", rs.getString(2))
            }
        }
    }

    @Test fun questionCannotPointToMissingTender() {
        migrated.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        val failed = runCatching { insertQuestion(tenderId = 99, companyId = 1, question = "Órfã?") }.isFailure
        assertTrue("FK deve recusar pergunta sem licitação", failed)
    }

    private fun count(): Int = migrated.createStatement().use { st ->
        st.executeQuery("SELECT COUNT(*) FROM edital_questions").use { rs -> rs.next(); rs.getInt(1) }
    }

    /** Insere uma licitação preenchendo as colunas NOT NULL sem default com valores neutros do tipo. */
    private fun insertTender(id: Long, companyId: Long) {
        val columns = mutableListOf<Pair<String, String>>()
        migrated.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(tenders)").use { rs ->
                while (rs.next()) {
                    val name = rs.getString("name")
                    val value = when (name) {
                        "id" -> id.toString()
                        "companyId" -> companyId.toString()
                        "opportunityId" -> "'OP-$id'"
                        else -> if (rs.getInt("notnull") == 1 && rs.getString("dflt_value") == null) {
                            when (rs.getString("type").uppercase()) {
                                "INTEGER" -> "0"
                                "REAL" -> "0.0"
                                else -> "''"
                            }
                        } else {
                            null
                        }
                    }
                    if (value != null) columns += name to value
                }
            }
            st.execute("INSERT INTO tenders (${columns.joinToString { "`${it.first}`" }}) VALUES (${columns.joinToString { it.second }})")
        }
    }

    private fun insertQuestion(tenderId: Long, companyId: Long, question: String) {
        migrated.prepareStatement(
            "INSERT INTO edital_questions (companyId, tenderId, question, answer, provider, model, sources, createdAt, status) " +
                "VALUES (?, ?, ?, '', '', NULL, '', 0, 'OK')",
        ).use { ps ->
            ps.setLong(1, companyId)
            ps.setLong(2, tenderId)
            ps.setString(3, question)
            ps.executeUpdate()
        }
    }
}
