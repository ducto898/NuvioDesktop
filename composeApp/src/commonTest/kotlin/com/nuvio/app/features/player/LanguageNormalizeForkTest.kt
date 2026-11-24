package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

// Fork fix LANG (2026-11-25): the name-alias list is sorted once instead of per call. Expected values were captured
// from the pre-fix implementation (build 1.1.38-1833594b); track titles are the ones of the crashing release.
class LanguageNormalizeForkTest {
    private val expected = listOf(
        "English (Forced)" to "en",
        "English" to "en",
        "Arabic" to "ar",
        "Chinese (Simplified)" to "zh",
        "Chinese (Traditional)" to "zh",
        "French (Canadian)" to "fr",
        "French (Parisian)" to "fr",
        "German" to "de",
        "Indonesian" to "id",
        "Italian" to "it",
        "Japanese" to "ja",
        "Malay" to "ms",
        "Polish" to "pl",
        "Portuguese (Brazilian)" to "pt-br",
        "Portuguese (Iberian)" to "pt",
        "Russian" to "ru",
        "Spanish (Castilian)" to "es",
        "Spanish (Latin American)" to "es-419",
        "Thai" to "th",
        "Vietnamese" to "vi",
        "Signs & Songs" to "signs & songs",
        "Full Subtitles" to "full subtitles",
        "fr-CA" to "fr-ca",
        "zh-Hans" to "zh-hans",
        "es-419" to "es-419",
        "pt-BR" to "pt-br",
        "Brazilian Portuguese" to "pt-br",
        "English [Forced]" to "en",
    )

    @Test
    fun sameResultsAsBeforeTheFix() {
        for ((input, want) in expected) assertEquals(want, normalizeLanguageCode(input), input)
    }

    @Test
    fun repeatedCallsStayStable() {
        repeat(20_000) { i ->
            val (input, want) = expected[i % expected.size]
            assertEquals(want, normalizeLanguageCode(input), input)
        }
    }
}
