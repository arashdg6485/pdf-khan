package com.ayat64.pricefinder

data class SearchHit(val product: Product, val score: Int)

object Persian {
    private val digitMap = mapOf(
        '۰' to '0','۱' to '1','۲' to '2','۳' to '3','۴' to '4',
        '۵' to '5','۶' to '6','۷' to '7','۸' to '8','۹' to '9',
        '٠' to '0','١' to '1','٢' to '2','٣' to '3','٤' to '4',
        '٥' to '5','٦' to '6','٧' to '7','٨' to '8','٩' to '9'
    )
    fun toEnglishDigits(s: String): String = buildString {
        s.forEach { append(digitMap[it] ?: it) }
    }
    fun normalize(s: String): String = toEnglishDigits(s)
        .lowercase()
        .replace('ي','ی').replace('ى','ی').replace('ك','ک')
        .replace('ۀ','ه').replace('ة','ه')
        .replace(Regex("""[\u064B-\u065F\u0670]"""), "")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")
}

object SmartSearch {
    fun search(items: List<Product>, raw: String): List<SearchHit> {
        val q = Persian.normalize(raw)
        if (q.isBlank()) return emptyList()
        val tokens = q.split(" ").filter { it.length > 1 }
        return items.mapNotNull { p ->
            val fields = listOf(
                Persian.normalize(p.name) to 55,
                Persian.normalize(p.code) to 100,
                Persian.normalize(p.description) to 35,
                Persian.normalize(p.specs) to 30
            )
            var score = 0
            for ((field, weight) in fields) {
                if (field.isBlank()) continue
                if (field == q) score += weight + 45
                else if (field.contains(q)) score += weight
                for (t in tokens) {
                    if (field.split(" ").any { it == t }) score += (weight * .55).toInt()
                    else if (field.split(" ").any { similarity(it,t) >= .82 }) score += (weight * .28).toInt()
                }
            }
            // Numeric/code queries are intentionally given priority.
            if (q.all { it.isDigit() } && Persian.normalize(p.code).contains(q)) score += 140
            if (score > 0) SearchHit(p, score.coerceAtMost(999)) else null
        }.sortedByDescending { it.score }
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                cur[j+1] = minOf(cur[j] + 1, prev[j+1] + 1, prev[j] + cost)
            }
            for (j in prev.indices) prev[j] = cur[j]
        }
        val dist = prev[b.length]
        1.0 - dist.toDouble() / maxOf(a.length,b.length)
    }
}
