package com.wisp.app.repo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * SigningMode.REMOTE persistence through the REAL KeyRepository logic:
 * the Android Keystore layer is stubbed (Robolectric has none), but the
 * SharedPreferences that actually hold account state are real, so
 * re-instantiation semantics are exercised end to end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class KeyRepositoryRemotePersistenceTest {
    @Test
    fun `remote account survives re-instantiation`() {
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
                }.thenReturn(app.getSharedPreferences("stub_enc_prefs", Context.MODE_PRIVATE))

                val pubkey = "b".repeat(64)
                val signerPackage = "com.example.signer"

                val repo1 = KeyRepository(app)
                repo1.savePubkeyOnly(pubkey, signerPackage)
                assertEquals(SigningMode.REMOTE, repo1.getSigningMode())
                assertEquals(pubkey, repo1.getPubkeyHex())
                assertTrue(repo1.isLoggedIn())

                // Fresh instance must observe the persisted remote account.
                val repo2 = KeyRepository(app)
                assertEquals(SigningMode.REMOTE, repo2.getSigningMode())
                assertEquals(pubkey, repo2.getPubkeyHex())
                assertTrue(repo2.isLoggedIn())

                // A local account can be added and switched to.
                val local = Keys.generate()
                repo2.saveKeypair(local)
                assertEquals(SigningMode.LOCAL, repo2.getSigningMode())
                repo2.switchToAccount(pubkey)
                assertEquals(SigningMode.REMOTE, repo2.getSigningMode())

                // Removing the remote account clears the session.
                repo2.removeAccount(pubkey)
                assertFalse(repo2.isLoggedIn())
            }
        }
    }

    @Test
    fun `local keypair persists across re-instantiation`() {
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
                }.thenReturn(app.getSharedPreferences("stub_enc_prefs", Context.MODE_PRIVATE))

                val repo1 = KeyRepository(app)
                val local = Keys.generate()
                repo1.saveKeypair(local)
                val pubkeyHex = local.pubkey.toHex()

                val repo2 = KeyRepository(app)
                assertEquals(SigningMode.LOCAL, repo2.getSigningMode())
                assertEquals(pubkeyHex, repo2.getPubkeyHex())
                val restored = repo2.getKeypair()
                assertTrue(restored != null && restored.privkey.contentEquals(local.privkey))

                repo2.removeAccount(pubkeyHex)
                assertFalse(repo2.isLoggedIn())
                assertNull(repo2.getPubkeyHex())
            }
        }
    }
}
