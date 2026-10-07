package com.licitaia.feature.radar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Radar
import com.licitaia.domain.model.Segment
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.RadarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RadarForm(
    val loading: Boolean = true,
    val loadError: String? = null,
    val isNew: Boolean = true,
    val name: String = "",
    val segment: Segment = Segment.TELECOM_ISP,
    val keywords: List<String> = emptyList(),
    val forbidden: List<String> = emptyList(),
    val allPortals: Boolean = true,
    val portals: Set<Portal> = emptySet(),
    val ufs: Set<String> = emptySet(),
    val region: String? = null,
    val agency: String = "",
    val modality: Modality? = null,
    val minValue: String = "",
    val maxValue: String = "",
    val startDate: Long? = null,
    val endDate: Long? = null,
    val minScore: Int = 60,
    val cnae: String = "",
    val preferredObject: String = "",
    val requireLocalSupport: Boolean = false,
    /** Mostrar dispensas sem disputa (contratação direta). Padrão: ocultas. */
    val showNoDispute: Boolean = false,
    val active: Boolean = true,
    val nameError: String? = null,
    val keywordsError: String? = null,
    val portalsError: String? = null,
    val valueError: String? = null,
    val dateError: String? = null,
    val cnaeError: String? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
    val saved: Boolean = false,
) {
    val hasErrors: Boolean
        get() = listOfNotNull(nameError, keywordsError, portalsError, valueError, dateError, cnaeError).isNotEmpty()
}

@HiltViewModel
class RadarEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val repository: RadarRepository,
) : ViewModel() {

    private val radarId: Long = savedStateHandle.longArg("radarId") ?: -1L
    private var original: Radar? = null

    private val _form = MutableStateFlow(RadarForm())
    val form: StateFlow<RadarForm> = _form.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _form.update { it.copy(loading = true, loadError = null) }
            val session = auth.session.value
            if (session == null) {
                _form.update { it.copy(loading = false, loadError = "Sessão encerrada. Entre novamente.") }
                return@launch
            }
            if (radarId <= 0) {
                _form.value = RadarForm(loading = false, isNew = true, segment = session.activeCompany.segment)
                return@launch
            }
            val radar = try {
                repository.getRadar(radarId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (radar == null || radar.companyId != session.activeCompany.id) {
                _form.update { it.copy(loading = false, loadError = "Radar não encontrado para a empresa ativa.") }
                return@launch
            }
            original = radar
            _form.value = RadarForm(
                loading = false, isNew = false, name = radar.name, segment = radar.segment,
                keywords = radar.keywords, forbidden = radar.forbiddenKeywords,
                allPortals = radar.allPortals, portals = radar.portals.toSet(), ufs = radar.ufs.toSet(),
                region = radar.region, agency = radar.agency.orEmpty(), modality = radar.modality,
                minValue = moneyText(radar.minValue), maxValue = moneyText(radar.maxValue),
                startDate = radar.startDate, endDate = radar.endDate, minScore = radar.minScore,
                cnae = radar.cnae.orEmpty(), preferredObject = radar.preferredObject.orEmpty(),
                requireLocalSupport = radar.requireLocalSupport, showNoDispute = radar.showNoDispute, active = radar.active,
            )
        }
    }

    /** Qualquer edição limpa os erros de validação pendentes. */
    fun edit(transform: (RadarForm) -> RadarForm) = _form.update {
        transform(it).copy(
            nameError = null, keywordsError = null, portalsError = null,
            valueError = null, dateError = null, cnaeError = null, saveError = null,
        )
    }

    fun addKeywords(raw: String, forbidden: Boolean) {
        val words = raw.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return
        edit { f ->
            val current = if (forbidden) f.forbidden else f.keywords
            val merged = (current + words).distinctBy { it.lowercase() }
            if (forbidden) f.copy(forbidden = merged) else f.copy(keywords = merged)
        }
    }

    fun removeKeyword(word: String, forbidden: Boolean) = edit { f ->
        if (forbidden) f.copy(forbidden = f.forbidden - word) else f.copy(keywords = f.keywords - word)
    }

    fun save() {
        val f = _form.value
        if (f.saving || f.loading) return
        val session = auth.session.value ?: run {
            _form.update { it.copy(saveError = "Sessão encerrada. Entre novamente.") }
            return
        }
        val min = parseMoney(f.minValue)
        val max = parseMoney(f.maxValue)
        val conflicting = f.keywords.map { it.lowercase() }.intersect(f.forbidden.map { it.lowercase() }.toSet())
        val cnaeDigits = f.cnae.filter(Char::isDigit)
        val validated = f.copy(
            nameError = when {
                f.name.isBlank() -> "Dê um nome ao radar"
                f.name.trim().length < 3 -> "O nome deve ter ao menos 3 caracteres"
                else -> null
            },
            keywordsError = when {
                conflicting.isNotEmpty() -> "\"${conflicting.first()}\" está nas palavras-chave e nas proibidas"
                f.keywords.isEmpty() && f.preferredObject.isBlank() -> "Informe ao menos uma palavra-chave ou o objeto preferencial"
                else -> null
            },
            portalsError = if (!f.allPortals && f.portals.isEmpty()) "Selecione ao menos um portal ou use todos os portais" else null,
            valueError = when {
                min?.isNaN() == true || max?.isNaN() == true -> "Informe valores numéricos válidos"
                min != null && max != null && min > max -> "O valor mínimo não pode ser maior que o máximo"
                else -> null
            },
            dateError = if (f.startDate != null && f.endDate != null && f.startDate > f.endDate) "A data inicial deve ser anterior à final" else null,
            cnaeError = if (f.cnae.isNotBlank() && cnaeDigits.length != 7) "O CNAE deve ter 7 dígitos (ex.: 6110-8/03)" else null,
        )
        if (validated.hasErrors) {
            _form.value = validated.copy(saveError = "Revise os campos destacados.")
            return
        }
        _form.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            val base = original
            val radar = Radar(
                id = base?.id ?: 0,
                companyId = base?.companyId ?: session.activeCompany.id,
                name = f.name.trim(),
                segment = f.segment,
                keywords = f.keywords,
                forbiddenKeywords = f.forbidden,
                portals = if (f.allPortals) emptyList() else f.portals.sortedBy { it.ordinal },
                allPortals = f.allPortals,
                ufs = f.ufs.sorted(),
                region = f.region,
                agency = f.agency.trim().ifBlank { null },
                modality = f.modality,
                minValue = min,
                maxValue = max,
                startDate = f.startDate,
                endDate = f.endDate,
                minScore = f.minScore,
                cnae = f.cnae.trim().ifBlank { null },
                preferredObject = f.preferredObject.trim().ifBlank { null },
                requireLocalSupport = f.requireLocalSupport,
                showNoDispute = f.showNoDispute,
                active = f.active,
                createdAt = base?.createdAt?.takeIf { it > 0 } ?: System.currentTimeMillis(),
            )
            try {
                repository.upsert(radar)
                _form.update { it.copy(saving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _form.update { it.copy(saving = false, saveError = e.message?.takeIf(String::isNotBlank) ?: "Não foi possível salvar o radar.") }
            }
        }
    }
}
