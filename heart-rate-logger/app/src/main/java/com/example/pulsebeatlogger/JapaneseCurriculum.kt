package com.example.pulsebeatlogger

import com.example.pulsebeatlogger.data.LearningItem
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Offline seed curriculum for the Japanese skill.
 * Used when Gemini is unavailable or as a permanent base for the spaced repetition engine.
 *
 * Pitch accent notation (Tokyo dialect):
 *   H = High, L = Low  — pattern shows mora-by-mora pitch.
 *   e.g. 雨 (ame, rain) = LH → rises on second mora.
 */
object JapaneseCurriculum {

    val items: List<LearningItem> by lazy { buildItems() }

    private data class Seed(
        val category: String,
        val content: String,
        val hint: String,
        val example: String,
        val tip: String,
        val tags: String
    )

    private fun buildItems(): List<LearningItem> {
        val now = System.currentTimeMillis()
        return raw.mapIndexed { i, seed ->
            LearningItem(
                id = "ja_seed_$i",
                skillName = "japanese",
                category = seed.category,
                content = seed.content,
                contentJson = JSONObject().apply {
                    put("hint", seed.hint)
                    put("example", seed.example)
                    put("tip", seed.tip)
                }.toString(),
                easeFactor = 2.5f,
                interval = 1,
                repetitions = 0,
                nextReviewDate = now,
                lastAccuracy = 0f,
                tags = seed.tags
            )
        }
    }

    private val raw = listOf(
        // ── Pitch Accent ──────────────────────────────────────────────────
        Item("pitch_accent", "雨 (ame, rain) — LH",
            "Rises on the second mora: a-ME",
            "雨が降っています。 (It is raining.)",
            "Say 'ame' with your pitch starting low, then jumping high on 'me'.",
            tags("pitch_accent", "n5", "nature")),

        Item("pitch_accent", "橋 (hashi, bridge) — LH vs 箸 (hashi, chopsticks) — HL",
            "Bridge = LH (rises); Chopsticks = HL (falls). Minimal pair!",
            "橋の上で箸を落とした。 (I dropped chopsticks on the bridge.)",
            "These two words are spelled the same in Roman letters — only pitch distinguishes them.",
            tags("pitch_accent", "minimal_pair", "n4")),

        Item("pitch_accent", "花 (hana, flower) — LH vs 鼻 (hana, nose) — LH",
            "Both are LH — context tells them apart.",
            "花の香りが好きです。 (I like the scent of flowers.)",
            "Not every pair differs in pitch! Some homophones share a pattern.",
            tags("pitch_accent", "minimal_pair", "n5")),

        Item("pitch_accent", "山 (yama, mountain) — LH",
            "Rises on second mora: ya-MA",
            "富士山に登りたい。 (I want to climb Mt. Fuji.)",
            "Most 2-mora nouns starting with L are type 2 (accent on last mora).",
            tags("pitch_accent", "n5", "nature")),

        Item("pitch_accent", "川 (kawa, river) — HH",
            "Both morae stay high: KA-WA (no drop)",
            "川の近くに住んでいます。 (I live near a river.)",
            "Flat-pitch words feel monotone compared to accented ones.",
            tags("pitch_accent", "n5", "nature")),

        // ── Pronunciation ─────────────────────────────────────────────────
        Item("pronunciation", "Long vowels: おおきい (ookii, big)",
            "The double 'o' is held for 2 beats — never shorten it.",
            "大きいケーキ (a big cake)",
            "Tap your finger twice while saying each long vowel mora.",
            tags("pronunciation", "vowels", "n5")),

        Item("pronunciation", "Double consonants: きって (kitte, stamp)",
            "Hold the 't' for one silent beat before releasing: ki_tte",
            "切手を貼る (to stick a stamp)",
            "Think of it as a short pause — your mouth stays in position.",
            tags("pronunciation", "consonants", "n5")),

        Item("pronunciation", "The う sound: used vs silent",
            "In words like です (desu) the final 'u' is often devoiced (whispered).",
            "です、ます、ます-form verbs",
            "Barely breathe the 'u' — almost silent in casual speech.",
            tags("pronunciation", "devoicing", "n5")),

        Item("pronunciation", "R sound: ら行 (ra-ri-ru-re-ro)",
            "Not an English R or L — tongue briefly taps the ridge behind upper teeth.",
            "りんご (ringo, apple), 桜 (sakura)",
            "It's closest to a very quick 'd' flap, like the 'd' in American 'ladder'.",
            tags("pronunciation", "consonants", "n5")),

        // ── Vocabulary ────────────────────────────────────────────────────
        Item("vocabulary", "Greetings: おはよう / おはようございます",
            "Casual vs polite good morning. Use ございます with strangers.",
            "おはようございます、田中さん。",
            "Use the casual form only with close friends or family.",
            tags("vocabulary", "greetings", "n5")),

        Item("vocabulary", "Apologies: すみません vs ごめんなさい",
            "すみません = excuse me / thank you (lighter). ごめんなさい = sorry (sincere apology).",
            "すみません、道を教えてください。 (Excuse me, please tell me the way.)",
            "Japanese uses すみません far more than English uses 'excuse me'.",
            tags("vocabulary", "greetings", "n5")),

        Item("vocabulary", "Numbers 1-10: いち に さん し ご ろく なな はち きゅう じゅう",
            "Two readings for 4 and 7: し/よん and しち/なな",
            "三個ください。 (Three please.)",
            "Use よん and なな to avoid confusion with death (し) and the near-homophone for 'die'.",
            tags("vocabulary", "numbers", "n5")),

        Item("vocabulary", "Colors: 赤 (aka), 青 (ao), 白 (shiro), 黒 (kuro), 黄色 (kiiro)",
            "These are い-adjectives except 黄色 which uses な.",
            "青い空 (blue sky), 白い雲 (white cloud)",
            "Add い to use them before nouns: 赤いりんご (red apple).",
            tags("vocabulary", "colors", "n5")),

        Item("vocabulary", "Time: 今 (ima, now), 今日 (kyou, today), 明日 (ashita, tomorrow)",
            "今日 is pronounced kyou, not konnichi!",
            "今日は何曜日ですか。 (What day is today?)",
            "今日 has an irregular reading — memorise the full pronunciation.",
            tags("vocabulary", "time", "n5")),

        // ── Grammar ───────────────────────────────────────────────────────
        Item("grammar", "Topic marker は (wa)",
            "は marks the topic of a sentence — pronounced 'wa', not 'ha'.",
            "私は学生です。 (I am a student.)",
            "は sets the theme; が marks the grammatical subject. They often overlap but differ in nuance.",
            tags("grammar", "particles", "n5")),

        Item("grammar", "Object marker を (wo)",
            "を marks the direct object — pronounced 'o'.",
            "りんごを食べます。 (I eat an apple.)",
            "Visually remember: it looks like a fancy 'wo' twist.",
            tags("grammar", "particles", "n5")),

        Item("grammar", "Polite present tense: verb + ます",
            "ます makes any verb polite. Dictionary form → ます form varies by verb type.",
            "食べます (I eat), 飲みます (I drink), します (I do)",
            "Mastering ます form gives you polite conversation immediately.",
            tags("grammar", "verb_conjugation", "n5")),

        Item("grammar", "Negation: ～ません (not…) and ～ないです (casual)",
            "ます → ません for polite negation.",
            "食べません (I don't eat), 行きません (I won't go)",
            "Pair each new verb with both its positive and negative ます form.",
            tags("grammar", "negation", "n5")),

        // ── Listening ─────────────────────────────────────────────────────
        Item("listening", "Sentence-final particles: ね vs よ",
            "よ = asserting new info to listener. ね = seeking agreement / softening.",
            "これ、おいしいよ。/ これ、おいしいね。",
            "Over-using よ sounds blunt; ね is warmer and more common in casual speech.",
            tags("listening", "particles", "n4")),

        Item("listening", "Minimal pair: 聞く (kiku, to listen) vs 効く (kiku, to be effective)",
            "Identical pronunciation — context is everything.",
            "薬が効く。 (The medicine works.) vs 音楽を聞く。 (Listen to music.)",
            "When you hear 'kiku', use the surrounding words to decide the meaning.",
            tags("listening", "minimal_pair", "n4"))
    )

    private fun Item(category: String, content: String, hint: String, example: String, tip: String, tags: String) =
        Seed(category, content, hint, example, tip, tags)

    private fun tags(vararg t: String): String =
        JSONArray().also { arr -> t.forEach { arr.put(it) } }.toString()
}
