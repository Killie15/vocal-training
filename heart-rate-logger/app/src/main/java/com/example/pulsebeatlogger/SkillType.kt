package com.example.pulsebeatlogger

enum class SkillType { CODE, LANGUAGE, MATH, PHYSICAL, MUSIC, ART, DEFAULT }

fun getSkillType(skillName: String): SkillType {
    val s = skillName.lowercase()
    return when {
        listOf(
            "python", "javascript", "typescript", "kotlin", "java", "swift", "go", "rust",
            "c++", "c#", "ruby", "php", "scala", "dart", "flutter", "react", "html", "css",
            "sql", "coding", "programming", "code", "algorithm", "leetcode", "data structure"
        ).any { s.contains(it) } -> SkillType.CODE

        listOf(
            "japanese", "mandarin", "chinese", "spanish", "french", "german", "italian",
            "korean", "portuguese", "arabic", "hindi", "russian", "english", "latin", "sign language",
            "language", "vocabulary", "grammar", "pronunciation", "translation", "linguistics"
        ).any { s.contains(it) } -> SkillType.LANGUAGE

        listOf(
            "math", "algebra", "calculus", "geometry", "trigonometry", "statistics", "probability",
            "physics", "chemistry", "logic", "equation", "formula", "arithmetic", "engineering"
        ).any { s.contains(it) } -> SkillType.MATH

        listOf(
            "running", "yoga", "pushup", "push-up", "squat", "deadlift", "workout", "gym",
            "cycling", "swimming", "weightlifting", "hiit", "cardio", "stretching", "pilates",
            "crossfit", "boxing", "martial arts", "karate", "judo", "fitness", "exercise", "sport"
        ).any { s.contains(it) } -> SkillType.PHYSICAL

        listOf(
            "piano", "guitar", "violin", "ukulele", "bass", "drums", "cello", "flute", "trumpet",
            "singing", "vocal", "choir", "music theory", "chord", "rhythm", "melody", "beat",
            "music", "instrument", "saxophone", "harmonica", "banjo", "mandolin", "tuning"
        ).any { s.contains(it) } -> SkillType.MUSIC

        listOf(
            "draw", "sketch", "paint", "watercolor", "oil paint", "acrylic", "illustration",
            "calligraphy", "lettering", "manga", "anime", "portrait", "figure drawing",
            "art", "doodle", "comic", "digital art", "procreate", "charcoal", "pencil shading"
        ).any { s.contains(it) } -> SkillType.ART

        else -> SkillType.DEFAULT
    }
}
