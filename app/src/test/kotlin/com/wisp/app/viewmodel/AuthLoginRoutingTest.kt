package com.wisp.app.viewmodel

import android.app.Application
import com.wisp.app.nostr.Nip06
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.Nip49
import com.wisp.app.nostr.toHex
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.SigningMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * AuthViewModel.logIn() input routing: nsec, npub, nprofile, hex pubkey,
 * ncryptsec backups (with the official NIP-49 vector) and NIP-06 mnemonics
 * (with the official vector). KeyRepository is construction-mocked; the
 * crypto and routing logic under test are real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AuthLoginRoutingTest {
    private fun newViewModel(app: Application): AuthViewModel {
        val mocked = Mockito.mockConstruction(KeyRepository::class.java) { _, _ -> }
        // The construction mock must live for the whole test; AuthViewModel
        // creates its own KeyRepository in its constructor.
        return AuthViewModel(app).also { mocked.close() }
    }

    private val mnemonic12 = "leader monkey parrot ring guide accident before fence cannon height naive bean"
    private val mnemonic24 = "what bleak badge arrange retreat wolf trade produce cricket blur garlic valid proud rude strong choose busy staff weather area salt hollow arm fade"
    private val officialNcryptsec =
        "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p"

    @Test
    fun blankInputFails() {
        val vm = newViewModel(RuntimeEnvironment.getApplication() as Application)
        vm.updateNsecInput("  ")
        assertFalse(vm.logIn())
    }

    @Test
    fun garbageInputFailsWithFormatError() {
        val vm = newViewModel(RuntimeEnvironment.getApplication() as Application)
        vm.updateNsecInput("not-a-key")
        assertFalse(vm.logIn())
        assertTrue(vm.error.value.orEmpty().contains("Invalid key format"))
    }

    @Test
    fun nprofileInputLogsInReadOnly() {
        val vm = newViewModel(RuntimeEnvironment.getApplication() as Application)
        val pubkeyHex = "a".repeat(64)
        vm.updateNsecInput(Nip19.nprofileEncode(pubkeyHex))
        assertTrue(vm.logIn())
        assertEquals(SigningMode.READ_ONLY, vm.signingModeFlow.value)
    }

    @Test
    fun hexPubkeyInputLogsInReadOnly() {
        val vm = newViewModel(RuntimeEnvironment.getApplication() as Application)
        vm.updateNsecInput("c".repeat(64))
        assertTrue(vm.logIn())
        assertEquals(SigningMode.READ_ONLY, vm.signingModeFlow.value)
    }

    @Test
    fun mnemonic12LogsInLocal() {
        val vm = newViewModel(RuntimeEnvironment.getApplication() as Application)
        vm.updateNsecInput(mnemonic12)
        assertTrue(vm.logIn())
        assertEquals(SigningMode.LOCAL, vm.signingModeFlow.value)
        assertEquals("npub1zutzeysacnf9rru6zqwmxd54mud0k44tst6l70ja5mhv8jjumytsd2x7nu", vm.npub.value)
    }

    @Test
    fun mnemonic24LogsInLocal() {
        val vm = newViewModel(RuntimeEnvironment.getApplication() as Application)
        vm.updateNsecInput(mnemonic24)
        assertTrue(vm.logIn())
        assertEquals(SigningMode.LOCAL, vm.signingModeFlow.value)
        assertEquals("npub16sdj9zv4f8sl85e45vgq9n7nsgt5qphpvmf7vk8r5hhvmdjxx4es8rq74h", vm.npub.value)
    }

    @Test
    fun invalidMnemonicFails() {
        val vm = newViewModel(RuntimeEnvironment.getApplication() as Application)
        vm.updateNsecInput("leader monkey parrot ring guide accident before fence cannon height naive beanx")
        assertFalse(vm.logIn())
        assertTrue(vm.error.value.orEmpty().contains("recovery phrase"))
    }

    @Test
    fun ncryptsecRequiresPassphraseThenUnlocks() = runBlocking {
        val vm = newViewModel(RuntimeEnvironment.getApplication() as Application)
        vm.updateNsecInput(officialNcryptsec)
        assertFalse(vm.logIn())
        assertNotNull(vm.pendingNcryptsec.value)
        assertTrue(vm.completeNcryptsecLogin("nostr"))
        assertEquals(SigningMode.LOCAL, vm.signingModeFlow.value)
        assertEquals(
            "3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683",
            Nip49.decrypt(officialNcryptsec, "nostr").toHex()
        )
        assertEquals(null, vm.pendingNcryptsec.value)
    }

    @Test
    fun nbspSeparatedMnemonicLogsInLocal() {
        val vm = newViewModel(RuntimeEnvironment.getApplication() as Application)
        vm.updateNsecInput(mnemonic12.replace(" ", "\u00A0"))
        assertTrue(vm.logIn())
        assertEquals(SigningMode.LOCAL, vm.signingModeFlow.value)
        assertEquals("npub1zutzeysacnf9rru6zqwmxd54mud0k44tst6l70ja5mhv8jjumytsd2x7nu", vm.npub.value)
    }

    @Test
    fun uppercaseNcryptsecWithNostrPrefixLogsIn() = runBlocking {
        val vm = newViewModel(RuntimeEnvironment.getApplication() as Application)
        vm.updateNsecInput("nostr:" + officialNcryptsec.uppercase())
        assertTrue(vm.logIn() == false) // pending, needs passphrase
        assertNotNull(vm.pendingNcryptsec.value)
        assertTrue(vm.completeNcryptsecLogin("nostr"))
        assertEquals(SigningMode.LOCAL, vm.signingModeFlow.value)
    }

    @Test
    fun hostileLogNcryptsecRejectedBeforePending() {
        val vm = newViewModel(RuntimeEnvironment.getApplication() as Application)
        val payload = Nip49.ncryptsecDecode(officialNcryptsec)
        payload[1] = 24 // OOM/CPU DoS attempt
        vm.updateNsecInput(Nip49.ncryptsecEncode(payload))
        assertFalse(vm.logIn())
        assertEquals(null, vm.pendingNcryptsec.value)
        assertTrue(vm.error.value.orEmpty().contains("log_n"))
    }

    @Test
    fun badChecksumNcryptsecRejectedBeforePending() {
        val vm = newViewModel(RuntimeEnvironment.getApplication() as Application)
        val broken = officialNcryptsec.dropLast(1) + if (officialNcryptsec.last() == 'p') 'q' else 'p'
        vm.updateNsecInput(broken)
        assertFalse(vm.logIn())
        assertEquals(null, vm.pendingNcryptsec.value)
    }

    @Test
    fun ncryptsecWrongPassphraseKeepsPending() = runBlocking {
        val vm = newViewModel(RuntimeEnvironment.getApplication() as Application)
        vm.updateNsecInput(officialNcryptsec)
        assertFalse(vm.logIn())
        assertFalse(vm.completeNcryptsecLogin("wrong"))
        assertNotNull(vm.pendingNcryptsec.value)
        assertTrue(vm.error.value.orEmpty().contains("passphrase"))
        vm.clearPendingNcryptsec()
        assertEquals(null, vm.pendingNcryptsec.value)
    }
}
