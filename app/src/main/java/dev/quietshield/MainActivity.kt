package dev.quietshield

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.quietshield.filter.LocalDnsVpnService
import dev.quietshield.filter.RootDnsService
import dev.quietshield.schedule.ScheduleCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private val Night = Color(0xFF0B0D0C)
private val Panel = Color(0xFF151917)
private val Panel2 = Color(0xFF1D2420)
private val Lime = Color(0xFFB5F36A)
private val Muted = Color(0xFF8A948D)
private val White = Color(0xFFE8EEE9)

class MainActivity : ComponentActivity() {
    private var fallbackLauncher: (() -> Unit)? = null
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) startVpn() else Toast.makeText(this, "需要 VPN 授权才能过滤网络 DNS", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 40)
        setContent { QuietShieldApp(::startProtection, ::stopProtection, ::askVpnPermission) }
    }

    private fun askVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent == null) startVpn() else vpnConsent.launch(intent)
    }
    private fun startVpn() {
        ContextCompat.startForegroundService(this, Intent(this, LocalDnsVpnService::class.java))
        QuietPrefs.setNetworkMode(this, "vpn")
    }
    private fun startProtection() {
        if (QuietPrefs.packages(this, "network_apps").isEmpty()) {
            Toast.makeText(this, "先在“应用”中勾选网络保护范围", Toast.LENGTH_LONG).show()
            return
        }
        if (QuietPrefs.networkMode(this) == "root") {
            val filter = IntentFilter(RootDnsService.ACTION_FAILED)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    unregisterReceiver(this)
                    Toast.makeText(context, "Root 不可用，切换到 VPN：${intent.getStringExtra("reason")}", Toast.LENGTH_LONG).show()
                    askVpnPermission()
                }
            }
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.startForegroundService(this, Intent(this, RootDnsService::class.java))
        } else askVpnPermission()
    }
    private fun stopProtection() {
        stopService(Intent(this, LocalDnsVpnService::class.java).setAction(LocalDnsVpnService.ACTION_STOP))
        stopService(Intent(this, RootDnsService::class.java).setAction(RootDnsService.ACTION_STOP))
    }
}

@Composable
private fun QuietShieldApp(start: () -> Unit, stop: () -> Unit, askVpn: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    MaterialTheme(colorScheme = darkColorScheme(background = Night, surface = Panel, primary = Lime, onPrimary = Night, onBackground = White, onSurface = White, secondary = Lime)) {
        Scaffold(containerColor = Night, bottomBar = {
            NavigationBar(containerColor = Panel) {
                listOf("总览", "应用", "规则", "设置").forEachIndexed { i, label ->
                    val glyphs = listOf("⌂", "▦", "◇", "⚙")
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(glyphs[i], fontSize = 19.sp, fontFamily = FontFamily.Monospace) }, label = { Text(label) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Lime, selectedTextColor = Lime, indicatorColor = Panel2, unselectedIconColor = Muted, unselectedTextColor = Muted))
                }
            }
        }) { padding ->
            when (tab) {
                0 -> HomeScreen(start, stop, askVpn, Modifier.padding(padding))
                1 -> AppsScreen(Modifier.padding(padding))
                2 -> RulesScreen(Modifier.padding(padding))
                else -> SettingsScreen(Modifier.padding(padding), start, stop)
            }
        }
    }
}

@Composable
private fun PageColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().background(Night).padding(horizontal = 20.dp), content = content)
}

@Composable
private fun HomeScreen(start: () -> Unit, stop: () -> Unit, askVpn: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var running by remember { mutableStateOf(QuietPrefs.bool(context, "network_running")) }
    var mode by remember { mutableStateOf(QuietPrefs.networkMode(context)) }
    var dialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); tick = System.currentTimeMillis(); running = QuietPrefs.bool(context, "network_running"); mode = QuietPrefs.networkMode(context) } }
    PageColumn(modifier) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Panel2), Alignment.Center) { Text("Q", color = Lime, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, fontSize = 21.sp) }
            Spacer(Modifier.width(12.dp))
            Column { Text("QUIETSHIELD", fontWeight = FontWeight.Black, letterSpacing = 1.6.sp); Text("本地规则 · 设备内运行", color = Muted, fontSize = 12.sp) }
            Spacer(Modifier.weight(1f)); Text("0.1.0", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (running) "保护中" else "保护已关闭", color = if (running) Lime else Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp)); Text(QuietPrefs.blocked(context).toString(), fontSize = 42.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text("DNS 请求拦截数", color = Muted, fontSize = 12.sp)
                    }
                    Switch(checked = running, onCheckedChange = { if (it) start() else stop() }, colors = SwitchDefaults.colors(checkedThumbColor = Night, checkedTrackColor = Lime))
                }
                Spacer(Modifier.height(18.dp)); HorizontalDivider(color = Panel2); Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("运行时间", if (running) DateUtils.formatElapsedTime(((tick - QuietPrefs.long(context, "network_started")) / 1000).coerceAtLeast(0)) else "—")
                    val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024
                    Metric("内存占用", "$mem MB")
                    Metric("保护应用", "${QuietPrefs.packages(context, "network_apps").size}")
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Text("网络模式", fontWeight = FontWeight.Bold, fontSize = 17.sp); Spacer(Modifier.weight(1f)); TextButton(onClick = { dialog = true }) { Text("切换模式", color = Lime) } }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeCard("VPN", "按应用 DNS 过滤", mode == "vpn", Modifier.weight(1f)) { mode = "vpn"; QuietPrefs.setNetworkMode(context, "vpn"); if (running) { stop(); askVpn() } }
            ModeCard("ROOT", "需设备 Root", mode == "root", Modifier.weight(1f)) { mode = "root"; QuietPrefs.setNetworkMode(context, "root"); if (running) { stop(); start() } }
        }
        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().clickable { QuietPrefs.setUiEnabled(context, !QuietPrefs.isUiEnabled(context)) }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Panel2), Alignment.Center) { Text("UI", color = Lime, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("界面规则引擎", fontWeight = FontWeight.SemiBold); Text(if (QuietPrefs.isUiEnabled(context)) "点击查看无障碍服务状态" else "无障碍服务单独授权", color = Muted, fontSize = 12.sp) }
                Switch(checked = QuietPrefs.isUiEnabled(context), onCheckedChange = {
                    QuietPrefs.setUiEnabled(context, it)
                    if (it) context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, colors = SwitchDefaults.colors(checkedThumbColor = Night, checkedTrackColor = Lime))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("过滤结果只统计命中规则的 DNS 请求。加密 DNS、IPv6 与同域广告可能绕过域名规则。", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
    }
    if (dialog) AlertDialog(onDismissRequest = { dialog = false }, confirmButton = { TextButton(onClick = { dialog = false }) { Text("知道了", color = Lime) } }, title = { Text("拦截能力说明") }, text = { Text("VPN/Root 模式过滤域名解析，不会读取其他应用的网页内容。界面规则需单独启用无障碍权限，且只对你勾选的应用生效。Root 模式目前仅拦截 IPv4 UDP DNS。") }, containerColor = Panel)
}

@Composable
private fun Metric(title: String, value: String) {
    Column { Text(title, color = Muted, fontSize = 11.sp); Spacer(Modifier.height(4.dp)); Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace) }
}

@Composable
private fun ModeCard(title: String, sub: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF25351D) else Panel), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(11.dp).clip(CircleShape).background(if (selected) Lime else Muted)); Spacer(Modifier.width(10.dp)); Column { Text(title, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace); Text(sub, color = Muted, fontSize = 10.sp) }
        }
    }
}

@Composable
private fun AppsScreen(modifier: Modifier) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var scope by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    val apps = remember(refresh) { QuietPrefs.installedApps(context) }
    var network by remember { mutableStateOf(QuietPrefs.packages(context, "network_apps")) }
    var ui by remember { mutableStateOf(QuietPrefs.packages(context, "ui_apps")) }
    PageColumn(modifier) {
        Header("应用范围", "首次默认不选择任何应用")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = scope == 0, onClick = { scope = 0 }, label = { Text("网络拦截 ${network.size}") })
            FilterChip(selected = scope == 1, onClick = { scope = 1 }, label = { Text("界面规则 ${ui.size}") })
        }
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索应用名称 / 包名") }, singleLine = true, shape = RoundedCornerShape(14.dp))
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(apps.filter { it.label.contains(search, true) || it.packageName.contains(search, true) }, key = { it.packageName }) { app ->
                val checked = if (scope == 0) app.packageName in network else app.packageName in ui
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                    if (scope == 0) { network = network.toMutableSet().apply { if (checked) remove(app.packageName) else add(app.packageName) }; QuietPrefs.setPackages(context, "network_apps", network) }
                    else { ui = ui.toMutableSet().apply { if (checked) remove(app.packageName) else add(app.packageName) }; QuietPrefs.setPackages(context, "ui_apps", ui) }
                }.padding(vertical = 8.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Panel2), Alignment.Center) { Text(app.label.take(1), color = Lime, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(app.label, fontWeight = FontWeight.Medium); Text(app.packageName, color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1) }
                    Checkbox(checked = checked, onCheckedChange = { value -> if (scope == 0) { network = network.toMutableSet().apply { if (value) add(app.packageName) else remove(app.packageName) }; QuietPrefs.setPackages(context, "network_apps", network) } else { ui = ui.toMutableSet().apply { if (value) add(app.packageName) else remove(app.packageName) }; QuietPrefs.setPackages(context, "ui_apps", ui) } }, colors = CheckboxDefaults.colors(checkedColor = Lime, checkmarkColor = Night))
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column(Modifier.padding(top = 18.dp, bottom = 14.dp)) { Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(3.dp)); Text(subtitle, color = Muted, fontSize = 12.sp) }
}

@Composable
private fun RulesScreen(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var networkRules by remember { mutableStateOf(QuietPrefs.domains(context)) }
    var uiRules by remember { mutableStateOf(QuietPrefs.uiRules(context)) }
    var selected by remember { mutableIntStateOf(0) }
    var addDialog by remember { mutableStateOf(false) }
    var subscriptionDialog by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { runCatching { context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }.onSuccess { content ->
            val count = runCatching { if (content.trimStart().startsWith("{") || content.trimStart().startsWith("[")) RuleImporter.importGkdText(context, content) else RuleImporter.importDomains(context, content) }.getOrElse { e -> Toast.makeText(context, e.message ?: "导入失败", Toast.LENGTH_LONG).show(); 0 }
            networkRules = QuietPrefs.domains(context); uiRules = QuietPrefs.uiRules(context); Toast.makeText(context, "已导入 $count 条", Toast.LENGTH_SHORT).show()
        } }
    }
    PageColumn(modifier) {
        Header("规则中心", "本地规则、手动导入和订阅")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selected == 0, onClick = { selected = 0 }, label = { Text("域名 ${networkRules.size}") })
            FilterChip(selected = selected == 1, onClick = { selected = 1 }, label = { Text("界面 ${uiRules.size}") })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/json", "application/octet-stream")) }, modifier = Modifier.weight(1f)) { Text("导入文件") }
            OutlinedButton(onClick = { input = QuietPrefs.string(context, "subscription_url"); subscriptionDialog = true }, modifier = Modifier.weight(1f)) { Text("订阅链接") }
            Button(onClick = { input = ""; addDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Night)) { Text("添加") }
        }
        Spacer(Modifier.height(10.dp))
        if (selected == 0) LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(networkRules, key = { it.host }) { rule ->
                RuleRow(rule.host, rule.source, rule.enabled, { enabled -> networkRules = networkRules.map { if (it == rule) it.copy(enabled = enabled) else it }; QuietPrefs.setDomains(context, networkRules) }, { networkRules = networkRules.filterNot { it == rule }; QuietPrefs.setDomains(context, networkRules) })
            }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(uiRules, key = { it.keyword + it.group }) { rule ->
                RuleRow("包含文字：${rule.keyword}", rule.group, rule.enabled, { enabled -> uiRules = uiRules.map { if (it == rule) it.copy(enabled = enabled) else it }; QuietPrefs.setUiRules(context, uiRules) }, { uiRules = uiRules.filterNot { it == rule }; QuietPrefs.setUiRules(context, uiRules) })
            }
        }
    }
    if (addDialog) AlertDialog(onDismissRequest = { addDialog = false }, title = { Text(if (selected == 0) "添加域名规则" else "添加界面文字规则") }, text = { OutlinedTextField(input, { input = it }, label = { Text(if (selected == 0) "example.com" else "按钮文字，如跳过") }, singleLine = true) }, confirmButton = { TextButton(onClick = {
        if (input.isNotBlank()) {
            if (selected == 0) { networkRules = networkRules + DomainRule(input.trim().lowercase()); QuietPrefs.setDomains(context, networkRules) }
            else { uiRules = uiRules + UiRule(input.trim(), true, "自定义"); QuietPrefs.setUiRules(context, uiRules) }
        }; addDialog = false
    }) { Text("保存", color = Lime) } }, dismissButton = { TextButton(onClick = { addDialog = false }) { Text("取消") } }, containerColor = Panel)
    if (subscriptionDialog) AlertDialog(onDismissRequest = { subscriptionDialog = false }, title = { Text("检查订阅更新") }, text = { Column { Text("输入 GKD JSON5 订阅地址。仅手动更新；当前只执行可识别的文字匹配规则。", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(10.dp)); OutlinedTextField(input, { input = it }, label = { Text("https://…") }, singleLine = true) } }, confirmButton = { TextButton(enabled = !busy, onClick = {
        if (input.startsWith("https://")) { busy = true; QuietPrefs.setString(context, "subscription_url", input); scope.launch { runCatching { withContext(Dispatchers.IO) { RuleImporter.importGkdText(context, RuleImporter.fetch(input)) } }.onSuccess { count -> uiRules = QuietPrefs.uiRules(context); Toast.makeText(context, "订阅已更新：$count 条", Toast.LENGTH_LONG).show(); subscriptionDialog = false }.onFailure { Toast.makeText(context, "订阅失败：${it.message}", Toast.LENGTH_LONG).show() }; busy = false } }
    }) { Text(if (busy) "正在获取…" else "更新", color = Lime) } }, dismissButton = { TextButton(onClick = { subscriptionDialog = false }) { Text("关闭") } }, containerColor = Panel)
}

@Composable
private fun RuleRow(title: String, group: String, enabled: Boolean, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(group, color = Muted, fontSize = 11.sp) }
            Switch(checked = enabled, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedThumbColor = Night, checkedTrackColor = Lime))
            TextButton(onClick = onDelete) { Text("删除", color = Muted, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier, start: () -> Unit, stop: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(QuietPrefs.bool(context, "schedule_enabled")) }
    var startTime by remember { mutableStateOf(QuietPrefs.string(context, "schedule_start", "23:00")) }
    var endTime by remember { mutableStateOf(QuietPrefs.string(context, "schedule_end", "07:00")) }
    var selectedDays by remember { mutableStateOf(QuietPrefs.string(context, "schedule_days", "1,2,3,4,5,6,7").split(',').filter { it.isNotBlank() }.map { it.toIntOrNull() ?: 1 }.toSet()) }
    PageColumn(modifier) {
        Header("设置", "计划任务 · 权限 · 运行状态")
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("定时保护", fontWeight = FontWeight.SemiBold); Text("按重复日期自动启停", color = Muted, fontSize = 12.sp) }; Switch(enabled, { enabled = it; QuietPrefs.setBool(context, "schedule_enabled", it); ScheduleCoordinator.schedule(context) }, colors = SwitchDefaults.colors(checkedThumbColor = Night, checkedTrackColor = Lime)) }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(startTime, { startTime = it }, Modifier.weight(1f), label = { Text("开始") }, placeholder = { Text("23:00") }, singleLine = true)
                    OutlinedTextField(endTime, { endTime = it }, Modifier.weight(1f), label = { Text("结束") }, placeholder = { Text("07:00") }, singleLine = true)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, day ->
                        FilterChip(selected = i + 1 in selectedDays, onClick = { selectedDays = selectedDays.toMutableSet().apply { if (contains(i + 1)) remove(i + 1) else add(i + 1) } }, label = { Text(day) })
                    }
                }
                Button(onClick = {
                    if (!startTime.matches(Regex("([01]\\d|2[0-3]):[0-5]\\d")) || !endTime.matches(Regex("([01]\\d|2[0-3]):[0-5]\\d")) || selectedDays.isEmpty()) Toast.makeText(context, "请填写有效时间并选择日期", Toast.LENGTH_SHORT).show()
                    else { QuietPrefs.setString(context, "schedule_start", startTime); QuietPrefs.setString(context, "schedule_end", endTime); QuietPrefs.setString(context, "schedule_days", selectedDays.sorted().joinToString(",")); ScheduleCoordinator.schedule(context); Toast.makeText(context, "计划已保存", Toast.LENGTH_SHORT).show() }
                }, colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Night), modifier = Modifier.fillMaxWidth()) { Text("保存计划") }
            }
        }
        Spacer(Modifier.height(12.dp))
        InfoCard("无障碍权限", "界面规则需在系统设置中单独启用。只对勾选应用读取控件并执行匹配动作。", { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        InfoCard("权限说明", "VPN 只建立本地 DNS 过滤通道；不会将流量转发到远程 VPN 服务。订阅仅在你手动更新时联网。", {})
        InfoCard("运行统计", "域名拦截 ${QuietPrefs.blocked(context)} 次 · 界面规则触发 ${QuietPrefs.uiHits(context)} 次\n最近触发：${QuietPrefs.string(context, "last_hit", "暂无")}", {})
        Text("APK 由 GitHub Actions 构建。标签版本可配置签名密钥生成发布包。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 18.dp))
    }
}

@Composable
private fun InfoCard(title: String, body: String, action: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = action), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(15.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(5.dp)); Text(body, color = Muted, fontSize = 12.sp, lineHeight = 17.sp) }
    }
}















