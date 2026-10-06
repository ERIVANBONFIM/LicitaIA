package com.licitaia.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.domain.repository.BackupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(private val backups: BackupRepository) : ViewModel() {
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    fun run(uri: String, password: String, restore: Boolean) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val result = if (restore) backups.restoreBackup(uri, password.toCharArray()) else backups.exportBackup(uri, password.toCharArray())
                message = result.getOrElse { "Falha: ${it.message ?: "Senha incorreta ou arquivo inválido."}" }
            } finally { busy = false }
        }
    }
}

@Composable
fun BackupCard(viewModel: BackupViewModel = hiltViewModel()) {
    var password by remember { mutableStateOf("") }
    var restoreUri by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { viewModel.run(it.toString(), password, false); password = "" }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri?.toString() }
    LicitaCard(Modifier.fillMaxWidth()) {
        Text("Backup cifrado da empresa", style = MaterialTheme.typography.titleMedium)
        Text("Inclui licitações, editais, documentos e anexos, propostas, radares, histórico e auditoria. Senhas, contas e chaves de IA não são exportadas. Guarde a senha: ela é necessária em outro aparelho.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Senha do backup (mínimo 12 caracteres)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !viewModel.busy)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { save.launch("LicitaIA-backup.licitaia") }, enabled = password.length >= 12 && !viewModel.busy) { Text("Exportar") }
            OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }, enabled = password.isNotEmpty() && !viewModel.busy) { Text("Restaurar") }
        }
        if (viewModel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        viewModel.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (restoreUri != null) AlertDialog(
        onDismissRequest = { restoreUri = null }, title = { Text("Restaurar por adição?") },
        text = { Text("O CNPJ deve coincidir com a empresa ativa. Os registros atuais serão preservados; restaurar o mesmo arquivo novamente pode duplicar dados. Propostas importadas voltam a rascunho. Contas e sessões de portal não são restauradas.") },
        confirmButton = { TextButton(onClick = { viewModel.run(restoreUri!!, password, true); password = ""; restoreUri = null }) { Text("Restaurar") } },
        dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Cancelar") } },
    )
}
