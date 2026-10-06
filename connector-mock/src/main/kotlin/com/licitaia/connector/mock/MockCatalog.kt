package com.licitaia.connector.mock

import com.licitaia.connector.api.TenderDetails
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Modality.CONCORRENCIA
import com.licitaia.domain.model.Modality.CREDENCIAMENTO
import com.licitaia.domain.model.Modality.DISPENSA_ELETRONICA
import com.licitaia.domain.model.Modality.PREGAO_ELETRONICO
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Portal.BLL
import com.licitaia.domain.model.Portal.COMPRAS_GOV
import com.licitaia.domain.model.Portal.LICITANET
import com.licitaia.domain.model.Portal.PORTAL_COMPRAS_PUBLICAS
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.Segment.EQUIPAMENTOS
import com.licitaia.domain.model.Segment.SERVICOS
import com.licitaia.domain.model.Segment.SOFTWARE
import com.licitaia.domain.model.Segment.TELECOM_ISP
import com.licitaia.domain.model.Segment.TI
import java.text.Normalizer

/**
 * Catálogo FICTÍCIO de oportunidades para demonstração. Os órgãos são nomes genéricos e os
 * números/valores não correspondem a processos reais. Nada aqui vem de rede.
 */
internal object MockCatalog {

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    private data class Row(
        val portal: Portal, val number: String, val agency: String, val obj: String, val modality: Modality,
        val segment: Segment, val uf: String, val city: String, val value: Double,
        val publishedDaysAgo: Int, val deadlineInDays: Int, val local: Boolean, val keywords: List<String>,
    )

    private fun r(
        portal: Portal, number: String, agency: String, obj: String, modality: Modality, segment: Segment,
        uf: String, city: String, value: Double, pub: Int, deadline: Int, local: Boolean = false, vararg kw: String,
    ) = Row(portal, number, agency, obj, modality, segment, uf, city, value, pub, deadline, local, kw.toList())

    private val rows: List<Row> = listOf(
        // ------------------------------------------------------------ Compras.gov.br
        r(COMPRAS_GOV, "90045/2026", "Universidade Federal do Triângulo Mineiro", "Contratação de link de internet dedicado de 1 Gbps com proteção anti-DDoS para o campus sede, por 12 meses", PREGAO_ELETRONICO, TELECOM_ISP, "MG", "Uberaba", 186_000.0, 6, 5, true, "link dedicado", "internet", "anti-ddos", "fibra"),
        r(COMPRAS_GOV, "90112/2026", "Instituto Federal de Educação, Ciência e Tecnologia do Paraná", "Serviço de conectividade IP dedicada para 14 campi, com redundância de rota e SLA de 99,7%", PREGAO_ELETRONICO, TELECOM_ISP, "PR", "Curitiba", 1_420_000.0, 9, 8, false, "link dedicado", "ip", "redundância", "sla"),
        r(COMPRAS_GOV, "90031/2026", "Tribunal Regional do Trabalho — 12ª Região", "Aquisição de switches de acesso gerenciáveis 48 portas PoE+ com garantia on-site de 60 meses", PREGAO_ELETRONICO, EQUIPAMENTOS, "SC", "Florianópolis", 874_500.0, 4, 11, false, "switch", "poe", "rede", "garantia"),
        r(COMPRAS_GOV, "90207/2026", "Hospital Universitário Federal de Juiz de Fora", "Locação de solução de rede sem fio corporativa (Wi-Fi 6) com controladora, instalação e suporte 24x7", PREGAO_ELETRONICO, TI, "MG", "Juiz de Fora", 612_000.0, 3, 9, true, "wi-fi", "access point", "controladora", "suporte"),
        r(COMPRAS_GOV, "90088/2026", "Superintendência Regional da Polícia Rodoviária Federal", "Serviço de comunicação de dados via satélite e rádio para 22 unidades operacionais", PREGAO_ELETRONICO, TELECOM_ISP, "MT", "Cuiabá", 2_180_000.0, 12, 14, false, "satélite", "rádio", "comunicação de dados"),
        r(COMPRAS_GOV, "90154/2026", "Universidade Federal Rural do Semi-Árido", "Licenciamento de suíte de produtividade e colaboração em nuvem para 4.500 usuários, 36 meses", PREGAO_ELETRONICO, SOFTWARE, "RN", "Mossoró", 1_965_000.0, 7, 6, false, "licença", "nuvem", "colaboração", "software"),
        r(COMPRAS_GOV, "90019/2026", "Agência Reguladora Federal — Unidade Regional Norte", "Contratação de central de serviços (service desk) de TI níveis 1 e 2 com atendimento presencial", PREGAO_ELETRONICO, SERVICOS, "PA", "Belém", 1_130_000.0, 10, 4, true, "service desk", "suporte", "atendimento"),
        r(COMPRAS_GOV, "90276/2026", "Instituto Federal do Sertão Pernambucano", "Aquisição de microcomputadores desktop e monitores para laboratórios de informática", PREGAO_ELETRONICO, EQUIPAMENTOS, "PE", "Petrolina", 538_900.0, 2, 13, false, "desktop", "computador", "monitor"),
        r(COMPRAS_GOV, "90301/2026", "Fundação Universidade Federal do Pampa", "Serviço de telefonia IP em nuvem (PABX virtual) com 600 ramais e tronco SIP", PREGAO_ELETRONICO, TELECOM_ISP, "RS", "Bagé", 297_600.0, 5, 7, false, "pabx", "voip", "sip", "telefonia"),
        r(COMPRAS_GOV, "90067/2026", "Delegacia da Receita Federal — 8ª Região Fiscal", "Manutenção preventiva e corretiva de infraestrutura de cabeamento estruturado e fibra óptica", PREGAO_ELETRONICO, SERVICOS, "SP", "Campinas", 246_000.0, 8, 3, true, "cabeamento", "fibra óptica", "manutenção"),
        r(COMPRAS_GOV, "75/2026", "Comando Militar do Planalto — Organização Militar de Apoio", "Dispensa eletrônica para aquisição de rádios enlace ponto a ponto 5 GHz e acessórios", DISPENSA_ELETRONICA, EQUIPAMENTOS, "DF", "Brasília", 58_400.0, 1, 2, false, "rádio", "enlace", "ponto a ponto"),
        r(COMPRAS_GOV, "90333/2026", "Universidade Federal do Recôncavo", "Solução de firewall de próxima geração em alta disponibilidade com licenças por 36 meses", PREGAO_ELETRONICO, TI, "BA", "Cruz das Almas", 742_000.0, 6, 10, false, "firewall", "ngfw", "segurança"),
        r(COMPRAS_GOV, "90358/2026", "Tribunal Regional Eleitoral — Seção Centro-Oeste", "Desenvolvimento e sustentação de sistemas em fábrica de software, 9.000 pontos de função", CONCORRENCIA, SOFTWARE, "GO", "Goiânia", 6_300_000.0, 15, 21, false, "fábrica de software", "ponto de função", "sustentação"),

        // ------------------------------------------------------------ BLL Compras
        r(BLL, "112/2026", "Prefeitura Municipal de Cascavel", "Fornecimento de internet banda larga em fibra óptica para 86 unidades escolares e de saúde", PREGAO_ELETRONICO, TELECOM_ISP, "PR", "Cascavel", 468_000.0, 5, 6, true, "internet", "fibra", "banda larga", "escolas"),
        r(BLL, "38/2026", "Prefeitura Municipal de Toledo", "Aquisição de switches gerenciáveis camada 3 e transceptores SFP+ para o datacenter municipal", PREGAO_ELETRONICO, EQUIPAMENTOS, "PR", "Toledo", 124_800.0, 3, 4, false, "switch", "sfp", "datacenter"),
        r(BLL, "57/2026", "Consórcio Intermunicipal de Saúde do Oeste", "Locação de sistema de gestão de saúde pública em nuvem com prontuário eletrônico", PREGAO_ELETRONICO, SOFTWARE, "PR", "Foz do Iguaçu", 1_080_000.0, 11, 12, false, "sistema de gestão", "saúde", "prontuário", "saas"),
        r(BLL, "204/2026", "Prefeitura Municipal de Chapecó", "Implantação de videomonitoramento urbano com 140 câmeras IP, rede óptica e central de operações", PREGAO_ELETRONICO, TI, "SC", "Chapecó", 3_250_000.0, 14, 16, true, "videomonitoramento", "câmera ip", "rede óptica", "cftv"),
        r(BLL, "19/2026", "Câmara Municipal de Joinville", "Link de internet dedicado 500 Mbps simétrico com link redundante de 200 Mbps", PREGAO_ELETRONICO, TELECOM_ISP, "SC", "Joinville", 96_000.0, 2, 5, true, "link dedicado", "redundante", "internet"),
        r(BLL, "81/2026", "Prefeitura Municipal de Londrina", "Registro de preços para notebooks corporativos com garantia de 36 meses", PREGAO_ELETRONICO, EQUIPAMENTOS, "PR", "Londrina", 1_347_000.0, 7, 9, false, "notebook", "registro de preços", "garantia"),
        r(BLL, "143/2026", "Serviço Autônomo Municipal de Água e Esgoto de Blumenau", "Serviço de telemetria via rede LoRaWAN/4G para 320 pontos de medição", PREGAO_ELETRONICO, TELECOM_ISP, "SC", "Blumenau", 389_000.0, 9, 8, false, "telemetria", "lorawan", "4g", "iot"),
        r(BLL, "66/2026", "Prefeitura Municipal de Rondonópolis", "Outsourcing de impressão com 210 equipamentos multifuncionais e gestão de páginas", PREGAO_ELETRONICO, SERVICOS, "MT", "Rondonópolis", 842_000.0, 4, 7, true, "outsourcing", "impressão", "multifuncional"),
        r(BLL, "12/2026", "Prefeitura Municipal de Guarapuava", "Dispensa eletrônica — renovação de licenças de antivírus corporativo para 900 estações", DISPENSA_ELETRONICA, SOFTWARE, "PR", "Guarapuava", 49_500.0, 1, 3, false, "antivírus", "licença", "endpoint"),
        r(BLL, "175/2026", "Prefeitura Municipal de Dourados", "Rede Wi-Fi pública em praças e terminais, com 60 pontos de acesso externos e gestão centralizada", PREGAO_ELETRONICO, TELECOM_ISP, "MS", "Dourados", 276_000.0, 6, 10, true, "wi-fi", "praça", "ponto de acesso", "internet"),
        r(BLL, "98/2026", "Fundo Municipal de Educação de Criciúma", "Aquisição de lousas digitais interativas e kits de robótica educacional", PREGAO_ELETRONICO, EQUIPAMENTOS, "SC", "Criciúma", 915_000.0, 8, 15, false, "lousa digital", "robótica", "educação"),

        // ------------------------------------------------------------ Licitanet
        r(LICITANET, "27/2026", "Prefeitura Municipal de Uberaba", "Contratação de provedor de acesso à internet com link dedicado de 2 Gbps e bloco IPv4 /28", PREGAO_ELETRONICO, TELECOM_ISP, "MG", "Uberaba", 228_000.0, 4, 5, true, "link dedicado", "ipv4", "provedor", "internet"),
        r(LICITANET, "61/2026", "Prefeitura Municipal de Patos de Minas", "Interligação de 48 prédios públicos por rede metropolitana em fibra óptica (lan-to-lan)", PREGAO_ELETRONICO, TELECOM_ISP, "MG", "Patos de Minas", 734_000.0, 10, 9, true, "lan-to-lan", "fibra óptica", "rede metropolitana"),
        r(LICITANET, "09/2026", "Câmara Municipal de Montes Claros", "Sistema de gestão legislativa e transmissão das sessões com portal da transparência", PREGAO_ELETRONICO, SOFTWARE, "MG", "Montes Claros", 198_000.0, 6, 7, false, "sistema legislativo", "transparência", "software"),
        r(LICITANET, "133/2026", "Prefeitura Municipal de Anápolis", "Aquisição de servidores em rack e storage híbrido para modernização do datacenter", PREGAO_ELETRONICO, EQUIPAMENTOS, "GO", "Anápolis", 1_590_000.0, 13, 14, false, "servidor", "storage", "datacenter"),
        r(LICITANET, "44/2026", "Prefeitura Municipal de Rio Verde", "Serviço de telefonia móvel pessoal com 380 linhas e pacote de dados", PREGAO_ELETRONICO, TELECOM_ISP, "GO", "Rio Verde", 312_000.0, 3, 6, false, "telefonia móvel", "smp", "dados"),
        r(LICITANET, "88/2026", "Instituto de Previdência dos Servidores de Uberlândia", "Sustentação de infraestrutura de TI com monitoramento 24x7, backup em nuvem e NOC", PREGAO_ELETRONICO, SERVICOS, "MG", "Uberlândia", 564_000.0, 8, 11, true, "noc", "monitoramento", "backup", "sustentação"),
        r(LICITANET, "152/2026", "Prefeitura Municipal de Sete Lagoas", "Locação de software de gestão tributária e nota fiscal eletrônica municipal", PREGAO_ELETRONICO, SOFTWARE, "MG", "Sete Lagoas", 876_000.0, 12, 13, false, "gestão tributária", "nfs-e", "software"),
        r(LICITANET, "23/2026", "Prefeitura Municipal de Ji-Paraná", "Internet via fibra para unidades rurais e link de backup via rádio licenciado", PREGAO_ELETRONICO, TELECOM_ISP, "RO", "Ji-Paraná", 174_000.0, 5, 8, true, "internet", "rural", "rádio", "fibra"),
        r(LICITANET, "70/2026", "Prefeitura Municipal de Palmas", "Aquisição de nobreaks senoidais de 3 kVA e bancos de baterias", PREGAO_ELETRONICO, EQUIPAMENTOS, "TO", "Palmas", 142_500.0, 2, 4, false, "nobreak", "bateria", "energia"),
        r(LICITANET, "05/2026", "Prefeitura Municipal de Araxá", "Credenciamento de provedores para o programa municipal de internet popular", CREDENCIAMENTO, TELECOM_ISP, "MG", "Araxá", 420_000.0, 16, 25, true, "credenciamento", "provedor", "internet popular"),
        r(LICITANET, "117/2026", "Consórcio Público do Vale do Rio Doce", "Serviços de instalação e manutenção de redes lógicas e elétricas em 35 municípios", PREGAO_ELETRONICO, SERVICOS, "MG", "Governador Valadares", 688_000.0, 9, 12, true, "rede lógica", "manutenção", "instalação"),

        // ------------------------------------------------------------ Portal de Compras Públicas
        r(PORTAL_COMPRAS_PUBLICAS, "31/2026", "Prefeitura Municipal de Caruaru", "Link de internet dedicado 1 Gbps para o centro administrativo e 300 Mbps para 12 secretarias", PREGAO_ELETRONICO, TELECOM_ISP, "PE", "Caruaru", 352_000.0, 5, 6, true, "link dedicado", "internet", "secretarias"),
        r(PORTAL_COMPRAS_PUBLICAS, "104/2026", "Prefeitura Municipal de Juazeiro do Norte", "Solução de conectividade para a rede municipal de ensino com Wi-Fi gerenciado em 74 escolas", PREGAO_ELETRONICO, TELECOM_ISP, "CE", "Juazeiro do Norte", 918_000.0, 8, 10, true, "wi-fi", "escolas", "conectividade"),
        r(PORTAL_COMPRAS_PUBLICAS, "16/2026", "Secretaria Municipal de Saúde de Feira de Santana", "Aquisição de computadores all-in-one e impressoras térmicas para unidades básicas de saúde", PREGAO_ELETRONICO, EQUIPAMENTOS, "BA", "Feira de Santana", 486_000.0, 3, 7, false, "computador", "impressora", "saúde"),
        r(PORTAL_COMPRAS_PUBLICAS, "59/2026", "Prefeitura Municipal de Santa Maria", "Sistema integrado de gestão pública (contábil, RH, compras e protocolo digital) em nuvem", PREGAO_ELETRONICO, SOFTWARE, "RS", "Santa Maria", 1_740_000.0, 13, 15, false, "erp", "gestão pública", "nuvem", "software"),
        r(PORTAL_COMPRAS_PUBLICAS, "82/2026", "Prefeitura Municipal de Pelotas", "Serviço de datacenter em colocation com conectividade redundante e suporte remoto", PREGAO_ELETRONICO, TI, "RS", "Pelotas", 624_000.0, 7, 9, false, "colocation", "datacenter", "conectividade"),
        r(PORTAL_COMPRAS_PUBLICAS, "140/2026", "Prefeitura Municipal de Parnamirim", "Implantação de central telefônica IP e contact center para a ouvidoria municipal", PREGAO_ELETRONICO, TELECOM_ISP, "RN", "Parnamirim", 233_000.0, 4, 5, false, "telefonia ip", "contact center", "pabx"),
        r(PORTAL_COMPRAS_PUBLICAS, "07/2026", "Câmara Municipal de Campina Grande", "Dispensa eletrônica — serviço de hospedagem de site, e-mail corporativo e certificado SSL", DISPENSA_ELETRONICA, SERVICOS, "PB", "Campina Grande", 38_900.0, 1, 3, false, "hospedagem", "e-mail", "ssl"),
        r(PORTAL_COMPRAS_PUBLICAS, "121/2026", "Prefeitura Municipal de Imperatriz", "Cidade inteligente: rede óptica própria, iluminação telegerida e pontos de Wi-Fi público", CONCORRENCIA, TI, "MA", "Imperatriz", 8_450_000.0, 18, 28, true, "cidade inteligente", "rede óptica", "telegestão", "wi-fi"),
        r(PORTAL_COMPRAS_PUBLICAS, "47/2026", "Prefeitura Municipal de Vitória da Conquista", "Aquisição de rádios digitais e repetidoras para a guarda municipal", PREGAO_ELETRONICO, EQUIPAMENTOS, "BA", "Vitória da Conquista", 407_000.0, 6, 8, false, "rádio digital", "repetidora", "comunicação"),
        r(PORTAL_COMPRAS_PUBLICAS, "93/2026", "Prefeitura Municipal de Macaé", "Suporte técnico especializado em redes, segurança da informação e virtualização (banco de horas)", PREGAO_ELETRONICO, SERVICOS, "RJ", "Macaé", 295_000.0, 9, 11, true, "suporte", "segurança da informação", "virtualização"),
        r(PORTAL_COMPRAS_PUBLICAS, "166/2026", "Prefeitura Municipal de Linhares", "Fornecimento de internet em fibra óptica com gerência proativa para 52 pontos da administração", PREGAO_ELETRONICO, TELECOM_ISP, "ES", "Linhares", 318_000.0, 2, 6, true, "internet", "fibra óptica", "gerência"),
        r(PORTAL_COMPRAS_PUBLICAS, "28/2026", "Prefeitura Municipal de Sobral", "Plataforma de ensino a distância e licenças de conteúdo digital para a rede municipal", PREGAO_ELETRONICO, SOFTWARE, "CE", "Sobral", 552_000.0, 10, 12, false, "ead", "plataforma", "licença", "educação"),
    )

    private fun Row.toOpportunity(now: Long): Opportunity {
        val base = now - now % HOUR
        val deadline = base + deadlineInDays * DAY + (9 + number.length % 6) * HOUR
        return Opportunity(
            id = "${portal.name}:$number",
            portal = portal,
            number = number,
            agency = agency,
            objectDescription = obj,
            modality = modality,
            segment = segment,
            uf = uf,
            city = city,
            estimatedValue = value,
            publishedAt = base - publishedDaysAgo * DAY,
            proposalDeadline = deadline,
            sessionAt = deadline + HOUR,
            requiresLocalSupport = local,
            keywords = keywords,
            // Somente a página pública institucional do portal; não há link direto inventado.
            editalUrl = portal.publicUrl,
        )
    }

    fun all(portal: Portal, now: Long = System.currentTimeMillis()): List<Opportunity> =
        rows.filter { it.portal == portal }.map { it.toOpportunity(now) }

    fun find(portal: Portal, opportunityId: String, now: Long = System.currentTimeMillis()): Opportunity? =
        rows.firstOrNull { it.portal == portal && "${it.portal.name}:${it.number}" == opportunityId }?.toOpportunity(now)

    /** `minScore` não é aplicado aqui: o score de aderência é calculado pelo repositório. */
    fun search(portal: Portal, filter: OpportunityFilter, now: Long = System.currentTimeMillis()): List<Opportunity> {
        if (filter.portals.isNotEmpty() && portal !in filter.portals) return emptyList()
        val terms = normalize(filter.query).split(' ').filter { it.isNotBlank() }
        val ufs = filter.ufs.map { it.uppercase() }.toSet()
        return all(portal, now).filter { o ->
            (ufs.isEmpty() || o.uf in ufs) &&
                (filter.segment == null || o.segment == filter.segment) &&
                (filter.modality == null || o.modality == filter.modality) &&
                (filter.minValue == null || o.estimatedValue >= filter.minValue!!) &&
                (filter.maxValue == null || o.estimatedValue <= filter.maxValue!!) &&
                (terms.isEmpty() || normalize(
                    listOf(o.objectDescription, o.agency, o.number, o.city, o.uf, o.keywords.joinToString(" ")).joinToString(" "),
                ).let { haystack -> terms.all { it in haystack } })
        }.sortedBy { it.proposalDeadline }
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

    fun details(o: Opportunity): TenderDetails {
        val items = when (o.segment) {
            TELECOM_ISP -> listOf("Item 1 — Serviço de conectividade (mensal, 12 meses)", "Item 2 — Instalação e ativação (parcela única)")
            EQUIPAMENTOS -> listOf("Lote 1 — Equipamentos", "Lote 2 — Acessórios e garantia estendida")
            SOFTWARE -> listOf("Item 1 — Licenciamento/assinatura", "Item 2 — Implantação e treinamento", "Item 3 — Suporte técnico mensal")
            TI -> listOf("Lote único — Solução completa com instalação, configuração e suporte")
            else -> listOf("Item 1 — Prestação dos serviços (mensal)")
        }
        val sla = when (o.segment) {
            TELECOM_ISP -> "Disponibilidade mínima mensal de 99,5%, com atendimento em até 4 horas e reparo em até 8 horas."
            SERVICOS, TI -> "Atendimento a chamados críticos em até 2 horas e solução em até 8 horas úteis."
            SOFTWARE -> "Disponibilidade mínima de 99% e correção de falhas impeditivas em até 24 horas."
            else -> "Substituição de equipamento defeituoso em até 5 dias úteis durante a garantia."
        }
        val qualification = when (o.segment) {
            TELECOM_ISP -> "Autorização SCM vigente junto à Anatel; atestado de capacidade técnica de serviço compatível; registro no CREA/CRT do responsável técnico."
            EQUIPAMENTOS -> "Atestado de fornecimento compatível em características e quantidades; declaração do fabricante quanto à garantia."
            SOFTWARE -> "Atestado de implantação de solução similar; comprovação de equipe técnica certificada."
            else -> "Atestado de capacidade técnica compatível com o objeto; indicação de responsável técnico."
        }
        val text = buildString {
            appendLine("EDITAL (TRECHO DE DEMONSTRAÇÃO — CONTEÚDO FICTÍCIO)")
            appendLine("${o.modality.label} nº ${o.number} — ${o.agency} (${o.city}/${o.uf})")
            appendLine()
            appendLine("1. DO OBJETO")
            appendLine("1.1. ${o.objectDescription}.")
            appendLine("1.2. Valor total estimado: R$ ${String.format(java.util.Locale("pt", "BR"), "%,.2f", o.estimatedValue)}. Critério de julgamento: menor preço.")
            appendLine()
            appendLine("2. DOS PRAZOS")
            appendLine("2.1. Prazo de instalação/entrega: até ${if (o.segment == EQUIPAMENTOS) 30 else 45} dias corridos após a ordem de serviço.")
            appendLine("2.2. Vigência contratual de 12 meses, prorrogável na forma da Lei nº 14.133/2021.")
            appendLine()
            appendLine("3. DO NÍVEL DE SERVIÇO")
            appendLine("3.1. $sla")
            if (o.requiresLocalSupport) appendLine("3.2. A contratada deverá manter suporte técnico presencial na região de ${o.city}/${o.uf}.")
            appendLine()
            appendLine("4. DA HABILITAÇÃO")
            appendLine("4.1. Habilitação jurídica, regularidade fiscal (Federal, Estadual, Municipal, FGTS) e trabalhista (CNDT).")
            appendLine("4.2. Qualificação técnica: $qualification")
            appendLine("4.3. Qualificação econômico-financeira: balanço patrimonial do último exercício.")
            appendLine()
            appendLine("5. DAS SANÇÕES")
            appendLine("5.1. Multa de 0,5% ao dia sobre o valor mensal por descumprimento do nível de serviço, limitada a 10%.")
            appendLine("5.2. Multa de até 20% do valor do contrato em caso de inexecução total.")
            appendLine()
            append("6. DA GARANTIA CONTRATUAL: 5% do valor do contrato, em qualquer das modalidades legais.")
        }
        return TenderDetails(o, text, items)
    }
}
