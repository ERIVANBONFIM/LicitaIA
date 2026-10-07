package com.licitaia.core.data.lookup

import com.licitaia.domain.lookup.LookupException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class PublicCompanyLookupTest {

    private lateinit var server: MockWebServer
    private val routes = mutableMapOf<String, MockResponse>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.path] ?: MockResponse().setResponseCode(500)
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun lookup(): PublicCompanyLookup {
        val base = server.url("/")
        return PublicCompanyLookup(OkHttpClient(), LookupEndpoints(brasilApi = base, cnpjWs = base, viaCep = base))
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    // ------------------------------------------------------------------ parsing

    @Test
    fun `parse BrasilAPI CNPJ`() {
        val d = PublicCompanyLookup.parseBrasilApiCnpj(obj(BRASILAPI_CNPJ))
        assertEquals("19131243000197", d.cnpj)
        assertEquals("OPEN KNOWLEDGE BRASIL", d.legalName)
        assertEquals("REDE PELO CONHECIMENTO LIVRE", d.tradeName)
        assertEquals("AVENIDA", d.streetType)
        assertEquals("AVENIDA PAULISTA, 37", d.streetLine)
        assertEquals("ANDAR 4", d.complement)
        assertEquals("BELA VISTA", d.district)
        assertEquals("01311902", d.zipCode)
        assertEquals("SAO PAULO", d.city)
        assertEquals("SP", d.uf)
        assertEquals("1123851939", d.phone)
        assertEquals("", d.email) // e-mail nulo na Receita
        assertEquals("ATIVA", d.status)
        assertTrue(d.isActive)
        assertEquals("9430800", d.cnaeCode)
        assertEquals(1, d.partners.size)
        assertEquals("FERNANDA CAMPAGNUCCI PEREIRA", d.partners[0].name)
        assertEquals("Presidente", d.partners[0].qualification)
        assertEquals("BrasilAPI", d.source)
    }

    @Test
    fun `parse CNPJ ws`() {
        val d = PublicCompanyLookup.parseCnpjWs(obj(CNPJWS_CNPJ))
        assertEquals("11222333000181", d.cnpj)
        assertEquals("REDE SUL TELECOM LTDA", d.legalName)
        assertEquals("REDE SUL", d.tradeName)
        assertEquals("Rua das Flores, 123", d.streetLine)
        assertEquals("SALA 4", d.complement)
        assertEquals("Centro", d.district)
        assertEquals("13010100", d.zipCode)
        assertEquals("Campinas", d.city)
        assertEquals("SP", d.uf)
        assertEquals("1932324545", d.phone)
        assertEquals("contato@redesul.com.br", d.email)
        assertEquals("ATIVA", d.status)
        assertEquals("6110801", d.cnaeCode)
        assertEquals(listOf("JOAO DA SILVA", "MARIA SOUZA"), d.partners.map { it.name })
        assertEquals("Sócio-Administrador", d.partners[1].qualification)
        assertEquals("CNPJ.ws", d.source)
    }

    @Test
    fun `parse ViaCEP e BrasilAPI CEP`() {
        val via = PublicCompanyLookup.parseViaCep(obj(VIACEP_OK))
        assertEquals("48903000", via.cep)
        assertEquals("Rua Juscelino Kubitschek", via.street)
        assertEquals("Centro", via.district)
        assertEquals("Juazeiro", via.city)
        assertEquals("BA", via.uf)
        assertTrue(PublicCompanyLookup.isNotFoundBody(obj(VIACEP_ERRO)))
        assertTrue(PublicCompanyLookup.isNotFoundBody(obj("""{"erro": "true"}""")))
        assertFalse(PublicCompanyLookup.isNotFoundBody(obj(VIACEP_OK)))

        val br = PublicCompanyLookup.parseBrasilApiCep(obj(BRASILAPI_CEP))
        assertEquals("Juazeiro", br.city)
        assertEquals("BA", br.uf)
        assertEquals("Centro", br.district)
        assertEquals("Rua Juscelino Kubitschek", br.street)
    }

    // ------------------------------------------------------------------ rede (MockWebServer)

    @Test
    fun `CNPJ usa BrasilAPI quando responde`() = runBlocking {
        routes["/api/cnpj/v1/19131243000197"] = json(BRASILAPI_CNPJ)
        val d = lookup().lookupCnpj("19.131.243/0001-97")
        assertEquals("BrasilAPI", d.source)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `CNPJ cai para CNPJ ws quando BrasilAPI falha ou limita`() = runBlocking {
        routes["/api/cnpj/v1/11222333000181"] = MockResponse().setResponseCode(429)
        routes["/cnpj/11222333000181"] = json(CNPJWS_CNPJ)
        val d = lookup().lookupCnpj("11222333000181")
        assertEquals("CNPJ.ws", d.source)
        assertEquals("REDE SUL TELECOM LTDA", d.legalName)
    }

    @Test
    fun `CNPJ inexistente nas duas fontes`() = runBlocking {
        routes["/api/cnpj/v1/11222333000181"] = json("""{"message":"CNPJ 11222333000181 não encontrado."}""", 404)
        routes["/cnpj/11222333000181"] = json("""{"status":404,"titulo":"Não Encontrado"}""", 404)
        try {
            lookup().lookupCnpj("11222333000181")
            fail("deveria falhar")
        } catch (e: LookupException) {
            assertTrue(e.notFound)
            assertTrue(e.message!!.contains("não encontrado"))
        }
    }

    @Test
    fun `CNPJ com limite nas duas fontes tem mensagem clara`() = runBlocking {
        routes["/api/cnpj/v1/11222333000181"] = MockResponse().setResponseCode(429)
        routes["/cnpj/11222333000181"] = MockResponse().setResponseCode(429)
        try {
            lookup().lookupCnpj("11222333000181")
            fail("deveria falhar")
        } catch (e: LookupException) {
            assertFalse(e.notFound)
            assertTrue(e.message!!.contains("Limite de consultas"))
        }
    }

    @Test
    fun `CEP existente e CEP com erro`() = runBlocking {
        routes["/ws/48903000/json/"] = json(VIACEP_OK)
        assertEquals("Juazeiro", lookup().lookupCep("48903-000").city)

        routes["/ws/99999999/json/"] = json(VIACEP_ERRO)
        routes["/api/cep/v2/99999999"] = json("""{"message":"Todos os serviços de CEP retornaram erro.","type":"service_error","name":"CepPromiseError"}""", 404)
        try {
            lookup().lookupCep("99999999")
            fail("deveria falhar")
        } catch (e: LookupException) {
            assertTrue(e.notFound)
            assertTrue(e.message!!.startsWith("CEP não encontrado"))
        }
    }

    @Test
    fun `CEP cai para BrasilAPI quando ViaCEP esta fora`() = runBlocking {
        routes["/ws/48903000/json/"] = MockResponse().setResponseCode(503)
        routes["/api/cep/v2/48903000"] = json(BRASILAPI_CEP)
        val cep = lookup().lookupCep("48903000")
        assertEquals("BrasilAPI", cep.source)
        assertEquals("BA", cep.uf)
    }

    @Test
    fun `sem servidor e mensagem de falha generica`() = runBlocking {
        server.shutdown()
        try {
            lookup().lookupCep("48903000")
            fail("deveria falhar")
        } catch (e: LookupException) {
            assertFalse(e.notFound)
        }
    }

    companion object {
        /** Exemplo da documentação da BrasilAPI (/api/cnpj/v1/19131243000197). */
        val BRASILAPI_CNPJ = """
            {"uf":"SP","cep":"01311902","qsa":[{"pais":null,"nome_socio":"FERNANDA CAMPAGNUCCI PEREIRA","codigo_pais":null,
            "faixa_etaria":"Entre 31 a 40 anos","cnpj_cpf_do_socio":"***690948**","qualificacao_socio":"Presidente",
            "codigo_faixa_etaria":4,"data_entrada_sociedade":"2019-10-25","identificador_de_socio":2,
            "cpf_representante_legal":"***000000**","nome_representante_legal":"","codigo_qualificacao_socio":16,
            "qualificacao_representante_legal":"Não informada","codigo_qualificacao_representante_legal":0}],
            "cnpj":"19131243000197","pais":null,"email":null,"porte":"DEMAIS","bairro":"BELA VISTA","numero":"37","ddd_fax":"",
            "municipio":"SAO PAULO","logradouro":"PAULISTA","cnae_fiscal":9430800,"codigo_pais":null,"complemento":"ANDAR 4",
            "codigo_porte":5,"razao_social":"OPEN KNOWLEDGE BRASIL","nome_fantasia":"REDE PELO CONHECIMENTO LIVRE","capital_social":0,
            "ddd_telefone_1":"1123851939","ddd_telefone_2":"","opcao_pelo_mei":null,"descricao_porte":"","codigo_municipio":7107,
            "cnaes_secundarios":[{"codigo":9493600,"descricao":"Atividades de organizações associativas ligadas à cultura e à arte"}],
            "natureza_juridica":"Associação Privada","situacao_especial":"","opcao_pelo_simples":null,"situacao_cadastral":2,
            "cnae_fiscal_descricao":"Atividades de associações de defesa de direitos sociais","codigo_municipio_ibge":3550308,
            "data_inicio_atividade":"2013-10-03","descricao_situacao_cadastral":"ATIVA","descricao_tipo_de_logradouro":"AVENIDA",
            "descricao_identificador_matriz_filial":"MATRIZ"}
        """.trimIndent()

        /** Estrutura da API pública do CNPJ.ws (/cnpj/{cnpj}). */
        val CNPJWS_CNPJ = """
            {"cnpj_raiz":"11222333","razao_social":"REDE SUL TELECOM LTDA","capital_social":"100000.00",
            "porte":{"id":"03","descricao":"Empresa de Pequeno Porte"},
            "socios":[{"cpf_cnpj_socio":"***123456**","nome":"JOAO DA SILVA","tipo":"Pessoa Física","qualificacao_socio":{"id":22,"descricao":"Sócio"}},
                      {"cpf_cnpj_socio":"***654321**","nome":"MARIA SOUZA","tipo":"Pessoa Física","qualificacao_socio":{"id":49,"descricao":"Sócio-Administrador"}}],
            "estabelecimento":{"cnpj":"11222333000181","nome_fantasia":"REDE SUL","situacao_cadastral":"Ativa","tipo_logradouro":"Rua",
              "logradouro":"das Flores","numero":"123","complemento":"SALA 4","bairro":"Centro","cep":"13010100","ddd1":"19","telefone1":"32324545",
              "ddd2":null,"telefone2":null,"email":"CONTATO@REDESUL.COM.BR",
              "atividade_principal":{"id":"6110801","secao":"J","divisao":"61","grupo":"61.1","classe":"61.10-8","subclasse":"6110-8/01","descricao":"Serviços de telefonia fixa comutada - STFC"},
              "estado":{"id":26,"nome":"São Paulo","sigla":"SP","ibge_id":35},"cidade":{"id":5,"nome":"Campinas","ibge_id":3509502}}}
        """.trimIndent()

        val VIACEP_OK = """
            {"cep":"48903-000","logradouro":"Rua Juscelino Kubitschek","complemento":"","unidade":"","bairro":"Centro",
             "localidade":"Juazeiro","uf":"BA","estado":"Bahia","regiao":"Nordeste","ibge":"2918407","gia":"","ddd":"74","siafi":"3741"}
        """.trimIndent()

        const val VIACEP_ERRO = """{"erro": true}"""

        val BRASILAPI_CEP = """
            {"cep":"48903000","state":"BA","city":"Juazeiro","neighborhood":"Centro","street":"Rua Juscelino Kubitschek",
             "service":"open-cep","location":{"type":"Point","coordinates":{}}}
        """.trimIndent()
    }
}
