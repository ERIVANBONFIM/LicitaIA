package com.licitaia.feature.auth

import android.content.Context
import com.licitaia.domain.auth.GoogleIdentity
import com.licitaia.domain.auth.NoCompanyAccessException
import com.licitaia.domain.auth.NoLocalLinkException
import com.licitaia.domain.demo.DemoAccount
import com.licitaia.domain.model.AuthProvider
import com.licitaia.feature.auth.google.GoogleCredentialClient
import com.licitaia.feature.auth.google.GoogleSignInResult
import com.licitaia.domain.model.AuthSession
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.UserProfile
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.CompanyRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val company = Company(id = 1, name = "Conecta Minas Ltda", tradeName = "Conecta Minas", cnpj = "12345678000190", segment = Segment.TELECOM_ISP, uf = "MG", city = "Uberlândia")
    private val user = UserProfile(id = 1, name = "Demo", email = DemoAccount.EMAIL, role = UserRole.ADMIN, companyIds = listOf(1))
    private val session = AuthSession(user, company)

    private val auth: AuthRepository = mockk(relaxed = true)
    private val companies: CompanyRepository = mockk()
    private val google: GoogleCredentialClient = mockk(relaxed = true)
    private val activity: Context = mockk(relaxed = true)
    private val identity = GoogleIdentity(subject = "sub-1", email = "ana@example.com", emailVerified = true, name = "Ana")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { auth.session } returns MutableStateFlow<AuthSession?>(null)
        every { companies.observeCompanies() } returns flowOf(listOf(company))
        every { google.isConfigured } returns true
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = LoginViewModel(auth, companies, google)

    // ------------------------------------------------------------------ Google

    @Test
    fun `google nao configurado mostra erro e nao chama o provedor`() = runTest {
        every { google.isConfigured } returns false
        val vm = vm()
        vm.signInWithGoogle(activity)
        assertFalse(vm.state.value.googleConfigured)
        assertNotNull(vm.state.value.generalError)
        coVerify(exactly = 0) { google.signIn(any()) }
    }

    @Test
    fun `google com sucesso e empresa vinculada loga`() = runTest {
        coEvery { google.signIn(any()) } returns GoogleSignInResult.Success(identity)
        coEvery { auth.loginWithGoogle(identity, true) } returns Result.success(session)
        val vm = vm()
        vm.signInWithGoogle(activity)
        assertTrue(vm.state.value.loggedIn)
        assertFalse(vm.state.value.googleLoading)
    }

    @Test
    fun `google identificado sem empresa fica pendente e nao loga`() = runTest {
        coEvery { google.signIn(any()) } returns GoogleSignInResult.Success(identity)
        coEvery { auth.loginWithGoogle(identity, true) } returns Result.failure(NoCompanyAccessException(identity.email))
        val vm = vm()
        vm.signInWithGoogle(activity)
        val s = vm.state.value
        assertFalse(s.loggedIn)
        assertEquals("ana@example.com", s.googlePending?.email)
        assertNull(s.generalError)
    }

    @Test
    fun `google cancelado nao gera erro`() = runTest {
        coEvery { google.signIn(any()) } returns GoogleSignInResult.Cancelled
        val vm = vm()
        vm.signInWithGoogle(activity)
        val s = vm.state.value
        assertFalse(s.googleLoading)
        assertNull(s.generalError)
        assertNotNull(s.info)
        coVerify(exactly = 0) { auth.loginWithGoogle(any(), any()) }
    }

    @Test
    fun `sair da conta google limpa pendencia e chama signOut`() = runTest {
        coEvery { google.signIn(any()) } returns GoogleSignInResult.Success(identity)
        coEvery { auth.loginWithGoogle(identity, true) } returns Result.failure(NoCompanyAccessException(identity.email))
        val vm = vm()
        vm.signInWithGoogle(activity)
        vm.signOutGoogle()
        assertNull(vm.state.value.googlePending)
        coVerify { google.signOut(AuthProvider.GOOGLE) }
    }

    // ------------------------------------------------------------------ vínculo Google ↔ conta local

    @Test
    fun `google com conta local existente pede a senha local em vez de logar`() = runTest {
        coEvery { google.signIn(any()) } returns GoogleSignInResult.Success(identity)
        coEvery { auth.loginWithGoogle(identity, true) } returns Result.failure(NoLocalLinkException(identity.email))
        val vm = vm()
        vm.signInWithGoogle(activity)
        val s = vm.state.value
        assertFalse(s.loggedIn)
        assertEquals("ana@example.com", s.linkPending?.email)
        assertNull(s.googlePending)
        assertNull(s.generalError)
        coVerify(exactly = 0) { auth.linkGoogleToLocal(any(), any(), any()) }
    }

    @Test
    fun `vinculo exige senha e mostra erro quando a senha esta errada`() = runTest {
        coEvery { google.signIn(any()) } returns GoogleSignInResult.Success(identity)
        coEvery { auth.loginWithGoogle(identity, true) } returns Result.failure(NoLocalLinkException(identity.email))
        coEvery { auth.linkGoogleToLocal(identity, "errada", true) } returns Result.failure(IllegalArgumentException("Senha inválida. O vínculo com o Google não foi feito."))
        val vm = vm()
        vm.signInWithGoogle(activity)
        vm.linkGoogle()
        assertEquals("Informe a senha da conta local", vm.state.value.linkError)
        coVerify(exactly = 0) { auth.linkGoogleToLocal(any(), any(), any()) }

        vm.onLinkPassword("errada")
        vm.linkGoogle()
        val s = vm.state.value
        assertFalse(s.loggedIn)
        assertEquals("Senha inválida. O vínculo com o Google não foi feito.", s.linkError)
        assertEquals("", s.linkPassword)
        assertNotNull(s.linkPending)
    }

    @Test
    fun `vinculo com senha certa loga e limpa a pendencia`() = runTest {
        coEvery { google.signIn(any()) } returns GoogleSignInResult.Success(identity)
        coEvery { auth.loginWithGoogle(identity, true) } returns Result.failure(NoLocalLinkException(identity.email))
        coEvery { auth.linkGoogleToLocal(identity, "certa123", true) } returns Result.success(session)
        val vm = vm()
        vm.signInWithGoogle(activity)
        vm.onLinkPassword("certa123")
        vm.linkGoogle()
        val s = vm.state.value
        assertTrue(s.loggedIn)
        assertNull(s.linkPending)
        assertEquals("", s.linkPassword)
    }

    @Test
    fun `cancelar o vinculo limpa a pendencia e sai da conta google`() = runTest {
        coEvery { google.signIn(any()) } returns GoogleSignInResult.Success(identity)
        coEvery { auth.loginWithGoogle(identity, true) } returns Result.failure(NoLocalLinkException(identity.email))
        val vm = vm()
        vm.signInWithGoogle(activity)
        vm.cancelLink()
        assertNull(vm.state.value.linkPending)
        assertNotNull(vm.state.value.info)
        coVerify { google.signOut(AuthProvider.GOOGLE) }
        vm.linkGoogle() // sem identidade: não chama o repositório
        coVerify(exactly = 0) { auth.linkGoogleToLocal(any(), any(), any()) }
    }

    // ------------------------------------------------------------------ demonstração

    @Test
    fun `explorar demonstracao entra sem senha`() = runTest {
        coEvery { auth.loginDemo() } returns Result.success(session)
        val vm = vm()
        vm.exploreDemo()
        assertTrue(vm.state.value.loggedIn)
        assertFalse(vm.state.value.demoLoading)
        coVerify(exactly = 0) { auth.login(any(), any(), any(), any()) }
    }

    @Test
    fun `falha ao abrir a demonstracao mostra a mensagem`() = runTest {
        coEvery { auth.loginDemo() } returns Result.failure(IllegalStateException("Espaço de demonstração inconsistente."))
        val vm = vm()
        vm.exploreDemo()
        assertFalse(vm.state.value.loggedIn)
        assertEquals("Espaço de demonstração inconsistente.", vm.state.value.generalError)
    }

    @Test
    fun `login valida e-mail e senha antes de chamar o repositorio`() = runTest {
        val vm = vm()
        vm.onEmail("nao-e-email")
        vm.submit()
        val s = vm.state.value
        assertEquals("E-mail inválido", s.emailError)
        assertEquals("Informe sua senha", s.passwordError)
        assertFalse(s.loading)
        coVerify(exactly = 0) { auth.login(any(), any(), any(), any()) }
    }

    @Test
    fun `login com sucesso marca loggedIn`() = runTest {
        coEvery { auth.login(DemoAccount.EMAIL, "demo1234", null, true) } returns Result.success(session)
        val vm = vm()
        vm.onEmail(DemoAccount.EMAIL)
        vm.onPassword("demo1234")
        vm.submit()
        val s = vm.state.value
        assertTrue(s.loggedIn)
        assertFalse(s.loading)
        assertNull(s.generalError)
    }

    @Test
    fun `login com falha mostra a mensagem do repositorio e nao vaza excecao`() = runTest {
        coEvery { auth.login(any(), any(), any(), any()) } returns Result.failure(IllegalArgumentException("E-mail ou senha inválidos."))
        val vm = vm()
        vm.onEmail("x@y.com")
        vm.onPassword("errada")
        vm.submit()
        assertEquals("E-mail ou senha inválidos.", vm.state.value.generalError)
        assertFalse(vm.state.value.loggedIn)

        coEvery { auth.login(any(), any(), any(), any()) } throws IllegalStateException("boom")
        vm.onPassword("outra")
        vm.submit()
        assertEquals("boom", vm.state.value.generalError)
    }

    @Test
    fun `cadastro exige senha de 8 caracteres com letras e numeros e CNPJ de 14 digitos`() = runTest {
        val vm = vm()
        vm.setMode(AuthMode.REGISTER)
        vm.onName("Jo")
        vm.onEmail("joao@empresa.com.br")
        vm.onPassword("abc1234")
        vm.onCompanyName("A")
        vm.onCnpj("12.345.678/0001")
        vm.submit()
        val s = vm.state.value
        assertNotNull(s.nameError)
        assertNull(s.emailError)
        assertEquals("A senha deve ter ao menos 8 caracteres", s.passwordError)
        assertNotNull(s.companyError)
        assertEquals("O CNPJ deve ter 14 dígitos", s.cnpjError)
        coVerify(exactly = 0) { auth.register(any(), any(), any(), any(), any()) }

        vm.onPassword("abcdefgh")
        vm.submit()
        assertEquals("Use letras e números na senha", vm.state.value.passwordError)
    }

    @Test
    fun `cadastro valido chama o repositorio com CNPJ so com digitos`() = runTest {
        coEvery { auth.register("João Silva", "joao@empresa.com.br", "senha123", "Empresa X", "12345678000190") } returns Result.success(session)
        val vm = vm()
        vm.setMode(AuthMode.REGISTER)
        vm.onName("João Silva")
        vm.onEmail("joao@empresa.com.br")
        vm.onPassword("senha123")
        vm.onCompanyName("Empresa X")
        vm.onCnpj("12.345.678/0001-90")
        assertEquals("12345678000190", vm.state.value.cnpj)
        vm.submit()
        assertTrue(vm.state.value.loggedIn)
    }

    @Test
    fun `trocar de modo limpa os erros`() = runTest {
        val vm = vm()
        vm.submit()
        assertNotNull(vm.state.value.emailError)
        vm.setMode(AuthMode.REGISTER)
        assertNull(vm.state.value.emailError)
        assertNull(vm.state.value.passwordError)
    }
}

