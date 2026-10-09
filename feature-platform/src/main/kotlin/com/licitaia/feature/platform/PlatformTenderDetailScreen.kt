package com.licitaia.feature.platform

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.platform.net.PisoItemRequest
import com.licitaia.core.platform.net.PlatformFile
import com.licitaia.core.platform.net.PlatformItem
import com.licitaia.core.platform.net.TenderDto
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors

@Composable
fun PlatformTenderDetailScreen(viewModel: PlatformTenderDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val roboRun by viewModel.roboRun.collectAsStateWithLifecycle()
    val propostaRun by viewModel.propostaRun.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { navigator.showMessage(it) }
    }
    // O robô de proposta pediu a tela do portal (ou o usuário tocou "Ver no portal"): abre o Compras.gov.br
    // no app, onde o usuário assiste o robô trabalhando — mesma tela do modo local.
    LaunchedEffect(Unit) {
        viewModel.abrirPortal.collect { navigator.navigate(Routes.portalWeb(com.licitaia.domain.model.Portal.COMPRAS_GOV)) }
    }

    LicitaScaffold(title = "Detalhe da licitação", subtitle = "Plataforma", showBack = true) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LicitaColors.Blue)
                }
                state.error != null -> ErrorState(message = state.error!!, onRetry = viewModel::load)
                state.tender != null -> TenderDetail(
                    s = state,
                    onFavorite = viewModel::toggleFavorita,
                    onArchive = viewModel::toggleArquivar,
                    onHide = viewModel::toggleOcultar,
                    onAnalyze = viewModel::analyze,
                    onGerarProposta = viewModel::gerarProposta,
                    onAprovarProposta = viewModel::aprovarProposta,
                    onExcluirProposta = viewModel::excluirProposta,
                    onEditarProposta = viewModel::editarProposta,
                    onMsgInput = viewModel::onMsgInput,
                    onSendMsg = viewModel::sendMessage,
                    onArmarRobo = viewModel::armarRobo,
                    onParticiparRobo = viewModel::participarRobo,
                    onPrepararRobo = viewModel::prepararRobo,
                    roboRun = roboRun,
                    onIniciarRoboLocal = viewModel::iniciarRoboLocal,
                    onPararRoboLocal = viewModel::pararRoboLocal,
                    onEnviarSugestao = viewModel::enviarSugestao,
                    propostaRun = propostaRun,
                    onCadastrarProposta = viewModel::abrirCadastroProposta,
                    onPararProposta = viewModel::pararRoboProposta,
                    onVerNoPortal = viewModel::verNoPortal,
                    onPedirLancamento = viewModel::pedirLancamento,
                    onRegisterResult = viewModel::registrarResultado,
                    onOpenQa = { navigator.navigate(Routes.platformTenderQa(state.tender!!.id)) },
                    onOpenLive = { navigator.navigate(Routes.platformLive(state.tender!!.id)) },
                    onOpenPortal = { url ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .onFailure { navigator.showMessage("Não foi possível abrir o portal.") }
                    },
                )
            }
            val t = state.tender
            state.propostaRoboItens?.let { candidatos ->
                if (t != null) {
                    PropostaRoboDialog(
                        titulo = listOfNotNull(t.numero.takeIf { it.isNotBlank() }, t.orgao.takeIf { it.isNotBlank() }).joinToString(" · "),
                        subtitulo = t.objeto,
                        candidatos = candidatos, busy = state.propostaRoboBusy,
                        onDismiss = viewModel::fecharCadastroProposta,
                        onConfirm = viewModel::soltarRoboProposta,
                    )
                }
            }
        }
    }
}

@Composable
private fun RoboCard(
    s: PlatformDetailUi,
    onArmarRobo: (Double, Double, String?, Int?, List<PisoItemRequest>, Boolean) -> Unit,
    onParticiparRobo: () -> Unit,
    onPrepararRobo: () -> Unit,
    roboRun: com.licitaia.feature.live.automation.RobotRun?,
    onIniciarRoboLocal: () -> Unit,
    onPararRoboLocal: () -> Unit,
    onEnviarSugestao: () -> Unit,
) {
    val robo = s.roboConfig
    var showConfig by rememberSaveable { mutableStateOf(false) }
    var confirmAuto by rememberSaveable { mutableStateOf(false) }
    // Campos editáveis (piso é obrigatório).
    var piso by rememberSaveable(robo?.valorMinimo) { mutableStateOf(robo?.valorMinimo?.takeIf { it > 0 }?.let { fmtBR(it) } ?: "") }
    var decremento by rememberSaveable(robo?.decremento) { mutableStateOf(robo?.decremento?.takeIf { it > 0 }?.let { fmtBR(it) } ?: "") }
    var intervalo by rememberSaveable(robo?.intervaloSegundos) { mutableStateOf(robo?.intervaloSegundos?.toString() ?: "30") }
    var estrategia by rememberSaveable(robo?.estrategia) { mutableStateOf(robo?.estrategia ?: "conservadora") }
    // Piso POR ITEM (numero → texto), pré-preenchido com o valor já salvo em cada item.
    val itemFloors = remember(s.itens, robo?.valorMinimo) {
        mutableStateMapOf<Int, String>().apply {
            // Piso salvo do item; com UM item só, cai no piso geral salvo (era gravado assim antes).
            val umItem = s.itens.count { it.numero != null } == 1
            s.itens.forEach { pi -> pi.numero?.let { n ->
                val v = pi.valorLanceMinimo?.toDoubleOrNull()?.takeIf { it > 0 } ?: robo?.valorMinimo?.takeIf { umItem && it > 0 }
                put(n, v?.let { fmtBR(it) } ?: "")
            } }
        }
    }
    // Itens com nº (os únicos que entram em pisosItens). Monta a lista a partir do que o usuário digitou.
    val itensComNumero = s.itens.filter { it.numero != null }
    fun pisosItens(): List<PisoItemRequest> = itensComNumero.mapNotNull { pi ->
        val num = pi.numero ?: return@mapNotNull null
        val v = parseValor(itemFloors[num])?.takeIf { it > 0 } ?: return@mapNotNull null
        PisoItemRequest(num, v)
    }
    // valorMinimo (obrigatório): MENOR piso informado; se nenhum item tiver piso, usa o "piso geral".
    fun valorMinimo(): Double? = pisosItens().minOfOrNull { it.valorLanceMinimo } ?: parseValor(piso)?.takeIf { it > 0 }
    fun decrementoVal(): Double = parseValor(decremento)?.takeIf { it > 0 } ?: 0.01
    var showNuvem by rememberSaveable { mutableStateOf(false) }

    // Robô do APARELHO está armado quando há piso salvo (o "ativo" da VPS é do robô da nuvem).
    val armed = (robo?.valorMinimo ?: 0.0) > 0
    val roboAccent = if (armed) LicitaColors.Green else if (robo != null) LicitaColors.Yellow else null
    LicitaCard(Modifier.fillMaxWidth(), accent = roboAccent) {
        // Cabeçalho no mesmo formato do robô local (bolha de ícone + título + status pulsante).
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Outlined.SmartToy, roboAccent ?: LicitaColors.Blue)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Robô de lance", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                Text(
                    if (robo != null) "Disputa no aparelho · certificado local" else "Sem configuração nesta licitação",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
            }
            if (robo != null) StatusBadge(if (armed) "Armado" else "Desarmado", if (armed) Tone.SUCCESS else Tone.NEUTRAL, pulsing = armed)
        }
        Spacer(Modifier.height(10.dp))
        InfoRow("Modo", "Manual — o robô sugere, você toca Enviar")
        if (robo?.modoExecucao == "auto") {
            Spacer(Modifier.height(6.dp))
            AlertBanner(
                "Lance automático na NUVEM ligado",
                "O robô do servidor está armado para dar lances sozinho ao Participar. Veja \"Robô na nuvem (avançado)\".",
                Tone.DANGER,
            )
        }
        if (robo != null) {
            robo.estrategia?.let { InfoRow("Estratégia", estrategiaLabel(it)) }
            InfoRow("Piso (valor mínimo)", robo.valorMinimo?.takeIf { it > 0 }?.let { PlatformFormat.currency(it.toString()) } ?: "não definido")
            InfoRow("Decremento", robo.decremento?.takeIf { it > 0 }?.let { PlatformFormat.currency(it.toString()) } ?: "não definido")
            robo.intervaloSegundos?.let { InfoRow("Intervalo entre lances", "$it s") }
            InfoRow("Lances registrados", "${s.roboLances}")
        } else {
            Text("Robô ainda não armado nesta licitação.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
        }
        if (!armed) {
            Spacer(Modifier.height(4.dp))
            Text("Arme o robô com o piso de cada item antes de iniciar.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
        }
        s.prontidao?.let { p ->
            Spacer(Modifier.height(6.dp))
            InfoRow("Prontidão", if (p.ok) "Pronto" else (p.estado ?: "bloqueado"))
            if (!p.ok && p.motivos.isNotEmpty()) {
                Text("Pendências: " + p.motivos.joinToString("; "), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            }
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            if (armed) "Editar configuração do robô" else "Armar robô", { showConfig = true }, Modifier.fillMaxWidth(),
            enabled = !s.roboBusy, icon = Icons.Outlined.SmartToy, tone = Tone.SUCCESS,
        )

        // Prévia do lance: cálculo LOCAL a partir da config; NADA é enviado ao portal.
        val pisoAtual = robo?.valorMinimo?.takeIf { it > 0 }
        if (pisoAtual != null) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = LicitaColors.Outline)
            Text("Prévia do lance (só cálculo, nada é enviado)", style = MaterialTheme.typography.labelLarge, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            var bestInput by rememberSaveable { mutableStateOf("") }
            var preview by rememberSaveable { mutableStateOf<String?>(null) }
            androidx.compose.material3.OutlinedTextField(
                value = bestInput,
                onValueChange = { bestInput = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                label = { Text("Melhor lance atual (R$)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            SecondaryButton("Simular lance", { preview = simularLance(pisoAtual, robo?.decremento, parseValor(bestInput)?.toString() ?: "") }, Modifier.fillMaxWidth(), tone = Tone.INFO)
            preview?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
            }
        }

        // Execução ON-DEVICE (modo MANUAL): roda o motor local reusado; lê a sala e SUGERE. Só envia quando o
        // usuário toca "Enviar" na sugestão (mesmas travas de piso/intervalo do modo local). Nunca envia sozinho.
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = LicitaColors.Outline)
        Text("Disputa no aparelho", style = MaterialTheme.typography.labelLarge, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        if (roboRun != null) {
            StatusBadge(roboRun.status.name.lowercase().replace('_', ' '), Tone.INFO)
            // Enquanto não há passo/sugestão, deixa claro que está aguardando a disputa/sessão.
            if (roboRun.step.isNullOrBlank() && roboRun.message.isNullOrBlank()) {
                Text(
                    "Aguardando a disputa abrir. Se o portal pedir login, entre no Comprasnet em Portais — o robô retoma sozinho.",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
            }
            roboRun.step?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary) }
            roboRun.message?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted) }
            roboRun.log.takeLast(4).forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted) }
            // Sugestão atual (modo manual): o lance só sai quando o usuário toca "Enviar".
            roboRun.suggestion?.takeIf { roboRun.active }?.let { sug ->
                Spacer(Modifier.height(8.dp))
                AlertBanner(
                    "Sugestão — item ${sug.itemNumber}: ${PlatformFormat.currency(sug.value.toString())}", sug.reason, Tone.INFO,
                    actionLabel = "Enviar", onAction = onEnviarSugestao,
                )
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Parar robô", onPararRoboLocal, Modifier.fillMaxWidth(), tone = Tone.DANGER)
        } else {
            Text(
                "Inicia a disputa NO APARELHO com o seu certificado local: o robô lê a sala e SUGERE o lance (respeitando o piso); " +
                    "o lance só é enviado quando você toca \"Enviar\" na sugestão. (Precisa da sessão do Comprasnet logada em Portais; se faltar, o robô pede.)",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Iniciar robô no aparelho", onIniciarRoboLocal, Modifier.fillMaxWidth(), enabled = !s.roboBusy && armed, tone = Tone.SUCCESS)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "No aparelho o robô sugere e você decide cada envio. O certificado nunca sai do aparelho.",
            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
        )

        // Robô da NUVEM (servidor): recolhido — o caminho principal é o robô do aparelho.
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = LicitaColors.Outline)
        Row(
            Modifier.fillMaxWidth().clickable { showNuvem = !showNuvem }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Robô na nuvem (avançado)", style = MaterialTheme.typography.labelLarge, color = LicitaColors.TextSecondary, modifier = Modifier.weight(1f))
            Text(if (showNuvem) "ocultar" else "mostrar", style = MaterialTheme.typography.labelMedium, color = LicitaColors.Blue)
        }
        if (showNuvem) {
            Text(
                "Roda no SERVIDOR da plataforma, não no seu celular. Preparar confere o portal; Participar coloca o robô do servidor " +
                    "na disputa. Só dá lances sozinho se o lance automático da nuvem estiver ligado.",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Preparar", onPrepararRobo, Modifier.weight(1f), enabled = !s.roboBusy, tone = Tone.NEUTRAL)
                SecondaryButton("Participar", onParticiparRobo, Modifier.weight(1f), enabled = !s.roboBusy, tone = Tone.INFO)
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                "Lance automático pela nuvem…", { if (valorMinimo()?.let { it > 0 } == true) confirmAuto = true else showConfig = true },
                Modifier.fillMaxWidth(), enabled = !s.roboBusy, tone = Tone.DANGER,
            )
        }
    }

    if (showConfig) {
        AlertDialog(
            onDismissRequest = { showConfig = false },
            title = { Text("Armar robô") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Estratégia", style = MaterialTheme.typography.labelLarge, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    // Grade 2×2, botões do mesmo tamanho e rótulo em uma linha só.
                    ESTRATEGIAS.chunked(2).forEach { linha ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            linha.forEach { (v, l) ->
                                androidx.compose.material3.FilterChip(
                                    selected = estrategia == v, onClick = { estrategia = v }, modifier = Modifier.weight(1f),
                                    label = { Text(l, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                                )
                            }
                        }
                    }
                    Text(estrategiaDescricao(estrategia), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.OutlinedTextField(decremento, { decremento = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, label = { Text("Decremento (R$)") }, singleLine = true, modifier = Modifier.weight(1f))
                        androidx.compose.material3.OutlinedTextField(intervalo, { intervalo = it.filter { c -> c.isDigit() } }, label = { Text("Intervalo (s)") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Text(
                        "Decremento: diferença mínima entre lances exigida no edital (ex.: R$ 10,00). Intervalo: mínimo de 20 s entre lances seus (regra do Compras.gov).",
                        style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (itensComNumero.isEmpty()) {
                        // Sem itens com nº: cai para um piso geral único (obrigatório).
                        androidx.compose.material3.OutlinedTextField(piso, { piso = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, label = { Text("Piso / valor mínimo (R$)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text("Piso por item (lance mínimo)", style = MaterialTheme.typography.labelLarge, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            "O menor valor que você aceita em cada item. O robô nunca sugere nem envia lance abaixo dele.",
                            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                        )
                        itensComNumero.forEach { pi ->
                            val num = pi.numero!!
                            Spacer(Modifier.height(10.dp))
                            Text("Item $num" + (pi.descricao?.let { semRepeticao(it) }?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val meta = listOfNotNull(
                                pi.quantidade?.takeIf { it.isNotBlank() }?.let { "Qtd $it" },
                                pi.unidade?.takeIf { it.isNotBlank() }?.let { "un: $it" },
                                pi.valor?.takeIf { it.isNotBlank() }?.let { "ref " + PlatformFormat.currency(it) },
                            )
                            if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                            androidx.compose.material3.OutlinedTextField(
                                value = itemFloors[num].orEmpty(),
                                onValueChange = { itemFloors[num] = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                                label = { Text("Piso do item $num (R$)") },
                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "No dia, o robô do aparelho lê a sala e sugere cada lance a partir desses valores; o lance só sai quando você toca Enviar.",
                        style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                    )
                }
            },
            confirmButton = {
                PrimaryButton("Armar robô", {
                    val vm = valorMinimo()
                    if (vm != null && vm > 0) {
                        onArmarRobo(vm, decrementoVal(), estrategia.trim().ifBlank { null }, intervalo.trim().toIntOrNull(), pisosItens(), false)
                        showConfig = false
                    }
                }, enabled = valorMinimo()?.let { it > 0 } == true)
            },
            dismissButton = { SecondaryButton("Cancelar", { showConfig = false }, tone = Tone.NEUTRAL) },
        )
    }

    if (confirmAuto) {
        val faltamPisos = itensComNumero.isNotEmpty() && pisosItens().size < itensComNumero.size
        AlertDialog(
            onDismissRequest = { confirmAuto = false },
            title = { Text("Ligar lance automático na NUVEM?") },
            text = {
                Column {
                    Text(
                        "Isto arma o robô do SERVIDOR da plataforma para DAR LANCES REAIS SOZINHO quando você tocar Participar, " +
                            "respeitando o piso de cada item. Não é o robô do seu celular. Confirma?",
                    )
                    if (faltamPisos) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Atenção: para LANCE REAL o backend exige o piso de TODOS os itens. Faltam ${itensComNumero.size - pisosItens().size} item(ns) — preencha-os ou o servidor vai recusar.",
                            style = MaterialTheme.typography.labelSmall, color = LicitaColors.Red,
                        )
                    }
                }
            },
            confirmButton = {
                PrimaryButton("Sim, lance automático na nuvem", {
                    val vm = valorMinimo()
                    if (vm != null && vm > 0) onArmarRobo(vm, decrementoVal(), estrategia.trim().ifBlank { null }, intervalo.trim().toIntOrNull(), pisosItens(), true)
                    confirmAuto = false
                }, tone = Tone.DANGER)
            },
            dismissButton = { SecondaryButton("Cancelar", { confirmAuto = false }, tone = Tone.NEUTRAL) },
        )
    }
}

private fun fmtNum(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

/** "Acesso X Acesso X" (descrição duplicada pelo portal) → "Acesso X". */
private fun semRepeticao(s: String): String {
    val t = s.trim().replace(Regex("\\s+"), " ")
    val meio = t.length / 2
    if (t.length >= 8 && t.length % 2 == 1 && t[meio] == ' ' && t.substring(0, meio) == t.substring(meio + 1)) return t.substring(0, meio)
    return t
}

/** 98700.0 → "98.700,00" (padrão brasileiro, sem "R$"). */
private fun fmtBR(v: Double): String = String.format(java.util.Locale("pt", "BR"), "%,.2f", v)

/** "1.500,00" (BR) ou "1500.5": com vírgula, ponto é milhar; sem vírgula, ponto é decimal. */
private fun parseValor(s: String?): Double? {
    val t = s?.trim().orEmpty()
    if (t.isEmpty()) return null
    val n = if (t.contains(',')) t.replace(".", "").replace(',', '.') else t
    return n.toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** Estratégias do robô (valor gravado na VPS → rótulo), no padrão da tela Estratégias. */
private val ESTRATEGIAS = listOf(
    "conservadora" to "Conservadora",
    "agressiva" to "Agressiva",
    "acompanhar_concorrente" to "Acompanhar",
    "personalizada" to "Personalizada",
)

private fun estrategiaLabel(v: String): String = ESTRATEGIAS.firstOrNull { it.first == v }?.second ?: v.replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun estrategiaDescricao(v: String): String = when (v) {
    "conservadora" -> "Só reage quando perde a 1ª posição e cobre pelo decremento mínimo. Poucos lances, preserva margem."
    "agressiva" -> "Cobre o melhor lance rapidamente, com mais lances, sempre respeitando o piso."
    "acompanhar_concorrente" -> "Segue o líder de perto, cobrindo cada lance dele pelo decremento."
    "personalizada" -> "Usa o decremento e o intervalo que você definir."
    else -> "Estratégia atual: ${estrategiaLabel(v)}. Escolha uma acima para trocar."
}

/**
 * Prévia (dry_run) do próximo lance, cálculo LOCAL e transparente: cobre o melhor lance pelo decremento,
 * NUNCA abaixo do piso. Não é o motor completo de estratégia e NADA é enviado ao portal — é só a decisão base.
 */
private fun simularLance(piso: Double, decremento: Double?, bestInput: String): String {
    val best = bestInput.trim().toDoubleOrNull()
    if (best == null || best <= 0.0) return "Informe o melhor lance atual para simular."
    val dec = decremento?.takeIf { it > 0 } ?: 0.01
    val proximo = best - dec
    val pisoFmt = PlatformFormat.currency(piso.toString())
    return if (proximo < piso) {
        "No piso: cobrir ${PlatformFormat.currency(best.toString())} exigiria ${PlatformFormat.currency(proximo.toString())}, abaixo do piso $pisoFmt. O robô PARARIA (não daria lance)."
    } else {
        "O robô daria ${PlatformFormat.currency(proximo.toString())} (cobre ${PlatformFormat.currency(best.toString())}; decremento ${PlatformFormat.currency(dec.toString())}; piso $pisoFmt respeitado). Só cálculo: nada é enviado ao portal."
    }
}

@Composable
private fun PropostaRow(
    p: com.licitaia.core.platform.net.PropostaDto,
    enabled: Boolean,
    onAprovar: () -> Unit,
    onExcluir: () -> Unit,
    onEditar: (String, Double?, String?) -> Unit,
    onPedirLancamento: () -> Unit,
) {
    var editing by rememberSaveable(p.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(PlatformFormat.currencyOrDash(p.valorTotal), style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            p.status?.let { StatusBadge(it.replace('_', ' '), if (it == "aceita") Tone.SUCCESS else Tone.INFO) }
        }
        p.createdAt?.let { Text(PlatformFormat.dateTime(it), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Editar", { editing = true }, Modifier.weight(1f), enabled = enabled, tone = Tone.NEUTRAL)
            if (!p.status.equals("revisada", true)) {
                SecondaryButton("Aprovar", onAprovar, Modifier.weight(1f), enabled = enabled, tone = Tone.SUCCESS)
            }
            SecondaryButton("Excluir", onExcluir, Modifier.weight(1f), enabled = enabled, tone = Tone.DANGER)
        }
        Spacer(Modifier.height(6.dp))
        SecondaryButton("Pedir para lançar", onPedirLancamento, Modifier.fillMaxWidth(), enabled = enabled, tone = Tone.WARNING)
    }
    if (editing) {
        PropostaEditDialog(p, onDismiss = { editing = false }, onSave = { v, st -> onEditar(p.id, v, st); editing = false })
    }
}

private val PROPOSTA_STATUS = listOf("rascunho", "gerada_ia", "revisada", "enviada", "aceita", "recusada")

@Composable
private fun PropostaEditDialog(p: com.licitaia.core.platform.net.PropostaDto, onDismiss: () -> Unit, onSave: (Double?, String?) -> Unit) {
    var valor by rememberSaveable { mutableStateOf(p.valorTotal?.filter { it.isDigit() || it == '.' } ?: "") }
    var status by rememberSaveable { mutableStateOf(p.status ?: "gerada_ia") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar proposta") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(valor, { valor = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Valor total") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("Status", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PROPOSTA_STATUS.forEach { opt ->
                        com.licitaia.core.ui.components.SelectChip(opt.replace('_', ' '), status == opt, { status = opt })
                    }
                }
            }
        },
        confirmButton = { PrimaryButton("Salvar", { onSave(valor.trim().toDoubleOrNull(), status) }) },
        dismissButton = { SecondaryButton("Cancelar", onDismiss, tone = Tone.NEUTRAL) },
    )
}

@Composable
private fun MensagensCard(mensagens: List<com.licitaia.core.platform.net.MensagemDto>, input: String, sending: Boolean, onInput: (String) -> Unit, onSend: () -> Unit) {
    val focus = LocalFocusManager.current
    LicitaCard(Modifier.fillMaxWidth()) {
        Text("Mensagens (${mensagens.size})", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        if (mensagens.isEmpty()) {
            Text("Nenhuma mensagem nesta licitação. Envie uma abaixo.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
        } else {
            mensagens.take(50).forEachIndexed { i, m ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                Text(m.displayTitle, style = MaterialTheme.typography.labelMedium, color = LicitaColors.BlueBright, fontWeight = FontWeight.SemiBold)
                if (m.displayBody.isNotBlank()) Text(m.displayBody, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                m.createdAt?.let { Text(PlatformFormat.dateTime(it), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted) }
            }
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.OutlinedTextField(
            value = input,
            onValueChange = onInput,
            label = { Text("Escreva uma mensagem") },
            enabled = !sending,
            trailingIcon = {
                androidx.compose.material3.IconButton(onClick = { focus.clearFocus(); onSend() }, enabled = !sending && input.isNotBlank()) {
                    if (sending) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp, color = LicitaColors.Blue)
                    else Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Enviar")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { focus.clearFocus(); onSend() }),
            // Consome o ENTER para NUNCA navegar/voltar (mesmo cuidado do campo de busca).
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                if (e.key == Key.Enter || e.key == Key.NumPadEnter) {
                    if (e.type == KeyEventType.KeyUp) { focus.clearFocus(); onSend() }
                    true
                } else {
                    false
                }
            },
        )
    }
}

/** Um item do edital como bloco legível: nº, linha Qtd·Un·Valor destacada e descrição recolhível. */
@Composable
private fun ItemRow(index: Int, item: PlatformItem) {
    var expanded by rememberSaveable(index) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text("Item ${index + 1}", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        val meta = listOfNotNull(
            item.quantidade?.takeIf { it.isNotBlank() }?.let { "Qtd $it" },
            item.unidade?.takeIf { it.isNotBlank() }?.let { "un: $it" },
            item.valor?.takeIf { it.isNotBlank() }?.let { PlatformFormat.currency(it) },
        )
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.labelLarge, color = LicitaColors.BlueBright, fontWeight = FontWeight.SemiBold)
        }
        val desc = item.descricao?.trim()
        if (!desc.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = LicitaColors.TextSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            )
            if (desc.length > 120) {
                Text(
                    if (expanded) "ver menos" else "ver mais",
                    style = MaterialTheme.typography.labelMedium,
                    color = LicitaColors.Blue,
                    modifier = Modifier.padding(top = 2.dp).clickable { expanded = !expanded },
                )
            }
        }
    }
}

@Composable
private fun TenderDetail(
    s: PlatformDetailUi,
    onFavorite: () -> Unit,
    onArchive: () -> Unit,
    onHide: () -> Unit,
    onAnalyze: () -> Unit,
    onGerarProposta: () -> Unit,
    onAprovarProposta: (String) -> Unit,
    onExcluirProposta: (String) -> Unit,
    onEditarProposta: (String, Double?, String?) -> Unit,
    onMsgInput: (String) -> Unit,
    onSendMsg: () -> Unit,
    onArmarRobo: (Double, Double, String?, Int?, List<PisoItemRequest>, Boolean) -> Unit,
    onParticiparRobo: () -> Unit,
    onPrepararRobo: () -> Unit,
    roboRun: com.licitaia.feature.live.automation.RobotRun?,
    onIniciarRoboLocal: () -> Unit,
    onPararRoboLocal: () -> Unit,
    onEnviarSugestao: () -> Unit,
    propostaRun: com.licitaia.feature.live.automation.RobotRun?,
    onCadastrarProposta: () -> Unit,
    onPararProposta: () -> Unit,
    onVerNoPortal: () -> Unit,
    onPedirLancamento: (com.licitaia.core.platform.net.PropostaDto) -> Unit,
    onRegisterResult: (String) -> Unit,
    onOpenQa: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenPortal: (String) -> Unit,
) {
    val t = s.tender ?: return
    val itens = s.itens
    val arquivos = s.arquivos
    val acting = s.acting
    var showProposta by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (s.fromCache) {
            AlertBanner("Offline", "Mostrando a cópia salva; ações e detalhes completos voltam ao reconectar.", Tone.INFO)
        }

        // Cabeçalho + objeto
        LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Blue) {
            Text(t.numero.ifBlank { "Licitação" }, style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text(t.orgao, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                t.fase?.let { StatusBadge(it.replace('_', ' '), Tone.INFO) }
                t.status?.let { StatusBadge(it, if (it.equals("ativa", true)) Tone.SUCCESS else Tone.WARNING) }
                if (t.favorita) StatusBadge("interesse", Tone.SUCCESS)
            }
            Spacer(Modifier.height(10.dp))
            Text(t.objeto, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
        }

        // Ações (VPS)
        LicitaCard(Modifier.fillMaxWidth()) {
            Text("Ações", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    if (t.favorita) "Remover interesse" else "Marcar interesse",
                    onFavorite, Modifier.fillMaxWidth(), enabled = !acting, tone = Tone.SUCCESS,
                    icon = if (t.favorita) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                )
                SecondaryButton(
                    if (t.status.equals("arquivada", true)) "Desarquivar" else "Arquivar",
                    onArchive, Modifier.fillMaxWidth(), enabled = !acting, tone = Tone.NEUTRAL,
                    icon = Icons.Outlined.Archive,
                )
                SecondaryButton(
                    if (t.status.equals("oculta", true)) "Reexibir" else "Ocultar",
                    onHide, Modifier.fillMaxWidth(), enabled = !acting, tone = Tone.NEUTRAL,
                    icon = Icons.Outlined.VisibilityOff,
                )
                // Prefere o link OPERACIONAL do Comprasnet (urlProposta: abre a compra/proposta) e só cai na
                // publicação do PNCP (portalUrl) se não houver — assim "Abrir no portal" leva ao lugar certo.
                (t.urlProposta?.takeIf { it.isNotBlank() } ?: t.portalUrl?.takeIf { it.isNotBlank() })?.let { url ->
                    SecondaryButton("Abrir no portal", { onOpenPortal(url) }, Modifier.fillMaxWidth(), tone = Tone.INFO, icon = Icons.Outlined.OpenInNew)
                }
            }
        }

        // Dados
        LicitaCard(Modifier.fillMaxWidth()) {
            Text("Dados", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            InfoRow("Modalidade", t.modalidade ?: "—")
            t.modoDisputa?.let { InfoRow("Modo de disputa", it) }
            InfoRow("Valor estimado", PlatformFormat.currency(t.valorEstimado))
            InfoRow("Publicação", PlatformFormat.dateTime(t.dataPublicacao))
            InfoRow("Abertura", PlatformFormat.dateTime(t.dataAbertura))
            InfoRow("Encerramento", PlatformFormat.dateTime(t.dataEncerramento))
            t.uasg?.let { InfoRow("UASG", it) }
            InfoRow("Local", listOfNotNull(t.cidade, t.estado).joinToString("/").ifBlank { "—" })
            t.portal?.let { InfoRow("Portal", it) }
            InfoRow("Participação", if (!t.urlProposta.isNullOrBlank()) "Proposta vinculada" else "Sem proposta vinculada")
        }

        // Vale a pena participar? / Análise do edital por IA (ler + gerar)
        LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Yellow) {
            Text("Vale a pena? · Análise do edital (IA)", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            t.veredito?.takeIf { it.isNotBlank() }?.let { InfoRow("Veredito", it) }
            t.scoreRelevancia?.let { InfoRow("Relevância", "$it") }
            t.scoreRisco?.let { InfoRow("Risco", "$it") }
            // Preço/margem: edital sigiloso vem 0/nulo → mostramos "—" (não "R$ 0,00").
            t.precoSugeridoIA?.takeIf { it.isNotBlank() }?.let { InfoRow("Preço sugerido (IA)", PlatformFormat.currencyOrDash(it)) }
            t.margemEstimadaIA?.takeIf { it.isNotBlank() }?.let { InfoRow("Margem estimada (IA)", PlatformFormat.numberOrDash(it, "%")) }
            val resumo = t.editalResumoIA?.takeIf { it.isNotBlank() }
            Spacer(Modifier.height(6.dp))
            Text(
                resumo ?: "Nenhuma análise da plataforma ainda. Toque em \"Analisar com IA\" para gerar (roda no servidor).",
                style = MaterialTheme.typography.bodySmall, color = if (resumo != null) LicitaColors.TextSecondary else LicitaColors.TextMuted,
            )
            if (t.editalTexto.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("Sem texto do edital ainda — a IA usará os metadados. Você pode abrir no portal para obter o edital.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            }
            if (s.analyzing) {
                Spacer(Modifier.height(10.dp))
                AlertBanner("Analisando no aparelho…", "${s.analysisStatus ?: "Rodando a IA local"} — pode levar até ~1-2 min. Usa a sua chave de IA; nenhuma chave sobe para a nuvem.", Tone.INFO, pulsing = true)
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth(), color = LicitaColors.Blue, trackColor = LicitaColors.Outline)
            }
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                if (s.analyzing) "Analisando…" else if (t.editalResumoIA.isNullOrBlank()) "Analisar com IA (no aparelho)" else "Reanalisar com IA",
                onAnalyze, Modifier.fillMaxWidth(), enabled = !s.analyzing, tone = Tone.INFO, icon = Icons.Outlined.Analytics,
            )
        }

        // Proposta comercial (gerada no aparelho; metadados/gestão na VPS)
        LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Green) {
            Text("Proposta comercial (${s.propostas.size})", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            if (s.propostas.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    s.proposalSummary ?: "Gere uma proposta inicial com IA no aparelho (usa a sua chave). O conteúdo fica no aparelho; os metadados são salvos na plataforma.",
                    style = MaterialTheme.typography.bodySmall, color = if (s.proposalSummary != null) LicitaColors.TextSecondary else LicitaColors.TextMuted,
                )
            } else {
                s.propostas.forEachIndexed { i, p ->
                    if (i == 0) Spacer(Modifier.height(8.dp)) else HorizontalDivider(Modifier.padding(vertical = 10.dp), color = LicitaColors.Outline)
                    PropostaRow(
                        p, enabled = !s.acting, onAprovar = { onAprovarProposta(p.id) }, onExcluir = { onExcluirProposta(p.id) },
                        onEditar = onEditarProposta, onPedirLancamento = { onPedirLancamento(p) },
                    )
                }
            }
            if (s.generatingProposal) {
                Spacer(Modifier.height(10.dp))
                AlertBanner("Gerando proposta no aparelho…", "Rodando a IA local. Pode levar até ~1-2 min.", Tone.INFO, pulsing = true)
            }
            if (s.proposalContent != null) {
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Ver proposta", { showProposta = true }, Modifier.fillMaxWidth(), tone = Tone.INFO, icon = Icons.Outlined.Description)
            }
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                if (s.generatingProposal) "Gerando…" else "Gerar proposta (IA)",
                onGerarProposta, Modifier.fillMaxWidth(), enabled = !s.generatingProposal, tone = Tone.SUCCESS, icon = Icons.Outlined.RequestQuote,
            )
        }

        // Mensagens desta licitação (enviar + listar)
        MensagensCard(
            mensagens = s.mensagens,
            input = s.msgInput,
            sending = s.sendingMsg,
            onInput = onMsgInput,
            onSend = onSendMsg,
        )

        // Pergunte ao edital (Q&A por IA)
        LicitaCard(Modifier.fillMaxWidth(), onClick = onOpenQa) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.QuestionAnswer, contentDescription = null, tint = LicitaColors.Blue)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Pergunte ao edital", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Tire dúvidas sobre prazos, exigências e documentos (IA)", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = LicitaColors.TextMuted)
            }
        }

        // Linha do tempo (derivada das datas e fase)
        LicitaCard(Modifier.fillMaxWidth()) {
            Text("Linha do tempo", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            InfoRow("Publicação", PlatformFormat.dateTime(t.dataPublicacao))
            InfoRow("Abertura", PlatformFormat.dateTime(t.dataAbertura))
            InfoRow("Encerramento", PlatformFormat.dateTime(t.dataEncerramento))
            t.fase?.let { InfoRow("Fase atual", it.replace('_', ' ')) }
        }

        // Itens
        LicitaCard(Modifier.fillMaxWidth()) {
            Text("Itens (${itens.size})", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            if (itens.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Sem itens carregados na plataforma. Abra no portal para ver a íntegra.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
            } else {
                itens.take(50).forEachIndexed { i, it ->
                    if (i == 0) Spacer(Modifier.height(10.dp)) else HorizontalDivider(Modifier.padding(vertical = 12.dp), color = LicitaColors.Outline)
                    ItemRow(i, it)
                }
            }
        }

        // Documentos / arquivos
        LicitaCard(Modifier.fillMaxWidth()) {
            Text("Documentos (${arquivos.size})", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            if (arquivos.isEmpty()) {
                Text("Nenhum arquivo do edital disponível na plataforma.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
            } else {
                arquivos.forEachIndexed { i, a ->
                    if (i > 0) Spacer(Modifier.height(4.dp))
                    Text(a.nome ?: "Arquivo ${i + 1}", style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
                    a.tipo?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary) }
                }
            }
        }

        // Robô de PROPOSTA (no aparelho, mesmo motor do modo local): cadastra a proposta no Comprasnet.
        PropostaRoboCard(s, propostaRun, onCadastrar = onCadastrarProposta, onParar = onPararProposta, onVerNoPortal = onVerNoPortal)

        // Robô de lance (config/arme via VPS + execução on-device em dry_run reusando o motor local)
        RoboCard(
            s, onArmarRobo = onArmarRobo, onParticiparRobo = onParticiparRobo, onPrepararRobo = onPrepararRobo,
            roboRun = roboRun, onIniciarRoboLocal = onIniciarRoboLocal, onPararRoboLocal = onPararRoboLocal,
            onEnviarSugestao = onEnviarSugestao,
        )

        // Resultado do pregão
        LicitaCard(Modifier.fillMaxWidth()) {
            Text("Resultado do pregão", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            val res = s.resultado
            val resStr = res?.resultado
            if (res != null && resStr != null) {
                StatusBadge(resStr, if (resStr.equals("vencida", true)) Tone.SUCCESS else Tone.WARNING)
                res.valorArrematado?.let { InfoRow("Valor arrematado", PlatformFormat.currency(it)) }
                res.posicaoFinal?.let { InfoRow("Posição final", "$it") }
                res.totalConcorrentes?.let { InfoRow("Concorrentes", "$it") }
            } else {
                Text("Ainda sem resultado. Registre quando o pregão encerrar.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Vencemos", { onRegisterResult("vencida") }, Modifier.weight(1f), enabled = !acting, tone = Tone.SUCCESS)
                    SecondaryButton("Perdemos", { onRegisterResult("perdida") }, Modifier.weight(1f), enabled = !acting, tone = Tone.NEUTRAL)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Desistimos", { onRegisterResult("desistida") }, Modifier.weight(1f), enabled = !acting, tone = Tone.NEUTRAL)
                    SecondaryButton("Anulada", { onRegisterResult("anulada") }, Modifier.weight(1f), enabled = !acting, tone = Tone.NEUTRAL)
                }
            }
        }

        // Acompanhar pregão ao vivo (leitura em tempo real, polling)
        LicitaCard(Modifier.fillMaxWidth(), onClick = onOpenLive) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Bolt, contentDescription = null, tint = LicitaColors.Red)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Acompanhar pregão ao vivo", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Melhor lance, sua posição e eventos em tempo real (leitura)", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = LicitaColors.TextMuted)
            }
        }
    }

    if (showProposta && s.proposalContent != null) {
        AlertDialog(
            onDismissRequest = { showProposta = false },
            title = { Text("Proposta gerada") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(s.proposalContent!!, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                }
            },
            confirmButton = { PrimaryButton("Fechar", { showProposta = false }) },
        )
    }
}
