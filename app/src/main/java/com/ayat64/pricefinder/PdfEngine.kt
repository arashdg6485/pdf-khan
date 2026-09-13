package com.ayat64.pricefinder

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.Locale

object PdfEngine {
    fun extractAndParse(context: Context, file: File): List<ProductDraft> {
        PDFBoxResourceLoader.init(context)
        PDDocument.load(file).use { doc ->
            val out = mutableListOf<ProductDraft>()
            val stripper = PDFTextStripper()
            for (page in 1..doc.numberOfPages) {
                stripper.startPage = page
                stripper.endPage = page
                val text = runCatching { stripper.getText(doc) }.getOrDefault("")
                if (text.isNotBlank()) out += parsePage(text, page)
            }
            return dedupe(out)
        }
    }

    private fun parsePage(text: String, page: Int): List<ProductDraft> {
        val lines = text.lines()
            .map { it.replace('\u00A0',' ').trim() }
            .filter { it.isNotBlank() }
        val result = mutableListOf<ProductDraft>()
        for (i in lines.indices) {
            val line = lines[i]
            val price = findPrice(line) ?: continue
            val window = ((i-3).coerceAtLeast(0)..(i+2).coerceAtMost(lines.lastIndex))
                .map { lines[it] }
            val joined = window.joinToString(" ")
            val code = findCode(joined)
            val candidates = window.filter { it != line && !looksLikeHeader(it) }
                .filterNot { findPrice(it) != null }
                .filterNot { looksLikeOnlyNumber(it) }
            val name = candidates.maxByOrNull { Persian.normalize(it).length } ?: ""
            val description = candidates.firstOrNull { it != name } ?: ""
            val specs = candidates.filter { it.length > 18 && it != name && it != description }
                .joinToString(" ").take(240)
            result += ProductDraft(
                name = clean(name),
                code = clean(code),
                description = clean(description),
                specs = clean(specs),
                price = price,
                page = page
            )
        }
        return result
    }

    private fun findPrice(s: String): String? {
        val n = Persian.toEnglishDigits(s)
        val money = Regex("""(?<!\d)(\d{1,3}(?:[,\s]\d{3})+(?:\.\d+)?|\d{4,})(?:\s*)(تومان|تومن|ریال|ر|ت)?""",
            RegexOption.IGNORE_CASE)
        val matches = money.findAll(n).toList()
        val m = matches.lastOrNull() ?: return null
        val raw = m.value.replace(" ","")
        val digits = raw.replace(Regex("[^0-9.]"), "")
        val value = digits.substringBefore('.').toLongOrNull() ?: return null
        // Ignore years, page numbers and tiny quantities.
        if (value < 1000 || value in 1300..1500) return null
        val unit = when {
            raw.contains("ریال") || raw.endsWith("ر") -> " ریال"
            raw.contains("تومان") || raw.contains("تومن") || value < 100_000_000 -> " تومان"
            else -> ""
        }
        return formatNumber(value) + unit
    }

    private fun findCode(s: String): String {
        val n = Persian.toEnglishDigits(s)
        val patterns = listOf(
            Regex("""(?i)(?:کد|code|مدل|model|sku|item)\s*[:#\-]?\s*([A-Za-z0-9آ-ی][A-Za-z0-9آ-ی\-_/.]{2,})"""),
            Regex("""\b[A-Z]{1,5}[-_/]?\d{2,}[A-Z0-9\-_/]*\b"""),
            Regex("""\b\d{4,12}\b""")
        )
        return patterns.asSequence().mapNotNull { it.find(n)?.groupValues?.getOrNull(1) ?: it.find(n)?.value }
            .firstOrNull()?.trim() ?: ""
    }

    private fun looksLikeHeader(s: String): Boolean {
        val x = Persian.normalize(s)
        return x in setOf("نام کالا","شرح کالا","کد کالا","قیمت","قیمت فروش","مشخصات","ردیف","تعداد","واحد")
    }

    private fun looksLikeOnlyNumber(s: String) = s.replace(Regex("""[\d\s,./\-]"""),"").isBlank()

    private fun clean(s: String): String = s.replace(Regex("""\s+""")," ").trim()

    private fun formatNumber(v: Long): String =
        String.format(Locale.US, "%,d", v)

    private fun dedupe(items: List<ProductDraft>): List<ProductDraft> =
        items.distinctBy { "${Persian.normalize(it.code)}|${Persian.normalize(it.name)}|${it.price}|${it.page}" }
}
