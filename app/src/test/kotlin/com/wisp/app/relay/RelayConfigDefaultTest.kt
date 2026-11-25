package com.wisp.app.relay

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wisp.app.repo.KeyRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RelayConfigDefaultTest {

    @Test
    fun defaultRelaysUseCanonicalFireWispRelay() {
        val urls = RelayConfig.DEFAULTS.map { it.url }
        assertTrue("Expected wss://relay.firewisp.de in defaults", "wss://relay.firewisp.de" in urls)
        assertFalse("Legacy rtard relay should not be in defaults", "wss://firewisp.relay.rtard.de" in urls)
        val fireWisp = RelayConfig.DEFAULTS.first { it.url == "wss://relay.firewisp.de" }
        assertTrue(fireWisp.read)
        assertTrue(fireWisp.write)
    }

    @Test
    fun keyRepositoryMigratesLegacyFireWispRelayUrl() {
        val app = RuntimeEnvironment.getApplication() as Application
        val legacyList = listOf(
            RelayConfig("wss://firewisp.relay.rtard.de", read = true, write = true),
            RelayConfig("wss://relay.damus.io", read = true, write = true)
        )
        app.getSharedPreferences("wisp_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("relays", Json.encodeToString(legacyList))
            .commit()

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
                }.thenReturn(app.getSharedPreferences("stub_enc_prefs_migrate", Context.MODE_PRIVATE))

                val repo = KeyRepository(app)
                val urls = repo.getRelays().map { it.url }
                assertEquals(listOf("wss://relay.firewisp.de", "wss://relay.damus.io"), urls)
            }
        }
    }
}
