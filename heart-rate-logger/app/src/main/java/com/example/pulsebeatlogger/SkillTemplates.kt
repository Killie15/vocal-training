package com.example.pulsebeatlogger

/** One tab in a skill's toolkit — e.g. Ukulele → Tune, Chords, Review. */
data class SkillTab(
    val id: String,
    val label: String,
    val emoji: String
)

data class SkillToolkitTemplate(
    val shortName: String,
    val tabs: List<SkillTab>
)

object SkillToolkitRegistry {

    fun templateFor(trackingMode: String, skillName: String): SkillToolkitTemplate {
        return when (trackingMode) {
            "ukulele" -> SkillToolkitTemplate(
                shortName = "Ukulele",
                tabs = listOf(
                    SkillTab("tune", "Tune", "🎯"),
                    SkillTab("chords", "Chords", "🎸"),
                    SkillTab("technique", "Technique", "📖"),
                    SkillTab("review", "Review", "🔁"),
                    SkillTab("log", "Log", "📋")
                )
            )
            "japanese" -> SkillToolkitTemplate(
                shortName = "Japanese",
                tabs = listOf(
                    SkillTab("vocab", "Vocab", "📚"),
                    SkillTab("pitch", "Pitch", "🗣️"),
                    SkillTab("grammar", "Grammar", "✏️"),
                    SkillTab("listening", "Listen", "👂"),
                    SkillTab("review", "Review", "🔁"),
                    SkillTab("log", "Log", "📋")
                )
            )
            "pushup" -> SkillToolkitTemplate(
                shortName = "Push-ups",
                tabs = listOf(
                    SkillTab("form", "Form", "🧍"),
                    SkillTab("reps", "Reps", "💪"),
                    SkillTab("zones", "HR Zones", "❤️"),
                    SkillTab("review", "Review", "🔁"),
                    SkillTab("log", "Log", "📋")
                )
            )
            "running" -> SkillToolkitTemplate(
                shortName = "Running",
                tabs = listOf(
                    SkillTab("pace", "Pace", "⏱️"),
                    SkillTab("gait", "Gait", "👟"),
                    SkillTab("zones", "HR Zones", "❤️"),
                    SkillTab("review", "Review", "🔁"),
                    SkillTab("log", "Log", "📋")
                )
            )
            "stress" -> SkillToolkitTemplate(
                shortName = "Calm",
                tabs = listOf(
                    SkillTab("breathe", "Breathe", "🌬️"),
                    SkillTab("hr", "Heart", "❤️"),
                    SkillTab("review", "Review", "🔁"),
                    SkillTab("log", "Log", "📋")
                )
            )
            else -> templateForSkillType(getSkillType(skillName), skillName)
        }
    }

    private fun templateForSkillType(type: SkillType, skillName: String): SkillToolkitTemplate {
        val name = skillName.takeIf { it != "none" && it.isNotBlank() } ?: "Skill"
        return when (type) {
            SkillType.MUSIC -> SkillToolkitTemplate(
                shortName = name,
                tabs = listOf(
                    SkillTab("tune", "Tune", "🎯"),
                    SkillTab("chords", "Chords", "🎸"),
                    SkillTab("review", "Review", "🔁"),
                    SkillTab("log", "Log", "📋")
                )
            )
            SkillType.LANGUAGE -> SkillToolkitTemplate(
                shortName = name,
                tabs = listOf(
                    SkillTab("vocab", "Vocab", "📚"),
                    SkillTab("grammar", "Grammar", "✏️"),
                    SkillTab("review", "Review", "🔁"),
                    SkillTab("log", "Log", "📋")
                )
            )
            SkillType.CODE -> SkillToolkitTemplate(
                shortName = name,
                tabs = listOf(
                    SkillTab("challenges", "Code", "💻"),
                    SkillTab("review", "Review", "🔁"),
                    SkillTab("log", "Log", "📋")
                )
            )
            SkillType.MATH -> SkillToolkitTemplate(
                shortName = name,
                tabs = listOf(
                    SkillTab("problems", "Problems", "🔢"),
                    SkillTab("review", "Review", "🔁"),
                    SkillTab("log", "Log", "📋")
                )
            )
            SkillType.PHYSICAL -> SkillToolkitTemplate(
                shortName = name,
                tabs = listOf(
                    SkillTab("form", "Form", "🧍"),
                    SkillTab("zones", "HR Zones", "❤️"),
                    SkillTab("review", "Review", "🔁"),
                    SkillTab("log", "Log", "📋")
                )
            )
            SkillType.ART -> SkillToolkitTemplate(
                shortName = name,
                tabs = listOf(
                    SkillTab("canvas", "Draw", "🎨"),
                    SkillTab("review", "Review", "🔁"),
                    SkillTab("log", "Log", "📋")
                )
            )
            SkillType.DEFAULT -> SkillToolkitTemplate(
                shortName = name,
                tabs = listOf(
                    SkillTab("review", "Review", "🔁"),
                    SkillTab("log", "Log", "📋")
                )
            )
        }
    }

    fun bottomBarLabel(trackingMode: String, skillName: String): String {
        val emoji = when (trackingMode) {
            "ukulele" -> "🎸"
            "japanese" -> "🇯🇵"
            "pushup" -> "💪"
            "running" -> "🏃"
            "stress" -> "🌬️"
            else -> when (getSkillType(skillName)) {
                SkillType.MUSIC -> "🎵"
                SkillType.LANGUAGE -> "🗣️"
                SkillType.CODE -> "💻"
                SkillType.MATH -> "🔢"
                SkillType.PHYSICAL -> "🏋️"
                SkillType.ART -> "🎨"
                SkillType.DEFAULT -> "🧰"
            }
        }
        return "$emoji Kit"
    }
}
