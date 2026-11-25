package com.wisp.app.viewmodel

import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.CustomEmojiRepository
import com.wisp.app.repo.DeletedEventsRepository
import com.wisp.app.repo.DmRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.InterfacePreferences
import com.wisp.app.repo.MuteRepository
import com.wisp.app.repo.NotificationRepository
import com.wisp.app.repo.PinRepository
import com.wisp.app.repo.PowPreferences
import com.wisp.app.repo.RelayListRepository
import com.wisp.app.repo.WalletProvider
import com.wisp.app.repo.ZapSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class BlockConfirmationTest {
    private data class Harness(
        val manager: SocialActionManager,
        val muteRepo: MuteRepository,
        val eventRepo: EventRepository,
        val notifRepo: NotificationRepository,
        val dmRepo: DmRepository
    )

    private fun createHarness(): Harness {
        val muteRepo = Mockito.mock(MuteRepository::class.java)
        val eventRepo = Mockito.mock(EventRepository::class.java)
        val notifRepo = Mockito.mock(NotificationRepository::class.java)
        val dmRepo = Mockito.mock(DmRepository::class.java)
        val manager = SocialActionManager(
            relayPool = Mockito.mock(RelayPool::class.java),
            outboxRouter = Mockito.mock(OutboxRouter::class.java),
            eventRepo = eventRepo,
            contactRepo = Mockito.mock(ContactRepository::class.java),
            muteRepo = muteRepo,
            notifRepo = notifRepo,
            dmRepo = dmRepo,
            pinRepo = Mockito.mock(PinRepository::class.java),
            deletedEventsRepo = Mockito.mock(DeletedEventsRepository::class.java),
            getWalletProvider = { Mockito.mock(WalletProvider::class.java) },
            customEmojiRepo = Mockito.mock(CustomEmojiRepository::class.java),
            zapSender = Mockito.mock(ZapSender::class.java),
            powPrefs = Mockito.mock(PowPreferences::class.java),
            interfacePrefs = Mockito.mock(InterfacePreferences::class.java),
            relayListRepo = Mockito.mock(RelayListRepository::class.java),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            getSigner = { null },
            getUserPubkey = { "1".repeat(64) }
        )
        return Harness(manager, muteRepo, eventRepo, notifRepo, dmRepo)
    }

    @Test
    fun requestBlockStagesTargetWithoutBlockingImmediately() {
        val h = createHarness()
        val targetPubkey = "a".repeat(64)
        var confirmedCallbackInvoked = false

        h.manager.requestBlockUser(targetPubkey) { confirmedCallbackInvoked = true }

        assertEquals(targetPubkey, h.manager.pendingBlockTarget.value?.pubkey)
        assertFalse(confirmedCallbackInvoked)
        Mockito.verify(h.muteRepo, Mockito.never()).blockUser(targetPubkey)
        Mockito.verify(h.eventRepo, Mockito.never()).purgeUser(targetPubkey)
    }

    @Test
    fun dismissBlockClearsPendingWithoutBlocking() {
        val h = createHarness()
        val targetPubkey = "b".repeat(64)
        var confirmedCallbackInvoked = false

        h.manager.requestBlockUser(targetPubkey) { confirmedCallbackInvoked = true }
        h.manager.dismissBlockUser()

        assertNull(h.manager.pendingBlockTarget.value)
        assertFalse(confirmedCallbackInvoked)
        Mockito.verify(h.muteRepo, Mockito.never()).blockUser(targetPubkey)
        Mockito.verify(h.eventRepo, Mockito.never()).purgeUser(targetPubkey)
    }

    @Test
    fun confirmBlockBlocksPurgesAndRunsCallback() {
        val h = createHarness()
        val targetPubkey = "c".repeat(64)
        var confirmedCallbackInvoked = false

        h.manager.requestBlockUser(targetPubkey) { confirmedCallbackInvoked = true }
        h.manager.confirmBlockUser()

        assertNull(h.manager.pendingBlockTarget.value)
        assertTrue(confirmedCallbackInvoked)
        Mockito.verify(h.muteRepo).blockUser(targetPubkey)
        Mockito.verify(h.eventRepo).purgeUser(targetPubkey)
        Mockito.verify(h.notifRepo).purgeUser(targetPubkey)
        Mockito.verify(h.dmRepo).purgeUser(targetPubkey)
    }
}
