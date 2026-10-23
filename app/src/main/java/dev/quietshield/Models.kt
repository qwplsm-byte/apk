package dev.quietshield

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class DomainRule(val host: String, val enabled: Boolean = true, val source: String = "自定义")
data class UiRule(val keyword: String, val enabled: Boolean = true, val group: String = "跳过提示", val appPackage: String? = null)
data class InstalledApp(val packageName: String, val label: String)

object QuietPrefs {
    private const val FILE = "quietshield"
    private val gson = Gson()
    private fun p(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun <T> readList(context: Context, key: String, type: java.lang.reflect.Type, fallback: List<T>): List<T> =
        runCatching { gson.fromJson<List<T>>(p(context).getString(key, null), type) ?: fallback }.getOrDefault(fallback)
    private val domainsType = object : TypeToken<List<DomainRule>>() {}.type
    private val uiType = object : TypeToken<List<UiRule>>() {}.type
    private val stringsType = object : TypeToken<List<String>>() {}.type

    fun domains(c: Context): List<DomainRule> = readList(c, "domains", domainsType, listOf(
        DomainRule("doubleclick.net", true, "基础规则"), DomainRule("googlesyndication.com", true, "基础规则"),
        DomainRule("googleadservices.com", true, "基础规则"), DomainRule("adnxs.com", true, "基础规则")
    ))
    fun setDomains(c: Context, value: List<DomainRule>) { p(c).edit().putString("domains", gson.toJson(value)).apply() }
    fun uiRules(c: Context): List<UiRule> = readList(c, "ui_rules", uiType, listOf(UiRule("跳过"), UiRule("Skip"), UiRule("关闭"), UiRule("Close")))
    fun setUiRules(c: Context, value: List<UiRule>) { p(c).edit().putString("ui_rules", gson.toJson(value)).apply() }
    fun packages(c: Context, key: String): Set<String> = readList(c, key, stringsType, emptyList()).toSet()
    fun setPackages(c: Context, key: String, value: Set<String>) { p(c).edit().putString(key, gson.toJson(value.toList())).apply() }
    fun bool(c: Context, key: String, default: Boolean = false) = p(c).getBoolean(key, default)
    fun setBool(c: Context, key: String, value: Boolean) { p(c).edit().putBoolean(key, value).apply() }
    fun long(c: Context, key: String, default: Long = 0) = p(c).getLong(key, default)
    fun setLong(c: Context, key: String, value: Long) { p(c).edit().putLong(key, value).apply() }
    fun string(c: Context, key: String, default: String = "") = p(c).getString(key, default) ?: default
    fun setString(c: Context, key: String, value: String) { p(c).edit().putString(key, value).apply() }
    fun blocked(c: Context) = long(c, "blocked", 0)
    fun addBlocked(c: Context) { p(c).edit().putLong("blocked", blocked(c) + 1).apply() }
    fun uiHits(c: Context) = long(c, "ui_hits", 0)
    fun addUiHit(c: Context, packageName: String, keyword: String) {
        p(c).edit().putLong("ui_hits", uiHits(c) + 1).putString("last_hit", "$packageName · $keyword").apply()
    }
    fun isUiEnabled(c: Context) = bool(c, "ui_enabled")
    fun setUiEnabled(c: Context, value: Boolean) = setBool(c, "ui_enabled", value)
    fun networkMode(c: Context) = string(c, "network_mode", "vpn")
    fun setNetworkMode(c: Context, value: String) = setString(c, "network_mode", value)

    @Suppress("DEPRECATION")
    fun installedApps(c: Context): List<InstalledApp> {
        val pm = c.packageManager
        val apps = if (Build.VERSION.SDK_INT >= 33) pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0)) else pm.getInstalledApplications(0)
        return apps.asSequence().filter { it.packageName != c.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { InstalledApp(it.packageName, runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName)) }
            .sortedBy { it.label.lowercase() }.toList()
    }
}



