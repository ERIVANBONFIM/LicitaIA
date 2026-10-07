package com.licitaia.domain.model

/**
 * O que acontece depois do "Tenho interesse". Padrão (configuração desligada): só salva a licitação com os dados da
 * fonte, status [TenderStatus.INTERESSE] — nada de IA nem download. A análise roda quando o usuário pede ("Analisar
 * com IA" na licitação ou "Analisar" no card da busca). Com "Analisar automaticamente ao marcar interesse" ligado,
 * volta o comportamento antigo: baixa o edital oficial do PNCP (quando há número de controle) e analisa em segundo plano.
 */
object InterestFollowUp {

    data class Plan(val downloadOfficialEdital: Boolean, val analyze: Boolean) {
        val idle: Boolean get() = !downloadOfficialEdital && !analyze
    }

    fun plan(autoAnalyzeOnInterest: Boolean, hasPncpControl: Boolean): Plan =
        Plan(downloadOfficialEdital = autoAnalyzeOnInterest && hasPncpControl, analyze = autoAnalyzeOnInterest)

    /** Status com que a licitação nasce ao marcar interesse (nunca "Em análise" sem pedido). */
    val INITIAL_STATUS: TenderStatus = TenderStatus.INTERESSE

    /**
     * Licitação que ficou "Em análise" porque o processo morreu no meio da análise: não é retomada sozinha
     * (gastaria cota de IA sem pedido) — volta para "Analisada" (se já havia análise) ou "Interesse".
     */
    fun recoverInterrupted(hasAnalysis: Boolean): TenderStatus = if (hasAnalysis) TenderStatus.ANALISADA else TenderStatus.INTERESSE
}
