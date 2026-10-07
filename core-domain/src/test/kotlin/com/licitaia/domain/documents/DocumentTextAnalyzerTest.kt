package com.licitaia.domain.documents

import com.licitaia.domain.model.CompanyDocument
import com.licitaia.domain.model.DocumentStatus
import com.licitaia.domain.model.DocumentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DocumentTextAnalyzerTest {

    private val zone = TimeZone.getTimeZone("America/Sao_Paulo")
    private val analyzer = DocumentTextAnalyzer(zone)

    private fun ymd(millis: Long?): String {
        assertNotNull(millis)
        val c = Calendar.getInstance(zone).apply { timeInMillis = millis!! }
        return "%02d/%02d/%04d".format(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR))
    }

    private val federal = """
        MINISTÉRIO DA FAZENDA
        Secretaria da Receita Federal do Brasil
        Procuradoria-Geral da Fazenda Nacional
        CERTIDÃO NEGATIVA DE DÉBITOS RELATIVOS AOS TRIBUTOS FEDERAIS E À DÍVIDA ATIVA DA UNIÃO
        Nome: REDE SUL TELECOM LTDA
        CNPJ: 11.222.333/0001-81
        Ressalvado o direito de a Fazenda Nacional cobrar e inscrever quaisquer dívidas de responsabilidade do sujeito
        passivo acima identificado que vierem a ser apuradas, é certificado que não constam pendências em seu nome.
        Emitida às 10:20:30 do dia 05/09/2024 <hora e data de Brasília>.
        Válida até 04/03/2025.
        Código de controle da certidão: 1A2B.3C4D.5E6F.7A8B
        Qualquer rasura ou emenda invalidará este documento.
    """.trimIndent()

    private val fgts = """
        CAIXA ECONÔMICA FEDERAL
        Certificado de Regularidade do FGTS - CRF
        Inscrição: 11.222.333/0001-81
        Razão Social: REDE SUL TELECOM LTDA
        Endereço: RUA DAS FLORES 100 / CENTRO / CAMPINAS / SP / 13010-100
        A Caixa Econômica Federal, no uso da atribuição que lhe confere o Art. 7, da Lei 8.036, de 11 de maio de 1990,
        certifica que, nesta data, a empresa acima identificada encontra-se em situação regular perante o Fundo de
        Garantia do Tempo de Servico - FGTS.
        Validade:06/09/2024 a 05/10/2024
        Certificação Número: 2024090601234567890123
        Informação obtida em 06/09/2024 10:00:00
    """.trimIndent()

    private val cndt = """
        PODER JUDICIÁRIO
        JUSTIÇA DO TRABALHO
        CERTIDÃO NEGATIVA DE DÉBITOS TRABALHISTAS
        Nome: REDE SUL TELECOM LTDA (MATRIZ E FILIAIS)
        CNPJ: 11.222.333/0001-81
        Certidão nº: 54872103/2024
        Expedição: 05/09/2024, às 10:11:12
        Validade: 04/03/2025 - 180 (cento e oitenta) dias, contados da data de sua expedição.
        Certifica-se que REDE SUL TELECOM LTDA NÃO CONSTA como inadimplente no Banco Nacional de Devedores Trabalhistas.
    """.trimIndent()

    private val cartaoCnpj = """
        REPÚBLICA FEDERATIVA DO BRASIL
        CADASTRO NACIONAL DA PESSOA JURÍDICA
        NÚMERO DE INSCRIÇÃO 11.222.333/0001-81 MATRIZ
        COMPROVANTE DE INSCRIÇÃO E DE SITUAÇÃO CADASTRAL
        DATA DE ABERTURA 01/02/2010
        NOME EMPRESARIAL REDE SUL TELECOM LTDA
        CÓDIGO E DESCRIÇÃO DA NATUREZA JURÍDICA 206-2 - Sociedade Empresária Limitada
        MUNICÍPIO CAMPINAS UF SP
        SITUAÇÃO CADASTRAL ATIVA DATA DA SITUAÇÃO CADASTRAL 01/02/2010
        Aprovado pela Instrução Normativa RFB nº 2.119, de 06 de dezembro de 2022.
        Emitido no dia 05/09/2024 às 10:11:12 (data e hora de Brasília).
    """.trimIndent()

    private val municipal = """
        PREFEITURA MUNICIPAL DE CAMPINAS
        SECRETARIA MUNICIPAL DE FINANÇAS
        CERTIDÃO NEGATIVA DE DÉBITOS DE TRIBUTOS MUNICIPAIS
        Contribuinte: REDE SUL TELECOM LTDA - CNPJ 11222333000181
        Certificamos que não constam débitos de ISSQN, taxas mobiliárias e IPTU.
        Data de emissão: 10/09/2024
        Esta certidão é válida por 90 (noventa) dias a contar da data de sua emissão.
        Código de autenticidade: AB12-CD34-EF56
    """.trimIndent()

    private val estadual = """
        GOVERNO DO ESTADO DE SÃO PAULO
        Procuradoria Geral do Estado
        Certidão Negativa de Débitos Tributários da Dívida Ativa do Estado de São Paulo
        CNPJ: 11.222.333/0001-81
        Data e hora da emissão: 11/09/2024 09:15:00
        Validade: 6 (seis) meses, contados da data de sua expedição.
    """.trimIndent()

    private val contrato = """
        INSTRUMENTO PARTICULAR DE ALTERAÇÃO CONTRATUAL Nº 5
        REDE SUL TELECOM LTDA
        CNPJ 11.222.333/0001-81
        Os sócios abaixo qualificados resolvem alterar o contrato social conforme as cláusulas seguintes.
        CLÁUSULA PRIMEIRA - O capital social passa a ser de R$ 500.000,00.
        Campinas, 15 de março de 2023.
        JUNTA COMERCIAL DO ESTADO DE SÃO PAULO
    """.trimIndent()

    private val scm = """
        AGÊNCIA NACIONAL DE TELECOMUNICAÇÕES - ANATEL
        ATO DE AUTORIZAÇÃO Nº 1.234, DE 20 DE JANEIRO DE 2015
        Expede autorização à REDE SUL TELECOM LTDA, CNPJ 11.222.333/0001-81, para explorar o Serviço de Comunicação
        Multimídia (SCM), de interesse coletivo.
    """.trimIndent()

    private val atestado = """
        PREFEITURA MUNICIPAL DE VALINHOS
        ATESTADO DE CAPACIDADE TÉCNICA
        Atestamos, para os devidos fins, que a empresa REDE SUL TELECOM LTDA, CNPJ 11.222.333/0001-81, prestou serviços
        de link dedicado de internet de 500 Mbps a esta Prefeitura, de forma satisfatória.
        Valinhos, 12 de agosto de 2024.
    """.trimIndent()

    private val balanco = """
        BALANÇO PATRIMONIAL ENCERRADO EM 31/12/2023
        REDE SUL TELECOM LTDA - CNPJ 11.222.333/0001-81
        ATIVO CIRCULANTE 1.200.000,00
        PASSIVO CIRCULANTE 400.000,00
        PATRIMÔNIO LÍQUIDO 800.000,00
        Termo de abertura e encerramento do Livro Diário nº 14
    """.trimIndent()

    // ---------------------------------------------------------------- classificação

    @Test
    fun `classifica as certidoes e documentos de habilitacao pelo texto`() {
        assertEquals(DocumentType.CERTIDAO_FEDERAL, analyzer.analyze(federal).type)
        assertEquals(DocumentType.FGTS, analyzer.analyze(fgts).type)
        assertEquals(DocumentType.TRABALHISTA, analyzer.analyze(cndt).type)
        assertEquals(DocumentType.CNPJ, analyzer.analyze(cartaoCnpj).type)
        assertEquals(DocumentType.CERTIDAO_MUNICIPAL, analyzer.analyze(municipal).type)
        assertEquals(DocumentType.CERTIDAO_ESTADUAL, analyzer.analyze(estadual).type)
        assertEquals(DocumentType.CONTRATO_SOCIAL, analyzer.analyze(contrato).type)
        assertEquals(DocumentType.SCM, analyzer.analyze(scm).type)
        assertEquals(DocumentType.ATESTADO, analyzer.analyze(atestado).type)
        assertEquals(DocumentType.BALANCO, analyzer.analyze(balanco).type)
    }

    @Test
    fun `texto sem indicios nao inventa tipo`() {
        assertNull(analyzer.analyze("Lista de compras: arroz, feijão e café.").type)
        assertTrue(analyzer.analyze("   ").isEmpty)
    }

    @Test
    fun `classifica texto de OCR em maiusculas e sem acentos`() {
        val ocr = "CERTIDAO NEGATIVA DE DEBITOS TRABALHISTAS\nCNPJ: 11.222.333/0001-81\nExpedicao: 05/09/2024"
        assertEquals(DocumentType.TRABALHISTA, analyzer.analyze(ocr).type)
    }

    // ---------------------------------------------------------------- datas e validade

    @Test
    fun `federal valida ate e emitida as do dia`() {
        val s = analyzer.analyze(federal)
        assertEquals("05/09/2024", ymd(s.issuedAt))
        assertEquals("04/03/2025", ymd(s.expiresAt))
        assertEquals("1A2B.3C4D.5E6F.7A8B", s.number)
        assertEquals("11222333000181", s.cnpj)
        assertEquals("Receita Federal do Brasil / PGFN", s.issuer)
    }

    @Test
    fun `fgts validade colada com intervalo`() {
        val s = analyzer.analyze(fgts)
        assertEquals("06/09/2024", ymd(s.issuedAt))
        assertEquals("05/10/2024", ymd(s.expiresAt))
        assertEquals("2024090601234567890123", s.number)
        assertEquals("Caixa Econômica Federal", s.issuer)
    }

    @Test
    fun `cndt expedicao e validade explicita`() {
        val s = analyzer.analyze(cndt)
        assertEquals("05/09/2024", ymd(s.issuedAt))
        assertEquals("04/03/2025", ymd(s.expiresAt))
        assertEquals("54872103/2024", s.number)
    }

    @Test
    fun `valida por 90 dias calcula a partir da emissao`() {
        val s = analyzer.analyze(municipal)
        assertEquals("10/09/2024", ymd(s.issuedAt))
        assertEquals("09/12/2024", ymd(s.expiresAt))
        assertNotNull(s.validityNote)
        assertEquals("Prefeitura Municipal de Campinas", s.issuer)
        assertEquals("AB12-CD34-EF56", s.number)
        assertEquals("11222333000181", s.cnpj)
    }

    @Test
    fun `validade em meses contados da expedicao`() {
        val s = analyzer.analyze(estadual)
        assertEquals("11/09/2024", ymd(s.issuedAt))
        assertEquals("11/03/2025", ymd(s.expiresAt))
        assertEquals("Procuradoria Geral do Estado", s.issuer)
    }

    @Test
    fun `cartao cnpj usa a data de emissao e nao a de abertura`() {
        val s = analyzer.analyze(cartaoCnpj)
        assertEquals("05/09/2024", ymd(s.issuedAt))
        assertNull(s.expiresAt)
        assertEquals("Receita Federal do Brasil", s.issuer)
    }

    @Test
    fun `data por extenso no fecho do documento`() {
        assertEquals("15/03/2023", ymd(analyzer.analyze(contrato).issuedAt))
        assertEquals("12/08/2024", ymd(analyzer.analyze(atestado).issuedAt))
        assertEquals("Prefeitura Municipal de Valinhos", analyzer.analyze(atestado).issuer)
        assertEquals("Junta Comercial do Estado de São Paulo", analyzer.analyze(contrato).issuer)
    }

    @Test
    fun `datas com espacos do OCR, pontos e ano com dois digitos`() {
        val s = analyzer.analyze("Certidão negativa de tributos federais\nEmitida em 05 / 09 / 2024\nVálida até 04.03.25")
        assertEquals("05/09/2024", ymd(s.issuedAt))
        assertEquals("04/03/2025", ymd(s.expiresAt))
    }

    @Test
    fun `validade termina no fim do dia`() {
        val s = analyzer.analyze(federal)
        val c = Calendar.getInstance(zone).apply { timeInMillis = s.expiresAt!! }
        assertEquals(23, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, c.get(Calendar.MINUTE))
    }

    @Test
    fun `validade anterior a emissao e descartada`() {
        val s = analyzer.analyze("Certidão de tributos federais. Emitida em 10/09/2024. Válida até 01/01/2020.")
        assertEquals("10/09/2024", ymd(s.issuedAt))
        assertNull(s.expiresAt)
    }

    @Test
    fun `data invalida e ignorada`() {
        val s = analyzer.analyze("Certidão de tributos federais. Válida até 31/02/2025.")
        assertNull(s.expiresAt)
    }

    // ---------------------------------------------------------------- CNPJ

    @Test
    fun `cnpj divergente gera alerta e mesma raiz indica filial`() {
        assertEquals(CnpjMatch.IGUAL, CnpjCheck.compare("11.222.333/0001-81", "11222333000181"))
        assertEquals(CnpjMatch.OUTRA_FILIAL, CnpjCheck.compare("11.222.333/0002-62", "11222333000181"))
        assertEquals(CnpjMatch.DIVERGENTE, CnpjCheck.compare("45.997.418/0001-53", "11222333000181"))
        assertEquals(CnpjMatch.DESCONHECIDO, CnpjCheck.compare(null, "11222333000181"))
        assertEquals(CnpjMatch.DESCONHECIDO, CnpjCheck.compare("123", "11222333000181"))
        assertNotNull(CnpjCheck.warning("45997418000153", "11.222.333/0001-81"))
        assertNotNull(CnpjCheck.warning("11222333000262", "11.222.333/0001-81"))
        assertNull(CnpjCheck.warning("11222333000181", "11.222.333/0001-81"))
    }

    @Test
    fun `digitos verificadores do cnpj`() {
        assertTrue(CnpjCheck.isValid("11.222.333/0001-81"))
        assertTrue(!CnpjCheck.isValid("11.222.333/0001-82"))
        assertTrue(!CnpjCheck.isValid("00000000000000"))
    }

    @Test
    fun `prefere cnpj valido quando ha varios numeros parecidos`() {
        val s = analyzer.analyze("Certidão de tributos federais\nProtocolo 12.345.678/9012-34\nCNPJ: 11.222.333/0001-81")
        assertEquals("11222333000181", s.cnpj)
    }

    // ---------------------------------------------------------------- status / vencimento / alertas

    private val day = CompanyDocument.DAY_MS
    private val now = 1_725_000_000_000L
    private fun doc(id: Long, expiresAt: Long?, attachment: String? = "content://x") = CompanyDocument(
        id = id, companyId = 1, type = DocumentType.FGTS, title = "FGTS", issuedAt = now - 10 * day,
        expiresAt = expiresAt, attachmentUri = attachment,
    )

    @Test
    fun `rotulo do selo mostra dias restantes`() {
        assertEquals("Vence em 12 dias", DocumentValidity.badge(DocumentStatus.VENCE_EM_BREVE, 12))
        assertEquals("Vence amanhã", DocumentValidity.badge(DocumentStatus.VENCE_EM_BREVE, 1))
        assertEquals("Vence hoje", DocumentValidity.badge(DocumentStatus.VENCE_EM_BREVE, 0))
        assertEquals("Vencido", DocumentValidity.badge(DocumentStatus.VENCIDO, -3))
        assertEquals("Válido", DocumentValidity.badge(DocumentStatus.VALIDO, 90))
    }

    @Test
    fun `etapas de alerta 15 e 3 dias e vencido`() {
        assertNull(DocumentValidity.alertStage(doc(1, now + 40 * day), now))
        assertEquals(15, DocumentValidity.alertStage(doc(1, now + 15 * day), now))
        assertEquals(15, DocumentValidity.alertStage(doc(1, now + 10 * day), now))
        assertEquals(3, DocumentValidity.alertStage(doc(1, now + 3 * day), now))
        assertEquals(3, DocumentValidity.alertStage(doc(1, now + 1000), now))
        assertEquals(0, DocumentValidity.alertStage(doc(1, now - 1), now))
        assertNull(DocumentValidity.alertStage(doc(1, null), now))
    }

    @Test
    fun `alertas nao se repetem e voltam quando a validade muda`() {
        val docs = listOf(doc(1, now + 2 * day), doc(2, now + 10 * day), doc(3, now + 60 * day), doc(4, now - day))
        val first = DocumentValidity.dueAlerts(docs, now, emptySet())
        assertEquals(listOf(4L, 1L, 2L), first.map { it.document.id })
        val sent = first.map { it.key }.toSet()
        assertTrue(DocumentValidity.dueAlerts(docs, now, sent).isEmpty())
        // Documento 2 chega a 3 dias: nova etapa, novo aviso.
        assertEquals(listOf(2L), DocumentValidity.dueAlerts(docs, now + 8 * day, sent).filter { it.stage == 3 && it.document.id == 2L }.map { it.document.id })
        // Renovado (nova validade): não reaproveita a chave antiga.
        val renewed = listOf(doc(1, now + 14 * day))
        assertEquals(1, DocumentValidity.dueAlerts(renewed, now, sent).size)
    }

    @Test
    fun `texto da notificacao`() {
        val single = DocumentValidity.dueAlerts(listOf(doc(1, now + 3 * day)), now, emptySet())
        assertEquals("FGTS vence em 3 dias", DocumentValidity.notificationText(single).first)
        val many = DocumentValidity.dueAlerts(listOf(doc(1, now + 3 * day), doc(2, now - day)), now, emptySet())
        assertTrue(DocumentValidity.notificationText(many).first.startsWith("1 documento(s) vencido(s)"))
    }

    @Test
    fun `ordenacao por vencimento`() {
        val docs = listOf(doc(1, null), doc(2, now + 90 * day), doc(3, now + 5 * day), doc(4, now - day), doc(5, now + 60 * day))
        val sorted = docs.sortedWith(compareBy({ DocumentValidity.sortKey(it, now).first }, { DocumentValidity.sortKey(it, now).second }))
        assertEquals(listOf(4L, 3L, 5L, 2L, 1L), sorted.map { it.id })
    }

    // ---------------------------------------------------------------- notas e IA

    @Test
    fun `numero e cnpj ficam nas observacoes e voltam ao carregar`() {
        val encoded = DocumentNotesCodec.encode("1A2B.3C4D", "11.222.333/0001-81", "Renovar em março")
        val decoded = DocumentNotesCodec.decode(encoded)
        assertEquals("1A2B.3C4D", decoded.number)
        assertEquals("11.222.333/0001-81", decoded.cnpj)
        assertEquals("Renovar em março", decoded.notes)
        assertEquals("Só observação", DocumentNotesCodec.decode("Só observação").notes)
        assertEquals("", DocumentNotesCodec.encode("", "", ""))
    }

    @Test
    fun `resposta da IA completa so os campos ausentes`() {
        val rules = analyzer.analyze("Certidão negativa de tributos municipais\nPrefeitura Municipal de Jundiaí\n")
        val ask = DocumentAiPrompt.fieldsToAsk(rules)
        assertTrue(DocumentField.EXPIRES_AT in ask)
        val ai = DocumentAiPrompt.parse(
            """
            TIPO: CERTIDAO_FEDERAL
            EMISSOR: Prefeitura de Outro Lugar
            NUMERO: 998877
            EMISSAO: 02/09/2024
            VALIDADE: 01/12/2024
            CNPJ: nao encontrado
            """.trimIndent(),
            analyzer,
        )
        val merged = rules.complementedBy(ai)
        assertEquals(DocumentType.CERTIDAO_MUNICIPAL, merged.type)
        assertEquals("Prefeitura Municipal de Jundiaí", merged.issuer)
        assertEquals("998877", merged.number)
        assertEquals("01/12/2024", ymd(merged.expiresAt))
        assertNull(merged.cnpj)
        assertEquals(setOf(DocumentField.NUMBER, DocumentField.ISSUED_AT, DocumentField.EXPIRES_AT), merged.aiFields)
    }

    @Test
    fun `documento completo pelas regras nao consulta a IA`() {
        assertTrue(DocumentAiPrompt.fieldsToAsk(analyzer.analyze(federal)).isEmpty())
        assertTrue(DocumentAiPrompt.fieldsToAsk(analyzer.analyze(cartaoCnpj)).isEmpty())
    }
}
