package com.licitaia.feature.platform.site

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformConfig
import com.licitaia.core.platform.PlatformRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Entrega ao site embutido o login da plataforma guardado no aparelho (cofre cifrado), atende no CELULAR o que o
 * site pede e precisa do certificado ([SiteLocal]) e encerra quando o site sai.
 */
@HiltViewModel
class PlatformSiteViewModel @Inject constructor(
    private val repository: PlatformRepository,
    private val local: SiteLocal,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data class Ready(val siteUrl: String, val token: String, val userJson: String?) : State
        data object SignedOut : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()
    private var saindo = false

    /** Telas nativas que o site pediu para abrir (Compras.gov do celular, IA, Portais). */
    private val _abrir = Channel<String>(Channel.BUFFERED)
    val abrir: Flow<String> = _abrir.receiveAsFlow()

    init {
        viewModelScope.launch {
            val auth = runCatching { repository.siteAuth() }.getOrNull()
            _state.value = if (auth == null) State.SignedOut else State.Ready(SITE_URL, auth.first, auth.second)
        }
    }

    /** O navegador do site mora aqui: sobrevive a abrir uma tela nativa e voltar (o site não recarrega). */
    var webView: android.webkit.WebView? = null

    /** Seletor de arquivos da composição atual (enviar documento/certificado). */
    var abrirArquivos: ((android.webkit.ValueCallback<Array<android.net.Uri>>, android.webkit.WebChromeClient.FileChooserParams) -> Boolean)? = null

    override fun onCleared() {
        webView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() }
        webView = null
        abrirArquivos = null
    }

    fun handles(method: String, path: String): Boolean = local.handles(method.uppercase(), path)

    /** Pedido do site (via ponte JS) → roda no celular → [responder] (status, json). */
    fun request(method: String, path: String, body: String, responder: (Int, String) -> Unit) {
        viewModelScope.launch {
            val r = local.handle(method.uppercase(), path, body)
            responder(r.status, r.json)
            r.abrir?.let { _abrir.send(it) }
        }
    }

    /** O site foi para /login ("Sair" ou sessão vencida): encerra a conta da plataforma no app (volta ao login do app). */
    fun onSiteLoggedOut() {
        if (saindo || _state.value !is State.Ready) return
        saindo = true
        viewModelScope.launch {
            runCatching { repository.logout() }
            _state.value = State.SignedOut
        }
    }

    private companion object {
        /** Mesmo host da API da plataforma, sem o /api/ (ex.: https://cont-negociacao.nexussystemtech.com.br/). */
        val SITE_URL: String = PlatformConfig.DEFAULT_BASE_URL.removeSuffix("/").removeSuffix("/api") + "/"
    }
}
