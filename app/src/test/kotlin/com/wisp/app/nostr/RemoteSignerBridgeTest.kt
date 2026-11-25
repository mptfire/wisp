package com.wisp.app.nostr

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RemoteSignerBridgeTest {

    private fun registerSigner(
        packageName: String,
        label: String,
        scheme: String = "nostrsigner",
        activityName: String = ".MainActivity"
    ) {
        val app = RuntimeEnvironment.getApplication() as Application
        val shadowPm = Shadows.shadowOf(app.packageManager)
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                this.name = packageName + activityName
                this.applicationInfo = ApplicationInfo().apply {
                    this.packageName = packageName
                    this.nonLocalizedLabel = label
                }
            }
            nonLocalizedLabel = label
        }
        shadowPm.addResolveInfoForIntent(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("$scheme:")), info)
    }

    @Test
    fun listsMultipleDistinctSignersSortedByLabelIncludingNip46() {
        val app = RuntimeEnvironment.getApplication() as Application
        registerSigner("net.primal.android", "Primal", scheme = "nostrsigner")
        registerSigner("com.greenart7c3.nostrsigner", "Amber", scheme = "nostrsigner")
        registerSigner("com.greenart7c3.nostrsigner", "Amber", scheme = "nostrconnect")
        registerSigner("com.greenart7c3.nostrsigner", "Amber", scheme = "nostrsigner", activityName = ".SecondaryActivity")
        registerSigner("io.cambium.signer", "Cambium", scheme = "nostrsigner")
        registerSigner("com.vitorpamplona.amethyst", "Amethyst", scheme = "nostrconnect")

        val signers = RemoteSignerBridge.listAvailableSigners(app)
        assertEquals(
            listOf(
                InstalledSignerApp("com.greenart7c3.nostrsigner", "Amber", ExternalSignerProtocol.NIP55),
                InstalledSignerApp("com.vitorpamplona.amethyst", "Amethyst", ExternalSignerProtocol.NIP46),
                InstalledSignerApp("io.cambium.signer", "Cambium", ExternalSignerProtocol.NIP55),
                InstalledSignerApp("net.primal.android", "Primal", ExternalSignerProtocol.NIP55)
            ),
            signers
        )
    }

    @Test
    fun buildNostrConnectIntentTargetsExplicitPackage() {
        val uri = "nostrconnect://${"a".repeat(64)}?relay=wss%3A%2F%2Frelay.nsec.app&secret=abc"
        val intent = RemoteSignerBridge.buildNostrConnectIntent(uri, packageName = "com.vitorpamplona.amethyst")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(uri, intent.dataString)
        assertEquals("com.vitorpamplona.amethyst", intent.`package`)
    }

    @Test
    fun buildGetPublicKeyIntentTargetsExplicitPackageWhenProvided() {
        val targeted = RemoteSignerBridge.buildGetPublicKeyIntent(packageName = "net.primal.android")
        assertEquals("net.primal.android", targeted.`package`)
        assertEquals("get_public_key", targeted.getStringExtra("type"))

        val generic = RemoteSignerBridge.buildGetPublicKeyIntent()
        assertNull(generic.`package`)
    }

    @Test
    fun resolveSignedPackagePrefersReturnedThenSelectedThenDefault() {
        val app = RuntimeEnvironment.getApplication() as Application
        registerSigner("com.greenart7c3.nostrsigner", "Amber")
        registerSigner("net.primal.android", "Primal")

        // Explicit package extra from signer wins
        assertEquals(
            "io.cambium.signer",
            RemoteSignerBridge.resolveSignedPackage("io.cambium.signer", "net.primal.android", app)
        )
        // Missing/blank extra falls back to the exact signer the user selected
        assertEquals(
            "net.primal.android",
            RemoteSignerBridge.resolveSignedPackage("", "net.primal.android", app)
        )
        assertEquals(
            "net.primal.android",
            RemoteSignerBridge.resolveSignedPackage(null, "net.primal.android", app)
        )
        // If neither returned nor selected, falls back to system default
        assertEquals(
            "com.greenart7c3.nostrsigner",
            RemoteSignerBridge.resolveSignedPackage(null, null, app)
        )
    }
}
