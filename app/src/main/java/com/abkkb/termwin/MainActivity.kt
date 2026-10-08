package com.abkkb.termwin

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

const val VERSION = "1.1"

class TabData(var name: String, var cwd: String, val log: StringBuilder = StringBuilder()) {
    @Volatile var proc: Process? = null
}

class Srv(var name: String, var type: String, var port: Int, var cmd: String) {
    @Volatile var running = false
    var sock: ServerSocket? = null
    var proc: Process? = null
}

fun ensureChannels(c: Context) {
    val nm = c.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("done", "Comandos concluídos", NotificationManager.IMPORTANCE_DEFAULT))
    nm.createNotificationChannel(NotificationChannel("run", "Em execução", NotificationManager.IMPORTANCE_LOW))
}

/** Mantém o TermWin vivo em segundo plano enquanto há comando/servidor rodando. */
class TermService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        ensureChannels(this)
        val n = Notification.Builder(this, "run")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("TermWin")
            .setContentText("Executando comandos / servidores…")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
        return START_NOT_STICKY
    }
}

class MaxScroll(c: Context, private val maxH: Int) : ScrollView(c) {
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
}

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val tabs = mutableListOf<TabData>()
    private val servers = mutableListOf<Srv>()
    private val pkgs = mutableSetOf("neofetch")
    private val history = mutableListOf<String>()
    private val panels = mutableListOf<Panel>()
    private var cur = 0
    private var maximized = false
    private var bg = false
    private var busyN = 0
    private var notifId = 100
    private var serversRefresh: (() -> Unit)? = null
    private var settingsSync: (() -> Unit)? = null

    private lateinit var root: FrameLayout
    private lateinit var taskbar: TextView
    private var win: LinearLayout? = null
    private var tabBar: LinearLayout? = null
    private var body: FrameLayout? = null
    private var outView: TextView? = null
    private var scroll: ScrollView? = null

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val BG = 0xFF1F1F1F.toInt()
    private val TITLE = 0xFF272727.toInt()
    private val ACCENT = 0xFF60CDFF.toInt()
    private val GREEN = 0xFF3DDC84.toInt()

    private val TYPES = listOf(
        "youtube" to "YouTube", "web" to "Web simples", "files" to "Arquivos (pasta)",
        "json" to "API JSON", "cmd" to "Comando", "custom" to "Personalizado (HTML)"
    )
    private val EXTRA_HINT = mapOf(
        "cmd" to "Comando (ex.: python -m http.server 9000)",
        "files" to "Pasta a servir (vazio = pasta do TermWin)",
        "json" to "JSON da resposta (ex.: {\"ok\":true})",
        "custom" to "Seu HTML / texto da página — você decide"
    )
    private val ALIAS = mapOf(
        "youtube" to "youtube", "web" to "web", "arquivos" to "files", "files" to "files",
        "json" to "json", "api" to "json", "comando" to "cmd", "cmd" to "cmd",
        "personalizado" to "custom", "custom" to "custom"
    )
    private val CATALOG = linkedMapOf(
        "neofetch" to "mostra o logo do Android",
        "cowsay" to "uma vaca que fala",
        "arvore" to "lista pastas em árvore"
    )

    private val HELP = """Comandos do TermWin:
  ajuda | help               esta ajuda
  limpar | clear             limpa a tela
  cd <pasta>                 muda de pasta (cd storage = /sdcard)
  info                       dados do aparelho
  memoria                    uso de RAM
  armazenamento              espaço livre
  ip                         endereços de rede
  bateria                    nível da bateria
  notificar <texto>          testa uma notificação
  abrir <url>                abre no navegador
  historico                  comandos usados
  pkg install|remove|list|upgrade <pacote>
  termwin upgrade|versao|sobre
  neofetch | cowsay <txt> | arvore   (pacotes)
  servidor listar
  servidor criar <nome> [youtube|web|arquivos|json|comando|personalizado] [porta] [extra...]
  servidor iniciar|parar|remover <nome>
  servidor renomear <nome> <novo>
Qualquer outro comando roda no shell do Android (ls, pwd, cat, ping...).
Toque e segure numa aba para renomear.
"""

    private val LOGO = """         -o          o-
          +hydNNNNdyh+
        +mMMMMMMMMMMmm+
      `dMMm:NMMMMMMN:mMMd`
      hMMMMMMMMMMMMMMMMMMh
  ..  yyyyyyyyyyyyyyyyyyyy  ..
.mMMm`MMMMMMMMMMMMMMMMMMMM`mMMm.
:MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM:
:MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM:
:MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM:
:MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM:
-MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM-
 +yy+ MMMMMMMMMMMMMMMMMMMM +yy+
      mMMMMMMMMMMMMMMMMMMm
      `/++MMMMh++hMMMM++/`
          MMMMo  oMMMM
          MMMMo  oMMMM
          oNMm-  -mMNs"""

    private val COW = """        \   ^__^
         \  (oo)\_______
            (__)\       )\/\
                ||----w |
                ||     ||"""

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tv(s: String, sz: Float, c: Int) = TextView(this).apply { text = s; textSize = sz; setTextColor(c) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun rounded(c: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(c); cornerRadius = r.toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }
    private fun pressBg(p: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(p))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }
    private fun short(p: String) = p.replace(filesDir.path, "~")

    private fun pill(text: String, primary: Boolean, click: () -> Unit) =
        tv(text, 14f, if (primary) Color.BLACK else Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = rounded(if (primary) ACCENT else 0xFF2D2D2D.toInt(), dp(8), if (primary) 0 else 0xFF454545.toInt())
            setOnClickListener { click() }
        }

    private fun winBtn(text: String, primary: Boolean, click: () -> Unit) = pill(text, primary, click).apply {
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) }
    }

    private fun field(hint: String, init: String = "", multi: Boolean = false) = EditText(this).apply {
        setText(init); this.hint = hint
        setTextColor(Color.WHITE); setHintTextColor(0xFF777777.toInt()); textSize = 14f
        background = rounded(0xFF2D2D2D.toInt(), dp(6), 0xFF454545.toInt())
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (multi) { minLines = 2; gravity = Gravity.TOP; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        else { setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
    }

    private fun styled(s: String): CharSequence {
        if ('\u0001' !in s) return s
        val sb = SpannableStringBuilder()
        val parts = s.split("\n")
        parts.forEachIndexed { i, line ->
            if (line.startsWith("\u0001")) {
                val st = sb.length
                sb.append(line.substring(1))
                sb.setSpan(ForegroundColorSpan(GREEN), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else sb.append(line)
            if (i < parts.size - 1) sb.append('\n')
        }
        return sb
    }

    // ---------- painel estilo Windows (usado em todos os diálogos) ----------
    inner class Panel(val overlay: FrameLayout, val body: LinearLayout, val footer: LinearLayout) {
        var onClose: (() -> Unit)? = null
        fun close() { root.removeView(overlay); panels.remove(this); onClose?.invoke() }
        fun button(text: String, primary: Boolean = false, click: () -> Unit) = footer.addView(winBtn(text, primary, click))
    }

    private fun panel(title: String, frac: Float = 0.6f): Panel {
        val dm = resources.displayMetrics
        val ov = FrameLayout(this)
        ov.setBackgroundColor(0x99000000.toInt())
        ov.isClickable = true
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(0xFF202020.toInt(), dp(10), 0xFF3A3A3A.toInt())
        card.clipToOutline = true
        card.elevation = dp(16).toFloat()
        lateinit var p: Panel

        val tb = LinearLayout(this)
        tb.setBackgroundColor(TITLE)
        tb.addView(tv("   ▣   $title", 13f, Color.WHITE).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(40), 1f))
        tb.addView(capBtn("✕", true, 46) { p.close() })
        card.addView(tb, LinearLayout.LayoutParams(MATCH, dp(40)))

        val bodyBox = LinearLayout(this)
        bodyBox.orientation = LinearLayout.VERTICAL
        bodyBox.setPadding(dp(20), dp(10), dp(20), dp(16))
        val sc = MaxScroll(this, (dm.heightPixels * 0.6).toInt())
        sc.addView(bodyBox)
        card.addView(sc, LinearLayout.LayoutParams(MATCH, WRAP))

        val foot = LinearLayout(this)
        foot.orientation = LinearLayout.HORIZONTAL
        foot.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        foot.setBackgroundColor(0xFF1A1A1A.toInt())
        foot.setPadding(dp(16), dp(10), dp(16), dp(10))
        card.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))

        ov.addView(card, FrameLayout.LayoutParams((dm.widthPixels * frac).toInt(), WRAP, Gravity.CENTER))
        root.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        p = Panel(ov, bodyBox, foot)
        panels.add(p)
        return p
    }

    // ---------- ciclo de vida ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        load()
        ensureChannels(this)
        root = FrameLayout(this)
        root.setBackgroundColor(0xFF0B0F14.toInt())

        val home = LinearLayout(this)
        home.orientation = LinearLayout.VERTICAL
        home.gravity = Gravity.CENTER
        home.addView(tv("TermWin", 34f, Color.WHITE).apply { typeface = Typeface.MONOSPACE })
        home.addView(tv("terminal + servidores + janela Windows 11", 14f, 0xFF8899AA.toInt()).apply { setPadding(0, dp(4), 0, dp(20)) })
        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.gravity = Gravity.CENTER
        val gap = { LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) } }
        btns.addView(pill("⊞  Abrir janela", true) { openWindow() })
        btns.addView(pill("⚙  Configurações", false) { showSettings() }, gap())
        btns.addView(pill("ⓘ  Créditos", false) { showCredits() }, gap())
        home.addView(btns, LinearLayout.LayoutParams(WRAP, WRAP))
        root.addView(home, FrameLayout.LayoutParams(MATCH, MATCH))

        taskbar = tv("⊞   ▣ TermWin", 14f, Color.WHITE)
        taskbar.setPadding(dp(20), dp(10), dp(20), dp(10))
        taskbar.background = rounded(0xEE2B2B2B.toInt(), dp(10), 0xFF444444.toInt())
        taskbar.visibility = View.GONE
        taskbar.setOnClickListener { restore() }
        root.addView(taskbar, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8) })
        setContentView(root)
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (panels.isNotEmpty()) panels.last().close() else super.onBackPressed() }
    override fun onStart() { super.onStart(); bg = false }
    override fun onStop() { super.onStop(); bg = true }
    override fun onResume() { super.onResume(); settingsSync?.invoke() }
    override fun onPause() { super.onPause(); save() }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        settingsSync?.invoke()
        val denied = res.isNotEmpty() && res[0] != PackageManager.PERMISSION_GRANTED
        if (code == 2 && denied && Build.VERSION.SDK_INT >= 33 && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openNotifSettings()
    }

    // ---------- persistência ----------
    private fun save() {
        val o = JSONObject()
        o.put("cur", cur)
        val ta = JSONArray()
        tabs.forEach { ta.put(JSONObject().put("n", it.name).put("c", it.cwd).put("l", it.log.toString().takeLast(20000))) }
        o.put("tabs", ta)
        val sa = JSONArray()
        servers.forEach { sa.put(JSONObject().put("n", it.name).put("t", it.type).put("p", it.port).put("c", it.cmd)) }
        o.put("servers", sa)
        o.put("pk", JSONArray(pkgs.toList()))
        getSharedPreferences("termwin", 0).edit().putString("d", o.toString()).apply()
    }

    private fun load() {
        try {
            val o = JSONObject(getSharedPreferences("termwin", 0).getString("d", "{}")!!)
            val ta = o.optJSONArray("tabs")
            if (ta != null) for (i in 0 until ta.length()) {
                val j = ta.getJSONObject(i)
                tabs.add(TabData(j.getString("n"), j.getString("c"), StringBuilder(j.optString("l"))))
            }
            val sa = o.optJSONArray("servers")
            if (sa != null) for (i in 0 until sa.length()) {
                val j = sa.getJSONObject(i)
                servers.add(Srv(j.getString("n"), j.getString("t"), j.getInt("p"), j.optString("c")))
            }
            val pk = o.optJSONArray("pk")
            if (pk != null) { pkgs.clear(); for (i in 0 until pk.length()) pkgs.add(pk.getString(i)) }
            cur = o.optInt("cur", 0)
        } catch (e: Exception) { }
        if (tabs.isEmpty()) tabs.add(newTabData("Terminal 1"))
        cur = cur.coerceIn(0, tabs.size - 1)
    }

    private fun newTabData(name: String) =
        TabData(name, filesDir.path, StringBuilder("TermWin $VERSION — digite 'ajuda' para ver os comandos.\n"))

    // ---------- permissões e notificações ----------
    private fun storageOk() = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun notifOk() = getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun askStorage() {
        if (Build.VERSION.SDK_INT >= 30)
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        else requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
    }

    private fun askNotif() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        else openNotifSettings()
    }

    private fun openNotifSettings() = try {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    } catch (e: Exception) { }

    private fun openAppSettings() {
        toast("Para desligar, remova a permissão nas configurações do Android")
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e: Exception) { }
    }

    private fun notifyDone(title: String, text: String, ok: Boolean, force: Boolean = false) {
        if (!bg && !force) return
        if (!notifOk()) { if (force) toast("Ligue as notificações em Configurações"); return }
        try {
            val pi = PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(this, "done")
                .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(title).setContentText(text).setContentIntent(pi).setAutoCancel(true).build()
            getSystemService(NotificationManager::class.java).notify(notifId++, n)
        } catch (e: Exception) { }
    }

    private fun busy(d: Int) {
        val before = busyN
        busyN = (busyN + d).coerceAtLeast(0)
        try {
            if (before == 0 && busyN > 0) startForegroundService(Intent(this, TermService::class.java))
            else if (before > 0 && busyN == 0) stopService(Intent(this, TermService::class.java))
        } catch (e: Exception) { }
    }

    // ---------- Configurações / Créditos ----------
    private fun showSettings() {
        val p = panel("Configurações", 0.55f)
        var syncing = false
        fun row(title: String, sub: String, onToggle: () -> Unit): Switch {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.background = rounded(0xFF2A2A2A.toInt(), dp(8))
            r.setPadding(dp(14), dp(10), dp(14), dp(10))
            val txt = LinearLayout(this)
            txt.orientation = LinearLayout.VERTICAL
            txt.addView(tv(title, 15f, Color.WHITE))
            txt.addView(tv(sub, 11f, 0xFF9AA5B1.toInt()))
            r.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f))
            val sw = Switch(this)
            sw.trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ACCENT, 0xFF555555.toInt()))
            sw.thumbTintList = ColorStateList.valueOf(Color.WHITE)
            sw.setOnCheckedChangeListener { _, _ -> if (!syncing) onToggle() }
            r.addView(sw)
            p.body.addView(r, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            return sw
        }
        val sSto = row("Armazenamento", "Acessar /sdcard no terminal (cd storage)") { if (!storageOk()) askStorage() else openAppSettings() }
        val sNot = row("Notificações", "Avisar quando um comando terminar (deu certo ou errado) com o app fora da tela") { if (!notifOk()) askNotif() else openAppSettings() }
        val sync = { syncing = true; sSto.isChecked = storageOk(); sNot.isChecked = notifOk(); syncing = false }
        settingsSync = sync
        p.onClose = { settingsSync = null }
        sync()
        p.button("Testar notificação") { notifyDone("✔ TermWin", "Notificação de teste funcionando", true, true) }
        p.button("Fechar", true) { p.close() }
    }

    private fun showCredits() {
        val p = panel("Créditos", 0.6f)
        fun line(a: String, b: String) {
            p.body.addView(tv(a, 12f, 0xFF9AA5B1.toInt()).apply { setPadding(0, dp(10), 0, 0) })
            p.body.addView(tv(b, 17f, Color.WHITE))
        }
        line("Criador", "Weverson Isaque  (GABEDEVELOPER)")
        line("Código escrito com", "Claude — IA criada pela Anthropic")
        p.body.addView(tv("O que o TermWin faz", 12f, 0xFF9AA5B1.toInt()).apply { setPadding(0, dp(14), 0, dp(4)) })
        p.body.addView(tv(
            "• Janela estilo Windows 11: mover, minimizar, maximizar\n" +
            "• Várias abas de terminal com histórico salvo\n" +
            "• Comandos do Android + comandos próprios (info, memoria, ip, bateria…)\n" +
            "• pkg / termwin upgrade e pacotes: neofetch, cowsay, arvore\n" +
            "• Servidores locais: YouTube, Web, Arquivos, API JSON, Comando e Personalizado\n" +
            "• Roda em segundo plano e avisa por notificação quando termina\n" +
            "• Acesso ao armazenamento com um toque", 13f, Color.WHITE))
        p.body.addView(tv("TermWin $VERSION", 11f, 0xFF667788.toInt()).apply { setPadding(0, dp(12), 0, 0) })
        p.button("Fechar", true) { p.close() }
    }

    // ---------- janela Windows 11 ----------
    private fun capBtn(t: String, red: Boolean, w: Int, click: () -> Unit) = tv(t, 14f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        background = pressBg(if (red) 0xFFC42B1C.toInt() else 0xFF3A3A3A.toInt())
        layoutParams = LinearLayout.LayoutParams(dp(w), dp(40))
        setOnClickListener { click() }
    }

    private fun openWindow() {
        if (win != null) { restore(); return }
        val dm = resources.displayMetrics
        val w = LinearLayout(this)
        w.orientation = LinearLayout.VERTICAL
        w.background = rounded(BG, dp(10), 0xFF3A3A3A.toInt())
        w.clipToOutline = true
        w.elevation = dp(12).toFloat()
        root.addView(w, FrameLayout.LayoutParams((dm.widthPixels * 0.88).toInt(), (dm.heightPixels * 0.86).toInt(), Gravity.CENTER))
        win = w

        val tb = LinearLayout(this)
        tb.orientation = LinearLayout.HORIZONTAL
        tb.setBackgroundColor(TITLE)
        val ttl = tv("   ▣   TermWin — Terminal", 13f, Color.WHITE)
        ttl.gravity = Gravity.CENTER_VERTICAL
        ttl.layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        var dx = 0f
        var dy = 0f
        ttl.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = w.translationX - e.rawX; dy = w.translationY - e.rawY }
                MotionEvent.ACTION_MOVE -> if (!maximized) { w.translationX = e.rawX + dx; w.translationY = e.rawY + dy }
            }
            true
        }
        tb.addView(ttl)
        tb.addView(capBtn("─", false, 46) { minimize() })
        tb.addView(capBtn("☐", false, 46) { toggleMax() })
        tb.addView(capBtn("✕", true, 46) { closeWindow() })
        w.addView(tb, LinearLayout.LayoutParams(MATCH, dp(40)))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.BOTTOM
        row.setBackgroundColor(0xFF2B2B2B.toInt())
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        hs.addView(bar)
        tabBar = bar
        row.addView(hs, LinearLayout.LayoutParams(0, dp(38), 1f))
        fun rb(t: String, wd: Int, accent: Boolean = false, click: () -> Unit) = row.addView(
            capBtn(t, false, wd, click).apply { textSize = if (wd > 50) 12f else 15f; if (accent) setTextColor(ACCENT); layoutParams = LinearLayout.LayoutParams(dp(wd), dp(38)) })
        rb("＋", 40) { addTab() }
        rb("Servidores", 88) { showServers() }
        rb("＋ Servidor", 88, true) { createServerDialog() }
        rb("⚙", 40) { showSettings() }
        rb("ⓘ", 40) { showCredits() }
        w.addView(row, LinearLayout.LayoutParams(MATCH, dp(38)))

        body = FrameLayout(this)
        w.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        refreshTabs()
        showTab()
    }

    private fun minimize() { win?.visibility = View.GONE; taskbar.visibility = View.VISIBLE }
    private fun restore() { win?.visibility = View.VISIBLE; taskbar.visibility = View.GONE; if (win == null) openWindow() }

    private fun closeWindow() {
        win?.let { root.removeView(it) }
        win = null; tabBar = null; body = null; outView = null; scroll = null
        taskbar.visibility = View.GONE
        save()
    }

    private fun toggleMax() {
        val w = win ?: return
        maximized = !maximized
        w.translationX = 0f; w.translationY = 0f
        val lp = w.layoutParams as FrameLayout.LayoutParams
        val dm = resources.displayMetrics
        lp.width = if (maximized) MATCH else (dm.widthPixels * 0.88).toInt()
        lp.height = if (maximized) MATCH else (dm.heightPixels * 0.86).toInt()
        w.layoutParams = lp
    }

    // ---------- abas ----------
    private fun refreshTabs() {
        val bar = tabBar ?: return
        bar.removeAllViews()
        val r = dp(8).toFloat()
        tabs.forEachIndexed { i, t ->
            val item = LinearLayout(this)
            item.orientation = LinearLayout.HORIZONTAL
            item.gravity = Gravity.CENTER_VERTICAL
            item.setPadding(dp(14), 0, dp(4), 0)
            if (i == cur) item.background = GradientDrawable().apply {
                setColor(0xFF0C0C0C.toInt()); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            item.addView(tv(t.name, 13f, if (i == cur) Color.WHITE else 0xFFAAAAAA.toInt()))
            val x = tv("✕", 11f, 0xFF999999.toInt())
            x.setPadding(dp(12), dp(8), dp(8), dp(8))
            x.setOnClickListener { closeTab(i) }
            item.addView(x)
            item.setOnClickListener { cur = i; refreshTabs(); showTab() }
            item.setOnLongClickListener { promptText("Renomear aba", t.name) { n -> t.name = n; refreshTabs(); save() }; true }
            bar.addView(item, LinearLayout.LayoutParams(WRAP, dp(34)))
        }
    }

    private fun addTab() {
        tabs.add(newTabData("Terminal ${tabs.size + 1}"))
        cur = tabs.size - 1
        refreshTabs(); showTab(); save()
    }

    private fun closeTab(i: Int) {
        if (tabs.size == 1) { toast("Mantenha pelo menos uma aba"); return }
        tabs[i].proc?.destroy()
        tabs.removeAt(i)
        cur = cur.coerceIn(0, tabs.size - 1)
        refreshTabs(); showTab(); save()
    }

    private fun showTab() {
        val b = body ?: return
        b.removeAllViews()
        val t = tabs[cur]
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(0xFF0C0C0C.toInt())
        val sc = ScrollView(this)
        val out = TextView(this)
        out.typeface = Typeface.MONOSPACE
        out.textSize = 12.5f
        out.setTextColor(0xFFCCCCCC.toInt())
        out.setPadding(dp(10), dp(8), dp(10), dp(8))
        out.setTextIsSelectable(true)
        out.text = styled(t.log.toString())
        sc.addView(out)
        col.addView(sc, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val inp = LinearLayout(this)
        inp.orientation = LinearLayout.HORIZONTAL
        inp.gravity = Gravity.CENTER_VERTICAL
        inp.setBackgroundColor(0xFF161616.toInt())
        inp.addView(tv("\$", 14f, 0xFF16C60C.toInt()).apply { setPadding(dp(12), 0, dp(6), 0); typeface = Typeface.MONOSPACE })
        val et = EditText(this)
        et.setSingleLine(true)
        et.typeface = Typeface.MONOSPACE
        et.textSize = 13f
        et.setTextColor(Color.WHITE)
        et.setBackgroundColor(Color.TRANSPARENT)
        et.hint = "comando"
        et.setHintTextColor(0xFF555555.toInt())
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        et.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        et.setOnEditorActionListener { _, _, _ -> submit(et); true }
        inp.addView(et, LinearLayout.LayoutParams(0, dp(44), 1f))
        inp.addView(capBtn("⏎", false, 44) { submit(et) }.apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) })
        inp.addView(capBtn("^C", false, 44) {
            tabs[cur].proc?.destroy()
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) })
        col.addView(inp, LinearLayout.LayoutParams(MATCH, dp(44)))
        b.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        outView = out
        scroll = sc
        sc.post { sc.fullScroll(View.FOCUS_DOWN) }
    }

    private fun append(t: TabData, s: String) {
        t.log.append(s)
        var trimmed = false
        if (t.log.length > 30000) { t.log.delete(0, t.log.length - 20000); trimmed = true }
        if (tabs.getOrNull(cur) === t) {
            if (trimmed) outView?.text = styled(t.log.toString()) else outView?.append(styled(s))
            scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun sayCur(s: String) { if (tabs.isNotEmpty()) append(tabs[cur.coerceIn(0, tabs.size - 1)], s) }

    // ---------- comandos ----------
    private fun submit(et: EditText) {
        val t = tabs[cur]
        val c = et.text.toString().trim()
        et.setText("")
        append(t, "${short(t.cwd)} \$ $c\n")
        if (c.isNotEmpty()) history.add(c)
        exec(t, c)
    }

    private fun exec(t: TabData, line: String) {
        if (line.isEmpty()) return
        val p = line.split(" ").filter { it.isNotEmpty() }
        when (p[0]) {
            "help", "ajuda" -> append(t, HELP)
            "clear", "limpar" -> { t.log.setLength(0); outView?.text = "" }
            "cd" -> {
                val target = p.getOrNull(1) ?: filesDir.path
                val tg = when (target) {
                    "storage", "sdcard", "~/storage" -> Environment.getExternalStorageDirectory().path
                    "~" -> filesDir.path
                    else -> target
                }
                val f = try { (if (tg.startsWith("/")) File(tg) else File(t.cwd, tg)).canonicalFile } catch (e: Exception) { null }
                if (f != null && f.isDirectory) t.cwd = f.path
                else append(t, "cd: pasta não encontrada" + if (!storageOk() && tg.startsWith("/storage")) " (ligue o armazenamento em ⚙ Configurações)\n" else "\n")
            }
            "servidor", "server" -> serverCmd(t, p)
            "pkg" -> pkgCmd(t, p)
            "termwin" -> when (p.getOrNull(1)) {
                "upgrade", "atualizar" -> upgrade(t)
                "versao", "version" -> append(t, "TermWin $VERSION\n")
                "sobre", "about" -> append(t, "TermWin $VERSION — criado por Weverson Isaque com Claude (Anthropic).\n")
                else -> append(t, "uso: termwin upgrade|versao|sobre\n")
            }
            "neofetch" ->
                if ("neofetch" !in pkgs) append(t, "neofetch: comando não encontrado. Use: pkg install neofetch\n")
                else append(t, LOGO.split("\n").joinToString("\n") { "\u0001$it" } + "\n")
            "cowsay" ->
                if ("cowsay" !in pkgs) append(t, "cowsay: comando não encontrado. Use: pkg install cowsay\n")
                else {
                    val msg = p.drop(1).joinToString(" ").ifEmpty { "muuu" }
                    val bar = "-".repeat(msg.length + 2)
                    append(t, " $bar\n< $msg >\n $bar\n$COW\n")
                }
            "arvore" ->
                if ("arvore" !in pkgs) append(t, "arvore: comando não encontrado. Use: pkg install arvore\n")
                else { val sb = StringBuilder(File(t.cwd).name + "/\n"); tree(File(t.cwd), "", 3, sb); append(t, sb.toString()) }
            "info" -> append(t, "Aparelho : ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid  : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nCPU      : ${Build.SUPPORTED_ABIS.joinToString()}\nTermWin  : $VERSION\n")
            "memoria" -> {
                val mi = ActivityManager.MemoryInfo()
                getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
                append(t, "RAM livre: ${mi.availMem shr 20} MiB de ${mi.totalMem shr 20} MiB\n")
            }
            "armazenamento" -> {
                fun sp(f: File) = StatFs(f.path).let { "${(it.availableBytes shr 20)} MiB livres de ${(it.totalBytes shr 20)} MiB" }
                append(t, "Interno do app: ${sp(filesDir)}\n")
                if (storageOk()) append(t, "Compartilhado : ${sp(Environment.getExternalStorageDirectory())}\n")
                else append(t, "Compartilhado : desligado (⚙ Configurações)\n")
            }
            "ip" -> {
                val l = try { NetworkInterface.getNetworkInterfaces().toList().flatMap { ni -> ni.inetAddresses.toList().filter { !it.isLoopbackAddress }.map { "${ni.name}: ${it.hostAddress}" } } } catch (e: Exception) { emptyList() }
                append(t, if (l.isEmpty()) "sem rede\n" else l.joinToString("\n") + "\n")
            }
            "bateria" -> append(t, "Bateria: ${getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%\n")
            "notificar" -> notifyDone("TermWin", p.drop(1).joinToString(" ").ifEmpty { "Olá!" }, true, true)
            "abrir" -> {
                val u = p.getOrNull(1)
                if (u == null) append(t, "uso: abrir <url>\n")
                else try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if ("://" in u) u else "https://$u"))) } catch (e: Exception) { append(t, "não foi possível abrir\n") }
            }
            "historico" -> append(t, history.mapIndexed { i, c -> "${i + 1}  $c" }.joinToString("\n") + "\n")
            else -> shell(t, line)
        }
        save()
    }

    private fun tree(f: File, pre: String, depth: Int, sb: StringBuilder) {
        if (depth == 0) return
        val items = (f.listFiles() ?: return).sortedBy { it.name }
        items.forEachIndexed { i, x ->
            val last = i == items.size - 1
            sb.append(pre).append(if (last) "└── " else "├── ").append(x.name).append(if (x.isDirectory) "/" else "").append('\n')
            if (x.isDirectory) tree(x, pre + if (last) "    " else "│   ", depth - 1, sb)
        }
    }

    private fun upgrade(t: TabData) =
        append(t, "Verificando pacotes internos...\n${pkgs.size} pacote(s) instalado(s) — tudo em dia.\nTermWin $VERSION\n")

    private fun pkgCmd(t: TabData, p: List<String>) {
        val n = p.getOrNull(2)
        when (p.getOrNull(1)) {
            "install", "instalar" -> when {
                n == null -> append(t, "uso: pkg install <pacote>\n")
                n == "termwin" -> append(t, "termwin é o núcleo e já está instalado.\n")
                CATALOG[n] == null -> append(t, "pacote '$n' não encontrado. Veja: pkg list\n")
                n in pkgs -> append(t, "$n já está instalado.\n")
                else -> { pkgs.add(n); append(t, "Instalando $n ...\n$n instalado ✔\n") }
            }
            "remove", "remover" -> when {
                n == null -> append(t, "uso: pkg remove <pacote>\n")
                pkgs.remove(n) -> append(t, "$n removido.\n")
                else -> append(t, "$n não está instalado.\n")
            }
            "list", "listar" -> {
                append(t, "Pacotes internos do TermWin:\n")
                CATALOG.forEach { (k, v) -> append(t, (if (k in pkgs) "[x] " else "[ ] ") + k.padEnd(10) + v + "\n") }
            }
            "upgrade", "atualizar" -> upgrade(t)
            else -> append(t, "uso: pkg install|remove|list|upgrade [pacote]\n")
        }
    }

    private fun shell(t: TabData, line: String) {
        if (t.proc != null) { append(t, "Já existe um comando rodando (use ^C)\n"); return }
        busy(1)
        thread {
            var ok = false
            var info = ""
            try {
                val pr = ProcessBuilder("sh", "-c", line).directory(File(t.cwd)).redirectErrorStream(true).start()
                t.proc = pr
                pr.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(1024)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        val s = String(buf, 0, n)
                        ui.post { append(t, s) }
                    }
                }
                val code = pr.waitFor()
                ok = code == 0
                info = if (ok) "terminou com sucesso" else "falhou (código $code)"
                ui.post { append(t, "[saiu: $code]\n") }
            } catch (e: Exception) {
                info = "erro: ${e.message}"
                ui.post { append(t, "erro: ${e.message}\n") }
            }
            t.proc = null
            ui.post { busy(-1); notifyDone(if (ok) "✔ Comando concluído" else "✖ Comando falhou", "$line — $info", ok) }
        }
    }

    // ---------- servidores ----------
    private fun freePort(): Int = (8080 until 9000).first { p -> servers.none { it.port == p } }
    private fun findSrv(n: String?) = servers.firstOrNull { it.name.equals(n, true) }
    private fun typeLabel(k: String) = TYPES.firstOrNull { it.first == k }?.second ?: k

    private fun serverCmd(t: TabData, p: List<String>) {
        when (p.getOrNull(1)) {
            "criar" -> {
                val n = p.getOrNull(2)
                if (n == null) { append(t, "uso: servidor criar <nome> [tipo] [porta] [extra...]\n"); return }
                if (findSrv(n) != null) { append(t, "já existe um servidor com esse nome\n"); return }
                val ty = ALIAS[p.getOrNull(3) ?: "web"]
                if (ty == null) { append(t, "tipo inválido (youtube, web, arquivos, json, comando, personalizado)\n"); return }
                val po = p.getOrNull(4)?.toIntOrNull() ?: freePort()
                val extra = p.drop(5).joinToString(" ")
                servers.add(Srv(n, ty, po, extra))
                append(t, "servidor '$n' criado ($ty, porta $po). Use: servidor iniciar $n\n")
            }
            "listar" -> {
                if (servers.isEmpty()) append(t, "nenhum servidor\n")
                servers.forEach { append(t, (if (it.running) "● " else "○ ") + "${it.name}  [${it.type}] :${it.port}\n") }
            }
            "iniciar" -> { val s = findSrv(p.getOrNull(2)); if (s == null) append(t, "não encontrado\n") else startServer(s) }
            "parar" -> { val s = findSrv(p.getOrNull(2)); if (s == null) append(t, "não encontrado\n") else stopServer(s) }
            "remover" -> { val s = findSrv(p.getOrNull(2)); if (s == null) append(t, "não encontrado\n") else { stopServer(s); servers.remove(s) } }
            "renomear" -> {
                val s = findSrv(p.getOrNull(2)); val novo = p.getOrNull(3)
                if (s == null || novo == null) append(t, "uso: servidor renomear <nome> <novo>\n") else { s.name = novo; append(t, "renomeado para $novo\n") }
            }
            else -> append(t, "uso: servidor listar|criar|iniciar|parar|remover|renomear\n")
        }
    }

    private fun startServer(s: Srv) {
        if (s.running) { sayCur("'${s.name}' já está rodando\n"); return }
        s.running = true
        busy(1)
        if (s.type == "cmd") {
            thread {
                var ok = false
                try {
                    val pr = ProcessBuilder("sh", "-c", s.cmd).directory(filesDir).redirectErrorStream(true).start()
                    s.proc = pr
                    ui.post { sayCur("[${s.name}] iniciado\n") }
                    pr.inputStream.bufferedReader().forEachLine { l -> ui.post { sayCur("[${s.name}] $l\n") } }
                    ok = pr.waitFor() == 0
                } catch (e: Exception) { ui.post { sayCur("erro: ${e.message}\n") } }
                s.running = false
                ui.post {
                    sayCur("[${s.name}] encerrado\n"); busy(-1); serversRefresh?.invoke()
                    notifyDone(if (ok) "✔ Servidor '${s.name}' terminou" else "✖ Servidor '${s.name}' falhou", s.cmd, ok)
                }
            }
        } else {
            thread {
                try {
                    val ss = ServerSocket(s.port, 50, InetAddress.getByName("127.0.0.1"))
                    s.sock = ss
                    ui.post { sayCur("Servidor '${s.name}' no ar: http://localhost:${s.port}\n"); serversRefresh?.invoke() }
                    while (s.running) {
                        val c = ss.accept()
                        thread { serve(c, s) }
                    }
                } catch (e: Exception) {
                    if (s.running) ui.post { sayCur("erro no servidor '${s.name}': ${e.message}\n") }
                }
                s.running = false
                ui.post { busy(-1); serversRefresh?.invoke() }
            }
        }
        serversRefresh?.invoke()
    }

    private fun stopServer(s: Srv) {
        s.running = false
        try { s.sock?.close() } catch (e: Exception) { }
        s.proc?.destroy()
        sayCur("'${s.name}' parado\n")
        serversRefresh?.invoke()
    }

    private fun send(o: OutputStream, ctype: String, data: ByteArray, status: String = "200 OK") {
        o.write("HTTP/1.1 $status\r\nContent-Type: $ctype\r\nContent-Length: ${data.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n".toByteArray())
        o.write(data)
    }

    private fun serve(c: Socket, s: Srv) {
        try {
            c.soTimeout = 3000
            val r = c.getInputStream().bufferedReader()
            val first = r.readLine() ?: return
            var l = r.readLine()
            while (l != null && l.isNotEmpty()) l = r.readLine()
            val path = Uri.decode((first.split(" ").getOrNull(1) ?: "/").substringBefore("?"))
            val o = c.getOutputStream()
            when (s.type) {
                "files" -> serveFiles(o, s, path)
                "json" -> send(o, "application/json; charset=utf-8", s.cmd.ifBlank { "{\"ok\":true}" }.toByteArray())
                "custom" -> send(o, "text/html; charset=utf-8", s.cmd.ifBlank { "<h1>${s.name.replace("<", "&lt;")}</h1>" }.toByteArray())
                else -> send(o, "text/html; charset=utf-8", page(s).toByteArray())
            }
            o.flush()
        } catch (e: Exception) {
        } finally {
            try { c.close() } catch (e: Exception) { }
        }
    }

    private fun serveFiles(o: OutputStream, s: Srv, path: String) {
        val base = File(s.cmd.ifBlank { filesDir.path }).canonicalFile
        val f = File(base, path).canonicalFile
        val inside = f.path == base.path || f.path.startsWith(base.path + File.separator)
        if (!inside || !f.exists()) { send(o, "text/plain; charset=utf-8", "404".toByteArray(), "404 Not Found"); return }
        if (f.isDirectory) {
            val idx = File(f, "index.html")
            if (idx.isFile) { serveFile(o, idx); return }
            val rows = (f.listFiles() ?: emptyArray()).sortedBy { it.name }.joinToString("") { x ->
                val href = Uri.encode(path.trimEnd('/') + "/" + x.name, "/")
                "<li><a href=\"$href\">${x.name.replace("<", "&lt;")}${if (x.isDirectory) "/" else ""}</a></li>"
            }
            val html = "<!doctype html><meta charset=utf-8><body style='font-family:sans-serif;background:#111;color:#fff'><h3>${path.replace("<", "&lt;")}</h3><ul>$rows</ul>"
            send(o, "text/html; charset=utf-8", html.toByteArray())
        } else serveFile(o, f)
    }

    private fun serveFile(o: OutputStream, f: File) {
        var mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
        if (mime.startsWith("text/")) mime += "; charset=utf-8"
        o.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${f.length()}\r\nConnection: close\r\n\r\n".toByteArray())
        f.inputStream().use { it.copyTo(o) }
    }

    private fun page(s: Srv): String {
        val n = s.name.replace("<", "&lt;")
        val css = "body{margin:0;font-family:sans-serif;background:#0f0f0f;color:#fff}header{padding:12px 16px;background:#212121;display:flex;gap:8px;align-items:center}header b{color:#f00}input{flex:1;padding:8px;border-radius:20px;border:1px solid #333;background:#121212;color:#fff}button{padding:8px 14px;border-radius:20px;border:0;background:#3ea6ff;color:#000}iframe{width:100%;height:70vh;border:0}"
        val head = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>$n</title><style>$css</style>"
        return if (s.type == "youtube")
            head + "<header><b>▶</b> <span>$n</span><input id=u placeholder='Cole um link do YouTube'><button onclick=\"var m=document.getElementById('u').value.match(/(?:v=|youtu\\.be\\/)([\\w-]{11})/);if(m)document.getElementById('f').src='https://www.youtube.com/embed/'+m[1]\">Assistir</button></header><iframe id=f allowfullscreen></iframe>"
        else
            head + "<header><b>●</b> $n</header><p style='padding:16px'>Servidor online ✔ (porta ${s.port})</p>"
    }

    // ---------- painéis de servidores ----------
    private fun smallBtn(text: String, red: Boolean = false, click: () -> Unit) =
        tv(text, 12f, if (red) 0xFFFF8A80.toInt() else Color.WHITE).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(0xFF3A3A3A.toInt(), dp(6))
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) }
        }

    private fun showServers() {
        val p = panel("Servidores", 0.75f)
        fun fill() {
            p.body.removeAllViews()
            if (servers.isEmpty()) p.body.addView(tv("Nenhum servidor ainda. Toque em ＋ Servidor.", 14f, 0xFFAAAAAA.toInt()).apply { setPadding(0, dp(12), 0, dp(12)) })
            servers.toList().forEach { s ->
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.background = rounded(0xFF2A2A2A.toInt(), dp(8))
                row.setPadding(dp(12), dp(8), dp(8), dp(8))
                row.addView(tv(if (s.running) "●" else "○", 15f, if (s.running) 0xFF16C60C.toInt() else 0xFF888888.toInt()).apply { setPadding(0, 0, dp(10), 0) })
                val info = LinearLayout(this)
                info.orientation = LinearLayout.VERTICAL
                info.addView(tv(s.name, 15f, Color.WHITE))
                info.addView(tv("[" + typeLabel(s.type) + "]" + (if (s.type != "cmd") "  :${s.port}" else ""), 11f, 0xFF9AA5B1.toInt()))
                row.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(smallBtn(if (s.running) "Parar" else "Iniciar") { if (s.running) stopServer(s) else startServer(s); fill() })
                if (s.running && s.type != "cmd") row.addView(smallBtn("Abrir") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:${s.port}"))) } catch (e: Exception) { toast("Sem navegador") }
                })
                row.addView(smallBtn("Renomear") { promptText("Novo nome", s.name) { n -> s.name = n; save(); fill() } })
                row.addView(smallBtn("Remover", true) { stopServer(s); servers.remove(s); save(); fill() })
                p.body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
            }
        }
        serversRefresh = { fill() }
        p.onClose = { serversRefresh = null }
        fill()
        p.button("Fechar", true) { p.close() }
    }

    private fun createServerDialog() {
        val p = panel("Criar servidor", 0.6f)
        val name = field("Nome (ex.: YouTube)")
        var sel = 0
        val combo = tv("${TYPES[0].second}   ▾", 14f, Color.WHITE).apply {
            background = rounded(0xFF2D2D2D.toInt(), dp(6), 0xFF454545.toInt())
            setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
        }
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.background = rounded(0xFF262626.toInt(), dp(6), 0xFF454545.toInt())
        list.visibility = View.GONE
        val port = field("Porta", freePort().toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val extra = field("", "", true)
        fun upd() {
            val k = TYPES[sel].first
            extra.visibility = if (k in EXTRA_HINT) View.VISIBLE else View.GONE
            extra.hint = EXTRA_HINT[k] ?: ""
            port.visibility = if (k == "cmd") View.GONE else View.VISIBLE
        }
        TYPES.forEachIndexed { i, (_, label) ->
            list.addView(tv(label, 14f, Color.WHITE).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { sel = i; combo.text = "$label   ▾"; list.visibility = View.GONE; upd() }
            })
        }
        combo.setOnClickListener { list.visibility = if (list.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        p.body.addView(name); p.body.addView(combo); p.body.addView(list, LinearLayout.LayoutParams(MATCH, WRAP))
        p.body.addView(port); p.body.addView(extra)
        upd()
        p.button("Cancelar") { p.close() }
        p.button("Criar", true) {
            val n = name.text.toString().trim()
            if (n.isEmpty() || findSrv(n) != null) { toast("Nome vazio ou já existe"); return@button }
            val ty = TYPES[sel].first
            val po = port.text.toString().toIntOrNull() ?: freePort()
            if (ty != "cmd" && servers.any { it.type != "cmd" && it.port == po }) { toast("Porta $po já usada por outro servidor"); return@button }
            val ex = extra.text.toString().trim()
            if (ty == "cmd" && ex.isEmpty()) { toast("Digite o comando"); return@button }
            servers.add(Srv(n, ty, po, ex))
            save()
            sayCur("servidor '$n' criado ($ty). Inicie em Servidores.\n")
            p.close()
        }
    }

    private fun promptText(title: String, init: String, ok: (String) -> Unit) {
        val p = panel(title, 0.45f)
        val et = field("Nome", init)
        p.body.addView(et)
        p.button("Cancelar") { p.close() }
        p.button("OK", true) { val s = et.text.toString().trim(); if (s.isNotEmpty()) ok(s); p.close() }
    }
}
