package dev.quietshield

import android.content.Context
import com.google.gson.JsonParser
import java.net.URL

object RuleImporter {
    fun importDomains(context: Context, text: String): Int {
        val existing = QuietPrefs.domains(context).associateBy { it.host.lowercase() }.toMutableMap()
        var added = 0
        text.lineSequence().map { it.substringBefore('#').trim().lowercase() }.forEach { line ->
            val candidate = if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) line.substringAfter(' ').trim() else line
            if (candidate.matches(Regex("(?i)([a-z0-9-]+\\.)+[a-z]{2,}")) && candidate != "localhost" && candidate !in existing) {
                existing[candidate] = DomainRule(candidate, true, "导入")
                added++
            }
        }
        QuietPrefs.setDomains(context, existing.values.toList())
        return added
    }

    /** Imports only GKD rules whose selector and click action fit the supported text-button subset. */
    fun importGkdText(context: Context, raw: String): Int {
        val root = JsonParser.parseString(Json5Compat.toJson(raw)).asJsonObject
        val appArray = root.getAsJsonArray("apps") ?: root.getAsJsonArray("data") ?: throw IllegalArgumentException("找不到 apps/data 列表")
        val imported = mutableListOf<UiRule>()
        var seenRules = 0
        appArray.forEach appLoop@ { appElement ->
            val app = appElement.asJsonObject
            val appId = app.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: throw IllegalArgumentException("GKD 应用缺少包名 id")
            val groups = app.getAsJsonArray("groups") ?: return@appLoop
            groups.forEach groupLoop@ { groupElement ->
                val group = groupElement.asJsonObject
                val groupName = group.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "GKD 订阅"
                val rules = group.getAsJsonArray("rules") ?: return@groupLoop
                rules.forEach { ruleElement ->
                    seenRules++
                    val rule = ruleElement.asJsonObject
                    val selector = rule.get("matches")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                        ?: rule.get("selector")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
                    val keyword = Regex("(?:text|textContains|textStartsWith)\\s*[:=]\\s*['\"]([^'\"]+)['\"]").find(selector)?.groupValues?.get(1)
                    val actions = rule.get("actions")?.toString().orEmpty()
                    if (keyword != null && actions.contains("click", true)) imported += UiRule(keyword, true, groupName, appId)
                }
            }
        }
        if (imported.isEmpty()) throw IllegalArgumentException("订阅中没有可执行的文字匹配点击规则；复杂选择器会被拒绝")
        if (imported.size != seenRules) throw IllegalArgumentException("仅识别 ${imported.size}/$seenRules 条规则。含复杂选择器或非点击动作，未导入任何规则")
        QuietPrefs.setUiRules(context, (QuietPrefs.uiRules(context) + imported).distinctBy { listOf(it.keyword, it.group, it.appPackage) })
        return imported.size
    }

    fun fetch(url: String): String {
        val connection = URL(url).openConnection()
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "QuietShield/0.1")
        return connection.getInputStream().bufferedReader().use { it.readText() }
    }
}

object Json5Compat {
    fun toJson(input: String): String {
        val out = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < input.length) {
            val ch = input[i]
            if (quote != null) {
                if (ch == '\\' && i + 1 < input.length) {
                    val next = input[i + 1]
                    if (quote == '\'' && next == '\'') out.append('\'')
                    else if (quote == '\'' && next == '"') out.append("\\\"")
                    else out.append(ch).append(next)
                    i += 2
                    continue
                }
                if (ch == quote) { out.append('"'); quote = null }
                else if (quote == '\'' && ch == '"') out.append("\\\"")
                else out.append(ch)
                i++
                continue
            }
            if (ch == '\'' || ch == '"') { quote = ch; out.append('"'); i++; continue }
            if (ch == '/' && i + 1 < input.length && input[i + 1] == '/') { while (i < input.length && input[i] != '\n') i++; continue }
            if (ch == '/' && i + 1 < input.length && input[i + 1] == '*') {
                i += 2
                while (i + 1 < input.length && !(input[i] == '*' && input[i + 1] == '/')) i++
                i = (i + 2).coerceAtMost(input.length)
                continue
            }
            if (ch == ',') {
                var n = i + 1
                while (n < input.length && input[n].isWhitespace()) n++
                if (n < input.length && (input[n] == '}' || input[n] == ']')) { i++; continue }
            }
            if (ch.isLetter() || ch == '_' || ch == '$') {
                val start = i
                while (i < input.length && (input[i].isLetterOrDigit() || input[i] == '_' || input[i] == '$')) i++
                val word = input.substring(start, i)
                var next = i
                while (next < input.length && input[next].isWhitespace()) next++
                if (next < input.length && input[next] == ':') out.append('"').append(word).append('"') else out.append(word)
                continue
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }
}
