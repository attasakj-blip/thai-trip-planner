package th.trip.planner.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * The app's 30 menu languages. Thai and English are written in the code itself (every text is
 * given as tr("ไทย", "English")); the other 28 come from assets/i18n/<code>.json, which map the
 * English text to its translation. Texts with values in them use {0}, {1}… in those files.
 *
 * The 28 were chosen from Ministry of Tourism and Sports arrival figures for 2023–2025, ranking
 * languages by how many visitors speak them (see README).
 */
object I18n {
    data class Lang(val code: String, val native: String, val english: String, val rtl: Boolean = false) {
        val locale: Locale get() = Locale.forLanguageTag(code)
    }

    val LANGS = listOf(
        Lang("th", "ไทย", "Thai"),
        Lang("en", "English", "English"),
        Lang("zh-CN", "简体中文", "Chinese (Simplified)"),
        Lang("ms", "Bahasa Melayu", "Malay"),
        Lang("hi", "हिन्दी", "Hindi"),
        Lang("ru", "Русский", "Russian"),
        Lang("zh-TW", "繁體中文", "Chinese (Traditional)"),
        Lang("ko", "한국어", "Korean"),
        Lang("de", "Deutsch", "German"),
        Lang("ja", "日本語", "Japanese"),
        Lang("lo", "ລາວ", "Lao"),
        Lang("vi", "Tiếng Việt", "Vietnamese"),
        Lang("fr", "Français", "French"),
        Lang("id", "Bahasa Indonesia", "Indonesian"),
        Lang("ar", "العربية", "Arabic", rtl = true),
        Lang("fil", "Filipino", "Filipino"),
        Lang("my", "မြန်မာ", "Burmese"),
        Lang("km", "ខ្មែរ", "Khmer"),
        Lang("nl", "Nederlands", "Dutch"),
        Lang("es", "Español", "Spanish"),
        Lang("he", "עברית", "Hebrew", rtl = true),
        Lang("it", "Italiano", "Italian"),
        Lang("sv", "Svenska", "Swedish"),
        Lang("pl", "Polski", "Polish"),
        Lang("bn", "বাংলা", "Bengali"),
        Lang("da", "Dansk", "Danish"),
        Lang("nb", "Norsk bokmål", "Norwegian"),
        Lang("pt", "Português", "Portuguese"),
        Lang("tr", "Türkçe", "Turkish"),
        Lang("fi", "Suomi", "Finnish"),
    )

    /** Current language code. Compose state, so every screen redraws when it changes. */
    var lang by mutableStateOf("th")
        private set

    val current: Lang get() = LANGS.firstOrNull { it.code == lang } ?: LANGS[1]
    val thai: Boolean get() = lang == "th"

    private class Table(val exact: Map<String, String>, val templates: List<Pair<Regex, String>>)

    private var table: Table? = null

    /** The language that best matches the phone's own setting, or English. */
    fun deviceDefault(): String {
        val l = Locale.getDefault()
        val tag = l.toLanguageTag()
        LANGS.firstOrNull { it.code.equals(tag, true) }?.let { return it.code }
        if (l.language == "zh") return if (l.country in setOf("TW", "HK", "MO") || l.script == "Hant") "zh-TW" else "zh-CN"
        val base = when (l.language) { "in" -> "id"; "iw" -> "he"; "tl" -> "fil"; "no", "nn" -> "nb"; else -> l.language }
        return LANGS.firstOrNull { it.code == base }?.code ?: "en"
    }

    fun set(ctx: Context, code: String) {
        val c = LANGS.firstOrNull { it.code == code }?.code ?: "en"
        table = if (c == "th" || c == "en") null else load(ctx, c)
        lang = c
    }

    private fun load(ctx: Context, code: String): Table? = runCatching {
        val text = ctx.assets.open("i18n/$code.json").bufferedReader().use { it.readText() }
        val obj = Json.parseToJsonElement(text) as JsonObject
        val map = obj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }.toMap()
        val placeholder = Regex("\\{(\\d+)\\}")
        val templates = map.filterKeys { placeholder.containsMatchIn(it) }.map { (k, v) ->
            // "Add {0} photos" -> ^Add (.+?) photos$
            val parts = placeholder.split(k)
            val pattern = buildString {
                append('^')
                parts.forEachIndexed { i, p -> append(Regex.escape(p)); if (i < parts.size - 1) append("(.+?)") }
                append('$')
            }
            Regex(pattern, RegexOption.DOT_MATCHES_ALL) to v
        }
        Table(map, templates)
    }.getOrNull()

    /** The text in the current language: Thai, English, or the translation of the English text. */
    fun t(th: String, en: String): String = when (lang) {
        "th" -> th
        "en" -> en
        else -> translate(en)
    }

    fun translate(en: String): String {
        val tb = table ?: return en
        tb.exact[en]?.let { return it }
        for ((re, out) in tb.templates) {
            val m = re.matchEntire(en) ?: continue
            var r = out
            m.groupValues.drop(1).forEachIndexed { i, v -> r = r.replace("{$i}", v) }
            return r
        }
        return en
    }
}
