package com.wisp.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineLanguageDetectorTest {

    @Test
    fun detectsNonLatinScriptsAccurately() {
        assertEquals("ja", OfflineLanguageDetector.detect("おはようございます！今日はビットコインについて話しましょう。"))
        assertEquals("zh", OfflineLanguageDetector.detect("今天我们学习如何使用去中心化的社交网络协议。"))
        assertEquals("ko", OfflineLanguageDetector.detect("안녕하세요! 오늘 노스트르 릴레이를 설정했습니다."))
        assertEquals("ru", OfflineLanguageDetector.detect("Привет! Сегодня запустил свой собственный релей для сети."))
        assertEquals("uk", OfflineLanguageDetector.detect("Привіт! Сьогодні запустив власний сервер та налаштував мережу."))
        assertEquals("ar", OfflineLanguageDetector.detect("مرحبا بالجميع، اليوم نجرب الشبكة اللامركزية الجديدة."))
        assertEquals("fa", OfflineLanguageDetector.detect("سلام دوستان، امروز سرور جدید را راه‌اندازی کردیم."))
        assertEquals("he", OfflineLanguageDetector.detect("שלום לכולם, היום הפעלנו שרת חדש ברשת."))
        assertEquals("el", OfflineLanguageDetector.detect("Καλημέρα σε όλους! Σήμερα δοκιμάζουμε το νέο δίκτυο."))
        assertEquals("hi", OfflineLanguageDetector.detect("नमस्ते दोस्तों, आज हम नया विकेन्द्रीकृत नेटवर्क देख रहे हैं।"))
    }

    @Test
    fun detectsLatinLanguagesAfterStrippingNostrTokens() {
        assertEquals(
            "de",
            OfflineLanguageDetector.detect(
                "nostr:npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsutp0r3 " +
                    "Endlich läuft mein eigener Nostr Relay stabil über Tor! https://firewisp.de #bitcoin"
            )
        )
        assertEquals(
            "es",
            OfflineLanguageDetector.detect(
                "Buenos días Nostr! Hoy probando nodos Lightning desde casa sin servidores de Google."
            )
        )
        assertEquals(
            "fr",
            OfflineLanguageDetector.detect(
                "Bonjour à tous! Aujourd'hui nous testons le nouveau relais avec Tor sur téléphone."
            )
        )
        assertEquals(
            "pt",
            OfflineLanguageDetector.detect(
                "Bom dia pessoal! Hoje estamos configurando nossa própria carteira sem intermediários."
            )
        )
        assertEquals(
            "it",
            OfflineLanguageDetector.detect(
                "Ciao a tutti! Oggi proviamo questo nuovo nodo privato direttamente da casa nostra."
            )
        )
        assertEquals(
            "en",
            OfflineLanguageDetector.detect(
                "Good morning! Testing the sovereign offline translation engine with zero cloud calls today."
            )
        )
        assertEquals(
            "pl",
            OfflineLanguageDetector.detect(
                "Dzień dobry! Dzisiaj bardzo dobrze działa nasz prywatny przekaźnik bez serwerów."
            )
        )
        assertEquals(
            "tr",
            OfflineLanguageDetector.detect(
                "Bugün kendi sunucumuz için çok güzel bir gizlilik güncellemesi yayınladık, teşekkürler."
            )
        )
    }

    @Test
    fun detectsShortRealWorldNotesAndMixedCjkLatin() {
        assertEquals("es", OfflineLanguageDetector.detect("Hola amigos, feliz sábado!"))
        assertEquals("de", OfflineLanguageDetector.detect("Guten Morgen allerseits!"))
        assertEquals("de", OfflineLanguageDetector.detect("Schönen Abend!"))
        assertEquals("pt", OfflineLanguageDetector.detect("Boa noite pessoal!"))
        assertEquals("fr", OfflineLanguageDetector.detect("Merci beaucoup!"))
        assertEquals("it", OfflineLanguageDetector.detect("Grazie mille!"))
        assertEquals("nl", OfflineLanguageDetector.detect("Goedemorgen allemaal!"))
        assertEquals("tr", OfflineLanguageDetector.detect("Merhaba arkadaşlar!"))
        assertEquals("pl", OfflineLanguageDetector.detect("Dzień dobry!"))
        assertEquals("en", OfflineLanguageDetector.detect("Hello everyone, good morning!"))

        assertEquals("zh", OfflineLanguageDetector.detect("你好"))
        assertEquals("ja", OfflineLanguageDetector.detect("おはよう"))
        assertEquals("ko", OfflineLanguageDetector.detect("안녕"))
        assertEquals("ja", OfflineLanguageDetector.detect("BitcoinとNostr最高!"))
        assertEquals("en", OfflineLanguageDetector.detect("¯\\_(ツ)_/¯ hello world, how are you doing?"))
    }

    @Test
    fun returnsNullForUrlsOrTooShortNotes() {
        assertNull(OfflineLanguageDetector.detect("https://firewisp.de/live #nostr"))
        assertNull(OfflineLanguageDetector.detect("nostr:nevent1qqsqqqqqqqqqqqqqqqqqqqqqqqqqqqq"))
        assertNull(OfflineLanguageDetector.detect("gm!"))
        assertNull(OfflineLanguageDetector.detect("   "))
    }
}
