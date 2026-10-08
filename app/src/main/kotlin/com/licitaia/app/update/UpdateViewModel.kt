package com.licitaia.app.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.update.AppUpdate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Fases do diálogo de atualização. */
enum class UpdatePhase {
    /** Mostra notas, tamanho e os botões "Atualizar agora" / "Depois". */
    OFFER,
    /** Precisa autorizar "instalar apps desconhecidos" antes de baixar. */
    PERMISSION,
    DOWNLOADING,
    /** APK baixado e verificado; instalador aberto (botão para reabrir). */
    READY,
    ERROR,
}

data class UpdateUiState(
    val update: AppUpdate? = null,
    val phase: UpdatePhase = UpdatePhase.OFFER,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val file: File? = null,
    val error: String? = null,
    /** APK pronto e ainda não entregue ao instalador: a UI deve chamar [UpdateViewModel.reopenInstaller] uma vez. */
    val installRequested: Boolean = false,
) {
    val visible: Boolean get() = update != null
    val progress: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

/**
 * Orquestra a atualização: checagem automática ao logar, oferta, permissão de instalação,
 * download com cancelamento, verificação de assinatura e abertura do instalador.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val checker: UpdateChecker,
    private val downloader: ApkDownloader,
    private val installer: UpdateInstaller,
    authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** Atualização adiada: alimenta a faixa "Nova versão disponível · Atualizar" no topo do app. */
    val postponed: StateFlow<AppUpdate?> = checker.postponed

    private var downloadJob: Job? = null
    private var loggedIn = false

    init {
        // Diálogo global segue a atualização pendente publicada pelo checker (automática ou manual).
        viewModelScope.launch {
            checker.pending.collect { pending ->
                _state.update { current ->
                    when {
                        pending == null -> UpdateUiState()
                        current.update?.tag == pending.tag -> current
                        else -> UpdateUiState(update = pending)
                    }
                }
                if (pending == null) cancelDownload()
            }
        }
        // Checagem automática silenciosa quando há sessão (login ou sessão restaurada).
        viewModelScope.launch {
            authRepository.session.map { it != null }.distinctUntilChanged().collect { logged ->
                loggedIn = logged
                if (logged) runCatching { checker.checkAutomatically() }
            }
        }
    }

    /** "Atualizar agora": pede permissão se necessário; senão inicia o download. */
    fun updateNow(context: Context) {
        val update = _state.value.update ?: return
        if (!installer.canInstall()) {
            _state.update { it.copy(phase = UpdatePhase.PERMISSION, error = null) }
            return
        }
        startDownload(update)
    }

    /** Abre as configurações do sistema para autorizar a instalação. */
    fun openInstallPermission(context: Context) {
        if (!installer.openUnknownSourcesSettings(context)) {
            _state.update { it.copy(phase = UpdatePhase.ERROR, error = "Não foi possível abrir as configurações. Autorize manualmente em Configurações → Apps → Acesso especial → Instalar apps desconhecidos → LicitaPRO.") }
        }
    }

    /** Ao voltar ao app (ex.: das Configurações do sistema), retoma o fluxo se a permissão foi concedida. */
    fun onResume(context: Context) {
        // Toda volta ao app consulta de novo (o checker limita a uma consulta a cada 30 min).
        if (loggedIn) viewModelScope.launch { runCatching { checker.checkAutomatically() } }
        val s = _state.value
        if (s.phase == UpdatePhase.PERMISSION && installer.canInstall()) {
            val update = s.update ?: return
            val file = s.file
            if (file != null && file.exists()) openInstaller(context, file) else startDownload(update)
        }
    }

    /** Faixa "Atualizar" no topo: reabre o diálogo da versão adiada. */
    fun resumePostponed() {
        viewModelScope.launch { checker.resumePostponed() }
    }

    /** "Depois": fecha o diálogo; a faixa no topo continua até atualizar (o diálogo volta em 12 h). */
    fun later() {
        val update = _state.value.update ?: return
        cancelDownload()
        viewModelScope.launch { checker.postpone(update) }
    }

    /** Fecha o diálogo (após abrir o instalador ou em erro). */
    fun dismiss() {
        cancelDownload()
        checker.clearPending()
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.update { if (it.phase == UpdatePhase.DOWNLOADING) it.copy(phase = UpdatePhase.OFFER, downloadedBytes = 0, totalBytes = null) else it }
    }

    /** Reabre o instalador com o APK já baixado. */
    fun reopenInstaller(context: Context) {
        val file = _state.value.file?.takeIf { it.exists() } ?: run {
            _state.value.update?.let(::startDownload)
            return
        }
        openInstaller(context, file)
    }

    fun openReleasePage(context: Context) {
        val url = _state.value.update?.pageUrl ?: return
        installer.openReleasePage(context, url)
    }

    private fun startDownload(update: AppUpdate) {
        downloadJob?.cancel()
        _state.update { it.copy(phase = UpdatePhase.DOWNLOADING, downloadedBytes = 0, totalBytes = update.apkSize, error = null, file = null) }
        downloadJob = viewModelScope.launch {
            try {
                downloader.download(update).collect { event ->
                    when (event) {
                        is DownloadState.Progress -> _state.update { it.copy(downloadedBytes = event.bytes, totalBytes = event.total ?: it.totalBytes) }
                        is DownloadState.Done -> onDownloaded(event.file)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        phase = UpdatePhase.ERROR,
                        error = "Download interrompido: ${e.message ?: e.javaClass.simpleName}. Verifique a conexão e tente novamente.",
                    )
                }
            }
        }
    }

    private fun onDownloaded(file: File) {
        when (val check = installer.verify(file)) {
            is ApkCheck.Mismatch -> {
                file.delete()
                _state.update { it.copy(phase = UpdatePhase.ERROR, error = check.message, file = null) }
            }
            ApkCheck.Ok -> _state.update { it.copy(phase = UpdatePhase.READY, file = file, error = null, installRequested = true) }
        }
    }

    private fun openInstaller(context: Context, file: File) {
        if (installer.openInstaller(context, file)) {
            _state.update { it.copy(phase = UpdatePhase.READY, file = file, error = null, installRequested = false) }
        } else {
            _state.update { it.copy(phase = UpdatePhase.ERROR, error = "Não foi possível abrir o instalador do Android.", installRequested = false) }
        }
    }
}
