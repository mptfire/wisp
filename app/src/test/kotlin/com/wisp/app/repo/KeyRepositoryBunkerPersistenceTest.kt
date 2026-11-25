package com.wisp.app.repo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.Nip46
import com.wisp.app.nostr.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class KeyRepositoryBunkerPersistenceTest {

    @Test
    fun bunkerSessionSurvivesReinstantiationAndMultiAccountSwitch() {
        val app = RuntimeEnvironment.getApplication() as Application
        Mockito.mockConstruction(MasterKey.Builder::class.java) { builder, _ ->
            Mockito.`when`(builder.setKeyScheme(Mockito.any())).thenReturn(builder)
            Mockito.`when`(builder.build()).thenReturn(Mockito.mock(MasterKey::class.java))
        }.use {
            Mockito.mockStatic(EncryptedSharedPreferences::class.java).use { enc ->
                enc.`when`<SharedPreferences> {
                    EncryptedSharedPreferences.create(
                        Mockito.any<Context>(), Mockito.anyString(), Mockito.any<MasterKey>(),
                        Mockito.any<EncryptedSharedPreferences.PrefKeyEncryptionScheme>(),
                        Mockito.any<EncryptedSharedPreferences.PrefValueEncryptionScheme>()
                    )
                }.thenReturn(app.getSharedPreferences("stub_enc_prefs_bunker", Context.MODE_PRIVATE))

                val clientKeys = Keys.generate()
                val session = Nip46.BunkerSession(
                    userPubkeyHex = "e".repeat(64),
                    remoteSignerPubkeyHex = "f".repeat(64),
                    clientPrivkeyHex = clientKeys.privkey.toHex(),
                    relays = listOf("wss://relay.nsec.app", "wss://relay.damus.io"),
                    useNip04 = false
                )

                val repo1 = KeyRepository(app)
                repo1.saveBunkerSession(session)
                assertEquals(SigningMode.BUNKER, repo1.getSigningMode())
                assertEquals(session.userPubkeyHex, repo1.getPubkeyHex())
                assertEquals(session, repo1.getBunkerSession())
                assertTrue(repo1.isLoggedIn())

                // Re-instantiate KeyRepository -> bunker session must persist intact
                val repo2 = KeyRepository(app)
                assertEquals(SigningMode.BUNKER, repo2.getSigningMode())
                assertEquals(session, repo2.getBunkerSession())

                // Switch to a local account, then back to bunker -> bunker session restored
                val local = Keys.generate()
                repo2.saveKeypair(local)
                assertEquals(SigningMode.LOCAL, repo2.getSigningMode())
                assertNull(repo2.getBunkerSession())

                repo2.switchToAccount(session.userPubkeyHex)
                assertEquals(SigningMode.BUNKER, repo2.getSigningMode())
                assertNotNull(repo2.getBunkerSession())
                assertEquals(session, repo2.getBunkerSession())

                // Removing bunker account cleans up session
                repo2.removeAccount(session.userPubkeyHex)
                assertNull(repo2.getBunkerSession())
                assertFalse(repo2.getAccountList().any { it.pubkeyHex == session.userPubkeyHex })
            }
        }
    }
}
