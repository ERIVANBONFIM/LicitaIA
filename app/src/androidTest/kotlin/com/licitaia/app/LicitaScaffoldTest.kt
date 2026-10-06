package com.licitaia.app

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.nav.AppNavigator
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.LocalShellState
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.nav.ShellState
import com.licitaia.core.ui.theme.LicitaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Teste de UI do componente base de todas as telas: título, empresa ativa, menu/voltar e sino. */
@RunWith(AndroidJUnit4::class)
class LicitaScaffoldTest {

    @get:Rule
    val compose = createComposeRule()

    private class RecordingNavigator : AppNavigator {
        val calls = mutableListOf<String>()
        override fun navigate(route: String) { calls += "navigate:$route" }
        override fun navigateTop(route: String) { calls += "top:$route" }
        override fun back() { calls += "back" }
        override fun openDrawer() { calls += "drawer" }
        override fun onLoggedIn() { calls += "loggedIn" }
        override fun showMessage(message: String) { calls += "msg:$message" }
    }

    private fun setScaffold(navigator: RecordingNavigator, showBack: Boolean, shell: ShellState) {
        compose.setContent {
            LicitaTheme {
                CompositionLocalProvider(LocalAppNavigator provides navigator, LocalShellState provides shell) {
                    LicitaScaffold(title = "Painel", showBack = showBack) { Text("conteudo") }
                }
            }
        }
    }

    @Test
    fun rendersTitleCompanyAndBell() {
        val navigator = RecordingNavigator()
        setScaffold(navigator, showBack = false, shell = ShellState(companyName = "Conecta Minas", unreadNotifications = 3))

        compose.onNodeWithText("Painel").assertIsDisplayed()
        compose.onNodeWithText("Conecta Minas").assertIsDisplayed()
        compose.onNodeWithText("conteudo").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Menu").assertIsDisplayed()

        compose.onNodeWithContentDescription("Notificações").performClick()
        assertEquals(listOf("navigate:${Routes.NOTIFICATIONS}"), navigator.calls)
    }

    @Test
    fun backArrowCallsNavigatorBack() {
        val navigator = RecordingNavigator()
        setScaffold(navigator, showBack = true, shell = ShellState())

        compose.onNodeWithContentDescription("Voltar").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Menu").assertDoesNotExist()
        assertEquals(listOf("back"), navigator.calls)
    }

    @Test
    fun menuIconOpensDrawerOnTopLevel() {
        val navigator = RecordingNavigator()
        setScaffold(navigator, showBack = false, shell = ShellState())

        compose.onNodeWithContentDescription("Menu").performClick()
        assertEquals(listOf("drawer"), navigator.calls)
    }
}
