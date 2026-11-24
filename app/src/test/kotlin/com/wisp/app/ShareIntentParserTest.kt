package com.wisp.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ShareIntentParserTest {

    @Test
    fun parsesYouTubeShareTextAndDeduplicatesSubject() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Ali Campbell - I Want You")
            putExtra(Intent.EXTRA_TEXT, "https://youtu.be/20t-IZZztOk")
        }
        val parsed = ShareIntentParser.parse(intent)
        assertNotNull(parsed)
        assertEquals("Ali Campbell - I Want You\nhttps://youtu.be/20t-IZZztOk", parsed!!.text)
        assertTrue(parsed.mediaUris.isEmpty())

        val duplicateIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Same title")
            putExtra(Intent.EXTRA_TEXT, "Same title https://youtu.be/20t-IZZztOk")
        }
        val parsedDup = ShareIntentParser.parse(duplicateIntent)
        assertEquals("Same title https://youtu.be/20t-IZZztOk", parsedDup?.text)
    }

    @Test
    fun acceptsContentUrisAndRejectsFileUris() {
        val safeUri = Uri.parse("content://media/external/images/media/42")
        val safeIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, safeUri)
        }
        val parsedSafe = ShareIntentParser.parse(safeIntent)
        assertNotNull(parsedSafe)
        assertEquals(listOf(safeUri), parsedSafe!!.mediaUris)

        val evilUri = Uri.parse("file:///data/data/de.rtard.wisp/shared_prefs/wisp_keys.xml")
        val evilIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, evilUri)
        }
        assertNull(ShareIntentParser.parse(evilIntent))
    }

    @Test
    fun parsesMultipleSharedImagesAndCapsList() {
        val uris = ArrayList((1..12).map { Uri.parse("content://media/external/images/$it") })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        val parsed = ShareIntentParser.parse(intent)
        assertNotNull(parsed)
        assertEquals(10, parsed!!.mediaUris.size)
    }

    @Test
    fun ignoresNonShareIntents() {
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse("nostr:npub1test"))
        assertNull(ShareIntentParser.parse(viewIntent))
    }
}
