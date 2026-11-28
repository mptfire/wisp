package com.wisp.app.translate

import java.util.Locale
import kotlin.math.min

/**
 * Lightweight zero-Google offline language detector using Unicode script histograms,
 * unique vs shared diacritics, inverse-frequency stopwords, suffixes, and character n-grams
 * across all supported Mozilla translation models.
 */
object OfflineLanguageDetector {

    private data class LatinProfile(
        val code: String,
        val stopwords: Set<String>,
        val uniqueDiacritics: String = "",
        val sharedDiacritics: String = "",
        val suffixPatterns: List<String> = emptyList(),
        val ngramPatterns: List<String> = emptyList()
    )

    private val latinProfiles = listOf(
        LatinProfile(
            code = "en",
            stopwords = setOf(
                "the", "and", "is", "are", "was", "were", "to", "of", "in", "for", "with",
                "that", "this", "from", "have", "has", "not", "but", "you", "they", "what",
                "about", "today", "just", "your", "our", "will", "would", "can",
                "hello", "hey", "thanks", "thank", "good", "morning", "night", "great",
                "really", "very", "much", "more", "most", "some", "any", "all", "how",
                "why", "when", "where", "who", "which", "there", "here", "now", "then",
                "also", "still", "even", "only", "been", "being", "going", "doing", "make",
                "know", "think", "want", "need", "like", "love", "people", "world", "time",
                "work", "new", "right", "well", "don't", "can't", "won't", "it's", "that's",
                "i'm", "you're", "we're", "they're", "everyone", "something", "nothing"
            ),
            suffixPatterns = listOf("ing", "tion", "ment", "ness"),
            ngramPatterns = listOf("the", "ing", "tion", "ight", "ould", "ough", "ness", "with", "that")
        ),
        LatinProfile(
            code = "de",
            stopwords = setOf(
                "der", "die", "das", "und", "ist", "sind", "war", "nicht", "ich", "du", "wir",
                "ihr", "ein", "eine", "einen", "einem", "mit", "für", "auf", "von", "zu",
                "über", "aber", "auch", "wie", "noch", "schon", "endlich", "heute", "sich", "gut",
                "hallo", "guten", "morgen", "abend", "schönen", "tag", "danke", "bitte", "geht",
                "geht's", "habe", "haben", "kann", "wird", "muss", "will", "mein", "meine",
                "dein", "unser", "alles", "viel", "mehr", "immer", "jetzt", "hier", "dann",
                "wenn", "weil", "dass", "oder", "sehr", "gute", "leute", "welt", "zeit",
                "was", "wer", "wo", "wann", "warum", "krass", "allerseits", "schönen", "eigen"
            ),
            uniqueDiacritics = "ß",
            sharedDiacritics = "äöü",
            suffixPatterns = listOf("ung", "keit", "heit", "lich", "isch"),
            ngramPatterns = listOf("sch", "cht", "ein", "ung", "keit", "heit", "lich", "iger", "isch")
        ),
        LatinProfile(
            code = "es",
            stopwords = setOf(
                "el", "la", "los", "las", "un", "una", "y", "es", "son", "que", "de", "en",
                "para", "por", "con", "sin", "como", "pero", "hoy", "buenos", "gracias",
                "desde", "todo", "está", "están", "muy", "más", "también", "sobre", "casa",
                "hola", "buenas", "tardes", "noches", "amigo", "amigos", "favor", "qué", "cómo",
                "cuándo", "dónde", "quién", "porque", "sí", "si", "todos", "todas", "bien",
                "mal", "ahora", "aquí", "así", "siempre", "nunca", "solo", "sólo", "hay",
                "tengo", "tiene", "hacer", "puede", "quiero", "vida", "mundo", "tiempo", "gente",
                "nuevo", "nueva", "feliz", "día", "días", "pasa", "tal", "nada", "algo",
                "mucho", "mucha", "esto", "eso", "ese", "esa", "mío", "tuyo", "nuestro"
            ),
            uniqueDiacritics = "ñ¿¡",
            sharedDiacritics = "áéíóúü",
            suffixPatterns = listOf("ción", "sión", "ando", "iendo", "idad"),
            ngramPatterns = listOf("ción", "sión", "iento", "idad", "ía", "ñ", "ado", "ida", "uer", "iez")
        ),
        LatinProfile(
            code = "fr",
            stopwords = setOf(
                "le", "la", "les", "un", "une", "des", "et", "est", "sont", "que", "qui",
                "dans", "pour", "avec", "sur", "pas", "plus", "tout", "mais", "bonjour",
                "merci", "aujourd'hui", "nous", "vous", "très", "être", "avoir", "c'est",
                "bonsoir", "salut", "coucou", "beaucoup", "oui", "non", "sans", "tous", "toute",
                "toutes", "bien", "mal", "trop", "peu", "aussi", "encore", "toujours", "jamais",
                "maintenant", "ici", "pourquoi", "comment", "quand", "quoi", "quel", "quelle",
                "fais", "fait", "faire", "suis", "sommes", "êtes", "avons", "avez", "peut",
                "veux", "monde", "temps", "gens", "journée", "belle", "beau", "incroyable",
                "vraiment", "rien", "quelque", "chose", "ça", "j'ai", "qu'est"
            ),
            uniqueDiacritics = "œ",
            sharedDiacritics = "àâæçéèêëîïôùûüÿ",
            suffixPatterns = listOf("ment", "tion", "eau", "eux"),
            ngramPatterns = listOf("eau", "eux", "oir", "ais", "ait", "ois", "oux", "eur", "tion", "ment", "che", "que", "ouj")
        ),
        LatinProfile(
            code = "pt",
            stopwords = setOf(
                "o", "a", "os", "as", "um", "uma", "e", "é", "são", "que", "de", "em", "no",
                "na", "para", "com", "não", "mais", "como", "mas", "hoje", "obrigado",
                "bom", "dia", "tudo", "muito", "também", "você", "sobre",
                "olá", "oi", "boa", "tarde", "noite", "pessoal", "obrigada", "bem", "muita",
                "menos", "agora", "aqui", "sempre", "nunca", "ainda", "já", "só", "mesmo",
                "porque", "quando", "onde", "quem", "qual", "isso", "isto", "esse", "essa",
                "este", "esta", "meu", "minha", "seu", "sua", "nosso", "nossa", "fazer",
                "pode", "quero", "tenho", "tem", "vida", "mundo", "tempo", "gente", "novo",
                "nova", "feliz", "amigo", "amigos", "trabalho", "incrível"
            ),
            uniqueDiacritics = "ãõ",
            sharedDiacritics = "çáâàéêíóôú",
            suffixPatterns = listOf("ção", "ções", "mente", "nho"),
            ngramPatterns = listOf("ção", "ções", "nho", "lho", "nha", "lha", "eir", "ão", "ões", "vel", "mente")
        ),
        LatinProfile(
            code = "it",
            stopwords = setOf(
                "il", "lo", "la", "gli", "le", "un", "una", "e", "è", "sono", "che", "di",
                "in", "per", "con", "non", "ma", "come", "oggi", "ciao", "grazie", "molto",
                "anche", "questo", "questa", "tutto", "bene", "casa",
                "buongiorno", "buonasera", "mille", "prego", "sì", "si", "no", "male", "tutti",
                "tutte", "molta", "più", "meno", "ora", "adesso", "qui", "sempre", "mai",
                "ancora", "già", "solo", "perché", "quando", "dove", "chi", "cosa", "quello",
                "quella", "mio", "mia", "tuo", "tua", "nostro", "nostra", "fare", "può",
                "voglio", "mondo", "tempo", "gente", "nuovo", "nuova", "bello", "bella",
                "giornata", "amico", "amici"
            ),
            sharedDiacritics = "àèéìíîòóùú",
            suffixPatterns = listOf("zione", "mente", "iamo", "gli"),
            ngramPatterns = listOf("gli", "zione", "issim", "ione", "cch", "ggi", "zza", "che", "chi", "iamo", "mente")
        ),
        LatinProfile(
            code = "nl",
            stopwords = setOf(
                "de", "het", "een", "en", "van", "ik", "je", "we", "is", "zijn", "dat",
                "dit", "niet", "maar", "voor", "met", "op", "aan", "ook", "wel", "nog",
                "vandaag", "bedankt", "goed", "heel",
                "hallo", "goedemorgen", "goedenavond", "dank", "graag", "natuurlijk", "erg",
                "veel", "meer", "minder", "altijd", "nooit", "nu", "hier", "daar", "waarom",
                "hoe", "wanneer", "waar", "wie", "wat", "alles", "niets", "iets", "goede",
                "dag", "wereld", "tijd", "mensen", "nieuw", "nieuwe", "mooi", "geweldig",
                "werk", "echt", "gewoon", "want", "omdat", "al", "dus", "toch", "dan", "als",
                "bij", "uit", "naar", "over", "onder", "tussen", "zonder", "allemaal"
            ),
            suffixPatterns = listOf("heid", "lijk", "ings", "sch"),
            ngramPatterns = listOf("ijk", "sch", "een", "oor", "aan", "oei", "uij", "heid", "ing", "tje", "oed")
        ),
        LatinProfile(
            code = "pl",
            stopwords = setOf(
                "i", "w", "na", "z", "do", "nie", "to", "jest", "są", "że", "się", "jak",
                "ale", "czy", "już", "bardzo", "dziękuję", "dzisiaj", "można", "przez",
                "cześć", "dzień", "dobry", "dobranoc", "proszę", "tak", "wszystko", "dobrze", "teraz"
            ),
            uniqueDiacritics = "ąćęłńśźż",
            sharedDiacritics = "ó",
            ngramPatterns = listOf("prz", "szcz", "rze", "cze", "nie", "wia", "ość", "dzy", "cie")
        ),
        LatinProfile(
            code = "tr",
            stopwords = setOf(
                "ve", "bir", "bu", "da", "de", "ile", "için", "ama", "çok", "ne", "var",
                "yok", "ben", "sen", "biz", "teşekkür", "bugün", "nasıl", "gibi", "daha",
                "merhaba", "günaydın", "arkadaşlar", "evet", "hayır", "güzel", "şimdi"
            ),
            uniqueDiacritics = "ğıİş",
            sharedDiacritics = "çöü",
            ngramPatterns = listOf("yor", "lar", "ler", "dak", "dek", "mak", "mek", "lik", "lık", "luk", "lük", "ğ")
        ),
        LatinProfile(
            code = "id",
            stopwords = setOf(
                "yang", "dan", "di", "ke", "dari", "ini", "itu", "untuk", "dengan", "tidak",
                "bisa", "ada", "juga", "saya", "kamu", "hari", "terima", "kasih", "sudah",
                "selamat", "pagi", "malam", "halo", "apa", "kabar", "sangat"
            )
        ),
        LatinProfile(
            code = "ms",
            stopwords = setOf(
                "yang", "dan", "di", "ke", "dari", "ini", "itu", "untuk", "dengan", "tidak",
                "boleh", "ada", "juga", "saya", "awak", "hari", "terima", "kasih", "sudah",
                "selamat", "pagi", "apa", "khabar", "kerana", "tetapi"
            )
        ),
        LatinProfile(
            code = "vi",
            stopwords = setOf(
                "và", "là", "của", "trong", "cho", "với", "không", "có", "này", "đã",
                "được", "tôi", "bạn", "hôm", "nay", "cảm", "ơn", "rất", "xin", "chào"
            ),
            uniqueDiacritics = "ăơưđảạằẳẵắặầẩẫấậẻẽẹềểễếệỉĩịỏọồổỗốộờởỡớợủũụừửữứựỳỷỹýỵ",
            sharedDiacritics = "âêôàáèéìíòóùú"
        ),
        LatinProfile(
            code = "sv",
            stopwords = setOf(
                "och", "att", "det", "som", "är", "en", "ett", "för", "med", "inte",
                "jag", "vi", "på", "av", "till", "om", "men", "idag", "tack", "har",
                "hej", "god", "morgon", "mycket", "bra", "nu", "här"
            ),
            sharedDiacritics = "åäö"
        ),
        LatinProfile(
            code = "da",
            stopwords = setOf(
                "og", "at", "det", "som", "er", "en", "et", "for", "med", "ikke",
                "jeg", "vi", "på", "af", "til", "men", "tak", "i", "dag", "har",
                "hej", "godmorgen", "meget", "godt", "nu", "her"
            ),
            sharedDiacritics = "æøå"
        ),
        LatinProfile(
            code = "nb",
            stopwords = setOf(
                "og", "at", "det", "som", "er", "en", "et", "for", "med", "ikke",
                "jeg", "vi", "på", "av", "til", "men", "takk", "idag", "har", "hvordan",
                "hei", "veldig", "bra", "nå", "her"
            ),
            sharedDiacritics = "æøå"
        ),
        LatinProfile(
            code = "nn",
            stopwords = setOf(
                "og", "at", "det", "som", "er", "ein", "ei", "eit", "for", "med", "ikkje",
                "eg", "vi", "på", "av", "til", "men", "takk", "har", "korleis"
            ),
            sharedDiacritics = "æøå"
        ),
        LatinProfile(
            code = "fi",
            stopwords = setOf(
                "ja", "on", "ei", "että", "se", "kuin", "oli", "mutta", "minä", "sinä",
                "tänään", "kiitos", "hyvä", "kanssa", "myös", "vielä", "hei", "huomenta", "nyt"
            ),
            sharedDiacritics = "äö"
        ),
        LatinProfile(
            code = "cs",
            stopwords = setOf(
                "a", "je", "se", "na", "v", "z", "to", "ne", "že", "ale", "jsem", "jsou",
                "pro", "jak", "dnes", "děkuji", "velmi", "když", "ahoj", "dobrý", "den"
            ),
            uniqueDiacritics = "ěřů",
            sharedDiacritics = "áčďéíňóšťúýž"
        ),
        LatinProfile(
            code = "sk",
            stopwords = setOf(
                "a", "je", "sa", "na", "v", "z", "to", "nie", "že", "ale", "som", "sú",
                "pre", "ako", "dnes", "ďakujem", "veľmi", "keď", "ahoj", "dobrý", "deň"
            ),
            uniqueDiacritics = "ľĺŕô",
            sharedDiacritics = "áčďéíňóšťúýž"
        ),
        LatinProfile(
            code = "hu",
            stopwords = setOf(
                "és", "a", "az", "hogy", "nem", "van", "is", "egy", "ez", "már", "meg",
                "de", "vagy", "köszönöm", "nagyon", "ma", "mint", "szia", "jó", "reggelt"
            ),
            uniqueDiacritics = "őű",
            sharedDiacritics = "áéíóöúü"
        ),
        LatinProfile(
            code = "ro",
            stopwords = setOf(
                "și", "în", "la", "de", "cu", "nu", "este", "sunt", "că", "pentru",
                "mai", "dar", "mulțumesc", "astăzi", "foarte", "pe", "salut", "bună", "ziua"
            ),
            uniqueDiacritics = "șț",
            sharedDiacritics = "ăâî"
        ),
        LatinProfile(
            code = "ca",
            stopwords = setOf(
                "i", "el", "la", "els", "les", "de", "en", "amb", "per", "que", "és",
                "no", "avui", "gràcies", "molt", "més", "també", "com", "hola", "bon", "dia"
            ),
            uniqueDiacritics = "·",
            sharedDiacritics = "àèéíïòóúüç"
        ),
        LatinProfile(
            code = "hr",
            stopwords = setOf(
                "i", "u", "na", "je", "se", "da", "ne", "za", "ali", "sam", "su",
                "kao", "danas", "hvala", "vrlo", "kako", "bok", "dobar", "dan"
            ),
            sharedDiacritics = "čćđšž"
        ),
        LatinProfile(
            code = "bs",
            stopwords = setOf(
                "i", "u", "na", "je", "se", "da", "ne", "za", "ali", "sam", "su",
                "kao", "danas", "hvala", "kako", "zdravo", "dobar", "dan", "gdje"
            ),
            sharedDiacritics = "čćđšž"
        ),
        LatinProfile(
            code = "sl",
            stopwords = setOf(
                "in", "v", "na", "je", "se", "da", "ne", "za", "ali", "sem", "so",
                "kot", "danes", "hvala", "zelo", "kako", "zdravo", "dober", "dan", "ker"
            ),
            sharedDiacritics = "čšž"
        ),
        LatinProfile(
            code = "lt",
            stopwords = setOf(
                "ir", "į", "su", "kad", "tai", "yra", "ne", "aš", "tu", "mes",
                "labas", "rytas", "ačiū", "šiandien", "labai", "kaip", "bet", "taip"
            ),
            uniqueDiacritics = "ėįų",
            sharedDiacritics = "ąčęšūž"
        ),
        LatinProfile(
            code = "lv",
            stopwords = setOf(
                "un", "ir", "uz", "ar", "ka", "tas", "nav", "es", "tu", "mēs",
                "sveiki", "labrīt", "paldies", "šodien", "ļoti", "kā", "bet", "jā"
            ),
            uniqueDiacritics = "āēīķļņŗ",
            sharedDiacritics = "čģšūž"
        ),
        LatinProfile(
            code = "et",
            stopwords = setOf(
                "ja", "on", "ei", "et", "see", "kui", "oli", "aga", "mina", "sina",
                "täna", "aitäh", "tere", "hommikust", "väga", "ka", "veel", "siis"
            ),
            sharedDiacritics = "äöüõ"
        ),
        LatinProfile(
            code = "sq",
            stopwords = setOf(
                "dhe", "e", "të", "në", "me", "për", "që", "nga", "një", "është",
                "janë", "jo", "po", "faleminderit", "përshëndetje", "sot", "shumë", "mirë"
            ),
            sharedDiacritics = "ëç"
        ),
        LatinProfile(
            code = "is",
            stopwords = setOf(
                "og", "að", "það", "sem", "er", "ekki", "ég", "við", "fyrir", "með",
                "á", "af", "til", "en", "takk", "í", "dag", "hæ", "góðan", "daginn"
            ),
            uniqueDiacritics = "þð",
            sharedDiacritics = "áéíóúýöæ"
        ),
        LatinProfile(
            code = "mt",
            stopwords = setOf(
                "u", "il", "l", "ta", "li", "fil", "minn", "għal", "bħala", "huma",
                "mhux", "iva", "le", "grazzi", "bonġu", "illum", "ħafna", "tajjeb"
            ),
            uniqueDiacritics = "ċġħ",
            sharedDiacritics = "ż"
        ),
        LatinProfile(
            code = "az",
            stopwords = setOf(
                "və", "bir", "bu", "da", "də", "ilə", "üçün", "amma", "çox", "nə",
                "var", "yox", "mən", "sən", "biz", "təşəkkür", "bugün", "necə", "salam"
            ),
            uniqueDiacritics = "ə",
            sharedDiacritics = "çğıöşü"
        )
    )

    private val wordFreq: Map<String, Int> = buildMap {
        for (profile in latinProfiles) {
            for (word in profile.stopwords) {
                put(word, (get(word) ?: 0) + 1)
            }
        }
    }

    fun detect(rawText: String): String? {
        val cleaned = NostrTextPreprocessor.stripForDetection(rawText)
        if (cleaned.isBlank()) return null

        detectNonLatinScript(cleaned)?.let { return it }
        if (cleaned.count { it.isLetter() } < 3) return null
        return detectLatinLanguage(cleaned)
    }

    private fun detectNonLatinScript(text: String): String? {
        var kana = 0
        var hangul = 0
        var han = 0
        var cyrillic = 0
        var arabic = 0
        var hebrew = 0
        var greek = 0
        var devanagari = 0
        var bengali = 0
        var tamil = 0
        var telugu = 0
        var kannada = 0
        var malayalam = 0
        var gujarati = 0
        var totalLetters = 0

        for (ch in text) {
            if (!ch.isLetter()) continue
            totalLetters++
            val code = ch.code
            when {
                code in 0x3040..0x30FF -> kana++
                code in 0xAC00..0xD7AF || code in 0x1100..0x11FF -> hangul++
                code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF -> han++
                code in 0x0400..0x04FF -> cyrillic++
                code in 0x0600..0x06FF -> arabic++
                code in 0x0590..0x05FF -> hebrew++
                code in 0x0370..0x03FF -> greek++
                code in 0x0900..0x097F -> devanagari++
                code in 0x0980..0x09FF -> bengali++
                code in 0x0B80..0x0BFF -> tamil++
                code in 0x0C00..0x0C7F -> telugu++
                code in 0x0C80..0x0CFF -> kannada++
                code in 0x0D00..0x0D7F -> malayalam++
                code in 0x0A80..0x0AFF -> gujarati++
            }
        }
        if (totalLetters == 0) return null

        if (kana >= 2 || (kana >= 1 && han >= 1) || (kana >= 1 && (kana + han) * 3 >= totalLetters)) {
            return "ja"
        }
        if (hangul >= 2 && (hangul * 4 >= totalLetters || hangul >= 4)) return "ko"
        if (han >= 2 && (han * 3 >= totalLetters || han >= 4)) return "zh"
        if (hebrew >= 2 && (hebrew * 3 >= totalLetters || hebrew >= 4)) return "he"
        if (greek >= 2 && (greek * 3 >= totalLetters || greek >= 4)) return "el"
        if (devanagari >= 2 && (devanagari * 3 >= totalLetters || devanagari >= 4)) return "hi"
        if (bengali >= 2 && (bengali * 3 >= totalLetters || bengali >= 4)) return "bn"
        if (tamil >= 2 && (tamil * 3 >= totalLetters || tamil >= 4)) return "ta"
        if (telugu >= 2 && (telugu * 3 >= totalLetters || telugu >= 4)) return "te"
        if (kannada >= 2 && (kannada * 3 >= totalLetters || kannada >= 4)) return "kn"
        if (malayalam >= 2 && (malayalam * 3 >= totalLetters || malayalam >= 4)) return "ml"
        if (gujarati >= 2 && (gujarati * 3 >= totalLetters || gujarati >= 4)) return "gu"

        if (arabic >= 2 && (arabic * 3 >= totalLetters || arabic >= 4)) {
            val persianHints = text.count { it in "پچژگک" || it == 'ی' }
            return if (persianHints > 0) "fa" else "ar"
        }

        if (cyrillic >= 2 && (cyrillic * 3 >= totalLetters || cyrillic >= 4)) {
            val lower = text.lowercase(Locale.ROOT)
            if (lower.any { it in "їєіґ" }) return "uk"
            if (lower.any { it == 'ў' }) return "be"
            if (lower.any { it in "ђјљњћџ" }) return "sr"
            val russianSpecific = lower.count { it in "ыэё" }
            val bulgarianHints = lower.count { it == 'ъ' || it == 'щ' }
            val bgWords = Regex("""\b(това|съм|който|какво)\b""").containsMatchIn(lower)
            if ((bulgarianHints > 0 || bgWords) && russianSpecific == 0) return "bg"
            return "ru"
        }

        return null
    }

    private fun detectLatinLanguage(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        val words = lower.split(Regex("""[^\p{L}'-]+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null

        var bestCode: String? = null
        var bestScore = 0.0
        var secondScore = 0.0

        for (profile in latinProfiles) {
            var score = 0.0
            for (w in words) {
                if (w in profile.stopwords) {
                    val freq = wordFreq[w] ?: 1
                    score += when {
                        freq == 1 -> if (w.length >= 3) 2.6 else 1.5
                        freq == 2 -> if (w.length >= 3) 1.5 else 0.8
                        else -> if (w.length >= 3) 0.7 else 0.35
                    }
                }
                for (suf in profile.suffixPatterns) {
                    if (w.length >= suf.length + 2 && w.endsWith(suf)) {
                        score += 0.8
                        break
                    }
                }
                if (w.length >= 4 && profile.ngramPatterns.isNotEmpty()) {
                    var ngramHits = 0
                    for (ng in profile.ngramPatterns) {
                        if (ng in w) {
                            score += 0.45
                            ngramHits++
                            if (ngramHits >= 2) break
                        }
                    }
                }
            }
            if (profile.uniqueDiacritics.isNotEmpty()) {
                val hits = lower.count { it in profile.uniqueDiacritics }
                if (hits > 0) {
                    score += min(hits * 2.0, 6.0)
                }
            }
            if (profile.sharedDiacritics.isNotEmpty()) {
                val hits = lower.count { it in profile.sharedDiacritics }
                if (hits > 0) {
                    score += min(hits * 0.35, 1.75)
                }
            }

            if (score > bestScore) {
                secondScore = bestScore
                bestScore = score
                bestCode = profile.code
            } else if (score > secondScore) {
                secondScore = score
            }
        }

        if (bestCode == null || bestScore < 1.8) return null
        if (secondScore > 0.0 && bestScore < secondScore * 1.20 && bestScore - secondScore < 0.8) {
            return null
        }
        return bestCode
    }
}
