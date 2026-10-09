package com.termwin.console

import android.Manifest
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.tts.TextToSpeech
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

const val VERSION = "1.3"

class TabData(var name: String, var cwd: String, val log: StringBuilder = StringBuilder()) {
    @Volatile var proc: Process? = null
    var file: String = ""
    var prev: String = ""
    var tpl: String = ""
    var srvName: String = ""
}

class Srv(var name: String, var type: String, var port: Int, var cmd: String) {
    @Volatile var running = false
    var sock: ServerSocket? = null
    var proc: Process? = null
    var file: String = ""
}

fun ensureChannels(c: Context) {
    val nm = c.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("done", "Finished commands", NotificationManager.IMPORTANCE_DEFAULT))
    nm.createNotificationChannel(NotificationChannel("run", "Running", NotificationManager.IMPORTANCE_LOW))
}

/** Keeps TermWin alive in the background while a command/server is running. */
class TermService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        ensureChannels(this)
        val n = Notification.Builder(this, "run")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("TermWin")
            .setContentText("Running commands / servers…")
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
    private val known = mutableSetOf<String>()
    private val history = mutableListOf<String>()
    private val panels = mutableListOf<Panel>()
    private var cur = 0
    private var maximized = false
    private var bg = false
    private var busyN = 0
    private var notifId = 100
    private var hIdx = -1
    private val aliases = mutableMapOf<String, String>()
    private val envVars = mutableMapOf<String, String>()
    private var pt = false
    private var serversRefresh: (() -> Unit)? = null
    private var settingsSync: (() -> Unit)? = null
    private var loadRefresh: (() -> Unit)? = null

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

    private val prefs by lazy { getSharedPreferences("termwin", 0) }

    // ---------- language ----------
    private fun tr(en: String, p: String) = if (pt) p else en

    private val TYPE_KEYS = listOf("youtube", "web", "files", "json", "cmd", "custom")
    private val TYPE_PT = mapOf("arquivos" to "files", "api" to "json", "comando" to "cmd", "personalizado" to "custom")
    private fun cmdType(s: String): String? = if (s in TYPE_KEYS) s else if (pt) TYPE_PT[s] else null
    private val TPL_TYPES = listOf("tpl-youtube", "tpl-windows10", "tpl-ai")
    private fun anyType(s: String): String? = if (s in TYPE_KEYS || s in TPL_TYPES) s else TYPE_PT[s]

    private fun typeLabel(k: String) = when (k) {
        "youtube" -> "YouTube"
        "web" -> tr("Simple web", "Web simples")
        "files" -> tr("Files (folder)", "Arquivos (pasta)")
        "json" -> tr("JSON API", "API JSON")
        "cmd" -> tr("Command", "Comando")
        "custom" -> tr("Custom (HTML)", "Personalizado (HTML)")
        "tpl-youtube" -> tr("YouTube template", "Modelo YouTube")
        "tpl-windows10" -> tr("Windows 10 template", "Modelo Windows 10")
        "tpl-ai" -> tr("AI template", "Modelo IA")
        else -> k
    }

    private fun extraHint(k: String): String? = when (k) {
        "cmd" -> tr("Command (e.g. python -m http.server 9000)", "Comando (ex.: python -m http.server 9000)")
        "files" -> tr("Folder to serve (empty = TermWin folder)", "Pasta a servir (vazio = pasta do TermWin)")
        "json" -> tr("JSON response (e.g. {\"ok\":true})", "JSON da resposta (ex.: {\"ok\":true})")
        "custom" -> tr("Your HTML / page text — you decide", "Seu HTML / texto da página — você decide")
        else -> null
    }

    private val PT_CMD = mapOf(
        "ajuda" to "help", "limpar" to "clear", "memoria" to "memory", "armazenamento" to "storage",
        "bateria" to "battery", "notificar" to "notify", "abrir" to "open", "historico" to "history",
        "servidor" to "server", "arvore" to "tree", "dados" to "data", "erros" to "errors"
    )
    private val PT_SUB = mapOf(
        "instalar" to "install", "remover" to "remove", "listar" to "list", "atualizar" to "upgrade",
        "versao" to "version", "sobre" to "about", "criar" to "create", "iniciar" to "start",
        "parar" to "stop", "renomear" to "rename", "limpar" to "clear",
        "pesquisar" to "search", "mostrar" to "show", "desinstalar" to "uninstall"
    )
    private fun cmd(s: String): String { val l = s.lowercase(); return if (pt) (PT_CMD[l] ?: l) else l }
    private fun sub(s: String?): String? { val l = s?.lowercase() ?: return null; return if (pt) (PT_SUB[l] ?: l) else l }

    private val CATALOG = listOf("neofetch", "cowsay", "tree")
    private fun pkgDesc(k: String) = when (k) {
        "neofetch" -> tr("shows the Android logo and device info", "mostra o logo do Android e dados do aparelho")
        "cowsay" -> tr("a talking cow", "uma vaca que fala")
        else -> tr("lists folders as a tree", "lista pastas em árvore")
    }

    private fun help() = if (pt) """Comandos do TermWin:
  help | ajuda               esta ajuda
  clear | limpar             limpa a tela
  cd storage                 vai para /sdcard (cd Download, cd .., cd ~)
  info                       dados do aparelho
  memory | memoria           uso de RAM
  storage | armazenamento    espaço livre
  ip                         endereços de rede
  battery | bateria          nível da bateria
  notificar oi               envia uma notificação com o texto "oi"
  abrir youtube.com          abre no navegador
  historico                  comandos usados
  dados                      onde seus arquivos ficam salvos
  erros                      mostra os erros salvos (erros limpar)
  pkg listar                 lista pacotes (pkg instalar cowsay | pkg remover cowsay | pkg atualizar)
  termwin atualizar          também: termwin versao | termwin sobre
  neofetch                   logo do Android + dados do aparelho
  cowsay muuu                uma vaca que fala
  arvore                     lista pastas em árvore
  servidor listar
  servidor criar meusite web 8080
  servidor criar docs arquivos 8081 /sdcard
  servidor iniciar meusite   também: servidor parar | remover meusite
  servidor renomear meusite novo
  botão 🎨 Modelos           modelos de servidor (YouTube, Windows 10, IA): play, port, turn off
  botão 📝 Dados             edita o arquivo dados.wintext
  nano arquivo.txt           editor de texto (também: edit, vi)
  alias ll='ls -la'          cria atalho | unalias ll
  export NOME=valor          variável de ambiente | env | unset NOME
  cd -                       volta para a pasta anterior (cd ~/pasta também)
  wget URL | curl URL        baixa arquivo | mostra o conteúdo da página
  exit                       fecha a aba
  botões ⇥ ↑ ↓               completar nome, histórico anterior/próximo
  --- comandos estilo Termux (termux-* viram termwin-*) ---
  termwin-info               dados do aparelho
  termwin-setup-storage      pede acesso ao armazenamento
  termwin-battery-status     bateria em JSON
  termwin-toast oi           mostra um aviso na tela
  termwin-vibrate -d 500     vibra (ms)
  termwin-clipboard-get      lê a área de transferência
  termwin-clipboard-set oi   copia o texto
  termwin-open-url site.com  abre no navegador
  termwin-notification --content oi
  termwin-volume             volumes | termwin-volume music 5
  termwin-torch on|off       lanterna
  termwin-tts-speak oi       fala o texto
  termwin-wake-lock          mantém o aparelho acordado | termwin-wake-unlock
  termwin-download URL       baixa o arquivo para a pasta atual
  pkg: update | search x | show x | list-all | list-installed | uninstall x
Tipos de servidor: youtube, web, arquivos, json, comando, personalizado
Qualquer outro comando roda no shell do Android (ls, pwd, cat, ping...).
Toque e segure numa aba para renomear.
""" else """TermWin commands:
  help                       this help
  clear                      clear the screen
  cd storage                 go to /sdcard (cd Download, cd .., cd ~)
  info                       device info
  memory                     RAM usage
  storage                    free space
  ip                         network addresses
  battery                    battery level
  notify hello               send a notification with the text "hello"
  open youtube.com           open in the browser
  history                    commands used
  data                       where your files are saved
  errors                     show saved errors (errors clear)
  pkg list                   list packages (pkg install cowsay | pkg remove cowsay | pkg upgrade)
  termwin upgrade            also: termwin version | termwin about
  neofetch                   Android logo + device info
  cowsay moo                 a talking cow
  tree                       list folders as a tree
  server list
  server create mysite web 8080
  server create docs files 8081 /sdcard
  server start mysite        also: server stop | remove mysite
  server rename mysite newname
  🎨 Templates button        server templates (YouTube, Windows 10, AI): play, port, turn off
  📝 Data button             edit the dados.wintext file
  nano file.txt              text editor (also: edit, vi)
  alias ll='ls -la'          create a shortcut | unalias ll
  export NAME=value          environment variable | env | unset NAME
  cd -                       go back to the previous folder (cd ~/folder too)
  wget URL | curl URL        download a file | show the page content
  exit                       close the tab
  buttons ⇥ ↑ ↓              complete name, previous/next history
  --- Termux-style commands (termux-* became termwin-*) ---
  termwin-info               device info
  termwin-setup-storage      ask for storage access
  termwin-battery-status     battery as JSON
  termwin-toast hi           show a toast on screen
  termwin-vibrate -d 500     vibrate (ms)
  termwin-clipboard-get      read the clipboard
  termwin-clipboard-set hi   copy the text
  termwin-open-url site.com  open in the browser
  termwin-notification --content hi
  termwin-volume             volumes | termwin-volume music 5
  termwin-torch on|off       flashlight
  termwin-tts-speak hi       speak the text
  termwin-wake-lock          keep the device awake | termwin-wake-unlock
  termwin-download URL       download the file to the current folder
  pkg: update | search x | show x | list-all | list-installed | uninstall x
Server types: youtube, web, files, json, cmd, custom
Any other command runs in the Android shell (ls, pwd, cat, ping...).
Long-press a tab to rename it.
"""

    private val LOGO = listOf(
        "         -o          o-",
        "          +hydNNNNdyh+",
        "        +mMMMMMMMMMMMMm+",
        "      `dMMm:NMMMMMMN:mMMd`",
        "      hMMMMMMMMMMMMMMMMMMh",
        "  ..  yyyyyyyyyyyyyyyyyyyy  ..",
        ".mMMm`MMMMMMMMMMMMMMMMMMMM`mMMm.",
        ":MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM:",
        ":MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM:",
        ":MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM:",
        ":MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM:",
        "-MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM-",
        " +yy+ MMMMMMMMMMMMMMMMMMMM +yy+",
        "      mMMMMMMMMMMMMMMMMMMm",
        "      `/++MMMMh++hMMMM++/`",
        "          MMMMo  oMMMM",
        "          MMMMo  oMMMM",
        "          oNMm-  -mMNs"
    )

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

    /** Lines starting with \u0001 are green; an optional \u0002 ends the green part of the line. */
    private fun styled(s: String): CharSequence {
        if ('\u0001' !in s) return s
        val sb = SpannableStringBuilder()
        val parts = s.split("\n")
        parts.forEachIndexed { i, line ->
            if (line.startsWith("\u0001")) {
                val e = line.indexOf('\u0002')
                val green = if (e >= 0) line.substring(1, e) else line.substring(1)
                val rest = if (e >= 0) line.substring(e + 1) else ""
                val st = sb.length
                sb.append(green)
                sb.setSpan(ForegroundColorSpan(GREEN), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(rest)
            } else sb.append(line)
            if (i < parts.size - 1) sb.append('\n')
        }
        return sb
    }

    // ---------- Windows-style panel (used by every dialog) ----------
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
        // Elevation decides drawing order: the main window has 12dp, so panels need more
        // to always stay IN FRONT of it (this was the "servers behind the window" bug).
        ov.elevation = dp(40).toFloat()
        ov.outlineProvider = null
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

    // ---------- lifecycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        load()
        ensureChannels(this)
        root = FrameLayout(this)
        root.setBackgroundColor(0xFF0B0F14.toInt())
        buildHome()
        setContentView(root)
        handleIncoming(intent)
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        handleIncoming(i)
    }

    /** A .winv / .winser / .wintext tapped in a file manager opens here. */
    private fun handleIncoming(i: Intent?) {
        val it2 = i ?: return
        if (it2.action != Intent.ACTION_VIEW) return
        val uri = it2.data ?: return
        it2.data = null
        ui.post { restore(); importUri(uri) }
    }

    private fun buildHome() {
        val home = LinearLayout(this)
        home.orientation = LinearLayout.VERTICAL
        home.gravity = Gravity.CENTER
        home.addView(tv("TermWin", 34f, Color.WHITE).apply { typeface = Typeface.MONOSPACE })
        home.addView(tv(tr("terminal + servers + Windows 11 window", "terminal + servidores + janela Windows 11"), 14f, 0xFF8899AA.toInt()).apply { setPadding(0, dp(4), 0, dp(20)) })
        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.gravity = Gravity.CENTER
        val gap = { LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) } }
        btns.addView(pill(tr("⊞  Open window", "⊞  Abrir janela"), true) { openWindow() })
        btns.addView(pill(tr("⚙  Settings", "⚙  Configurações"), false) { showSettings() }, gap())
        btns.addView(pill(tr("ⓘ  Credits", "ⓘ  Créditos"), false) { showCredits() }, gap())
        btns.addView(pill(tr("📝  Data", "📝  Dados"), false) { showDados() }, gap())
        home.addView(btns, LinearLayout.LayoutParams(WRAP, WRAP))
        root.addView(home, FrameLayout.LayoutParams(MATCH, MATCH))

        taskbar = tv("⊞   ▣ TermWin", 14f, Color.WHITE)
        taskbar.setPadding(dp(20), dp(10), dp(20), dp(10))
        taskbar.background = rounded(0xEE2B2B2B.toInt(), dp(10), 0xFF444444.toInt())
        taskbar.visibility = View.GONE
        taskbar.setOnClickListener { restore() }
        root.addView(taskbar, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8) })
    }

    /** Rebuilds every screen (used when the language changes). */
    private fun rebuildUi(reopenSettings: Boolean) {
        val wasOpen = win != null
        panels.clear()
        serversRefresh = null; settingsSync = null; loadRefresh = null
        root.removeAllViews()
        win = null; tabBar = null; body = null; outView = null; scroll = null
        maximized = false
        buildHome()
        if (wasOpen) openWindow()
        if (reopenSettings) showSettings()
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

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 77 || res != RESULT_OK) return
        val uri = data?.data ?: return
        importUri(uri)
    }

    private fun importUri(uri: Uri) {
        try {
            val name = try {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && ix >= 0) c.getString(ix) else null
                }
            } catch (e: Exception) { null } ?: uri.lastPathSegment ?: "file"
            val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }.trimStart('\uFEFF')
            if (importAny(text, name, true)) { save(); refreshTabs(); showTab(); loadRefresh?.invoke() }
        } catch (e: Exception) {
            val m = tr("Could not read the file: ${e.message}", "Não foi possível ler o arquivo: ${e.message}")
            sayCur(m + "\n"); logError("load", m)
        }
    }

    // ---------- data folder (.winv / .winser) ----------
    // Layout:  <app folder>/logs/errors.log
    //          <app folder>/files/terminal/*.winv , files/servidores/*.winser , files/dados.wintext
    private fun dataDir(): File = (getExternalFilesDir(null) ?: filesDir).apply { mkdirs() }
    private fun logsDir() = File(dataDir().parentFile ?: dataDir(), "logs").apply { mkdirs() }
    private fun termDir() = File(dataDir(), "terminal").apply { mkdirs() }
    private fun serDir() = File(dataDir(), "servidores").apply { mkdirs() }
    private fun safe(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "file" }

    private fun logError(src: String, msg: String) {
        try {
            val f = File(logsDir(), "errors.log")
            if (f.exists() && f.length() > 200_000) f.writeText(f.readText().takeLast(100_000))
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            f.appendText("[$ts] [$src] $msg\n")
        } catch (e: Exception) { }
    }

    private fun writeWinv(t: TabData) {
        try {
            val fn = safe(t.name) + ".winv"
            if (t.file.isNotEmpty() && t.file != fn) File(termDir(), t.file).delete()
            t.file = fn
            known.add("t:$fn")
            val j = JSONObject().put("format", "winv").put("version", 1)
                .put("name", t.name).put("cwd", t.cwd).put("log", t.log.toString())
            File(termDir(), fn).writeText(j.toString(2))
        } catch (e: Exception) { }
    }

    private fun writeWinser(s: Srv) {
        try {
            val fn = safe(s.name) + ".winser"
            if (s.file.isNotEmpty() && s.file != fn) File(serDir(), s.file).delete()
            s.file = fn
            known.add("s:$fn")
            val j = JSONObject().put("format", "winser").put("version", 1)
                .put("name", s.name).put("type", s.type).put("port", s.port).put("extra", s.cmd)
            File(serDir(), fn).writeText(j.toString(2))
        } catch (e: Exception) { }
    }

    private fun uniqueTabName(base: String): String {
        val b = base.ifBlank { "Terminal" }
        var n = b; var i = 2
        while (tabs.any { it.name.equals(n, true) }) { n = "$b $i"; i++ }
        return n
    }

    private fun uniqueSrvName(base: String): String {
        val b = base.ifBlank { "server" }
        var n = b; var i = 2
        while (servers.any { it.name.equals(n, true) }) { n = "$b $i"; i++ }
        return n
    }

    private fun nextTabName(): String {
        var i = tabs.size + 1
        while (tabs.any { it.name.equals("Terminal $i", true) } || File(termDir(), "Terminal $i.winv").exists()) i++
        return "Terminal $i"
    }

    private fun importWinv(j: JSONObject, fname: String): TabData {
        val name = uniqueTabName(j.optString("name").ifEmpty { fname.substringBeforeLast('.') })
        val cwd = j.optString("cwd").let { if (it.isNotEmpty() && File(it).isDirectory) it else filesDir.path }
        val t = TabData(name, cwd, StringBuilder(j.optString("log")))
        tabs.add(t)
        return t
    }

    private fun importWinser(j: JSONObject, fname: String): Srv {
        val name = uniqueSrvName(j.optString("name").ifEmpty { fname.substringBeforeLast('.') })
        val ty = anyType(j.optString("type", "web").lowercase()) ?: "web"
        var port = j.optInt("port", 0)
        if (port <= 0 || (ty != "cmd" && servers.any { it.type != "cmd" && it.port == port })) port = freePort()
        val s = Srv(name, ty, port, j.optString("extra"))
        servers.add(s)
        return s
    }

    /** Loads a .winv / .winser given as text. Returns true on success. */
    private fun importAny(text: String, fname: String, select: Boolean): Boolean {
        if (text.trimStart().startsWith("WINTEXT")) {
            val n = scanFolders()
            sayCur(tr("$fname: $n new file(s) loaded\n", "$fname: $n arquivo(s) novo(s) carregado(s)\n")); return true
        }
        val j = try { JSONObject(text) } catch (e: Exception) { null }
        if (j == null) {
            val m = tr("Invalid file: $fname", "Arquivo inválido: $fname")
            sayCur(m + "\n"); logError("load", m); return false
        }
        val kind = j.optString("format").ifEmpty { fname.substringAfterLast('.', "").lowercase() }
        return when (kind) {
            "winv" -> {
                val t = importWinv(j, fname)
                if (select) cur = tabs.indexOf(t)
                sayCur(tr("terminal '${t.name}' loaded\n", "terminal '${t.name}' carregado\n")); true
            }
            "winser" -> {
                val s = importWinser(j, fname)
                sayCur(tr("server '${s.name}' loaded\n", "servidor '${s.name}' carregado\n")); true
            }
            else -> {
                val m = tr("Unknown file format: $fname (use .winv, .winser or .wintext)", "Formato desconhecido: $fname (use .winv, .winser ou .wintext)")
                sayCur(m + "\n"); logError("load", m); false
            }
        }
    }

    /** Loads files that appeared in the folders and were never seen before. */
    private fun scanFolders(): Int {
        var n = 0
        termDir().listFiles()?.filter { it.isFile && it.extension.equals("winv", true) }?.sortedBy { it.name }?.forEach { f ->
            if (!known.add("t:" + f.name)) return@forEach
            try { importWinv(JSONObject(f.readText()), f.name).file = f.name; n++ } catch (e: Exception) { logError("load", "${f.name}: ${e.message}") }
        }
        serDir().listFiles()?.filter { it.isFile && it.extension.equals("winser", true) }?.sortedBy { it.name }?.forEach { f ->
            if (!known.add("s:" + f.name)) return@forEach
            try { importWinser(JSONObject(f.readText()), f.name).file = f.name; n++ } catch (e: Exception) { logError("load", "${f.name}: ${e.message}") }
        }
        return n
    }

    // ---------- persistence ----------
    /** dados.wintext: TermWin's own text index of every file in terminal/ ([Normal]) and servidores/ ([Servers]). */
    private fun writeIndex() {
        try {
            val sb = StringBuilder()
            sb.append("WINTEXT 1\n")
            sb.append("# TermWin - dados.wintext (generated automatically)\n\n")
            sb.append("[Normal]\n")
            val tf = termDir().listFiles()?.filter { it.isFile && it.extension.equals("winv", true) }?.sortedBy { it.name } ?: emptyList()
            if (tf.isEmpty()) sb.append("(none)\n") else tf.forEach { sb.append("terminal/").append(it.name).append('\n') }
            sb.append("\n[Servers]\n")
            val sf = serDir().listFiles()?.filter { it.isFile && it.extension.equals("winser", true) }?.sortedBy { it.name } ?: emptyList()
            if (sf.isEmpty()) sb.append("(none)\n") else sf.forEach { sb.append("servidores/").append(it.name).append('\n') }
            val f = File(dataDir(), "dados.wintext")
            val old = try { if (f.exists()) f.readText() else "" } catch (e: Exception) { "" }
            val ni = old.indexOf("[Notes]")
            sb.append("\n")
            sb.append(if (ni >= 0) old.substring(ni).trimEnd() + "\n" else "[Notes]\n" + tr("(write anything here - this part is kept)", "(escreva o que quiser aqui - esta parte fica salva)") + "\n")
            f.writeText(sb.toString())
        } catch (e: Exception) { }
    }

    private fun save() {
        tabs.forEach { writeWinv(it) }
        servers.forEach { writeWinser(it) }
        writeIndex()
        val o = JSONObject()
        o.put("cur", cur)
        val ta = JSONArray()
        tabs.forEach { ta.put(JSONObject().put("n", it.name).put("c", it.cwd).put("l", it.log.toString().takeLast(20000)).put("f", it.file).put("tp", it.tpl).put("sn", it.srvName)) }
        o.put("tabs", ta)
        val sa = JSONArray()
        servers.forEach { sa.put(JSONObject().put("n", it.name).put("t", it.type).put("p", it.port).put("c", it.cmd).put("f", it.file)) }
        o.put("servers", sa)
        o.put("pk", JSONArray(pkgs.toList()))
        o.put("alias", JSONObject(aliases as Map<*, *>))
        o.put("env", JSONObject(envVars as Map<*, *>))
        o.put("known", JSONArray(known.toList()))
        prefs.edit().putString("d", o.toString()).apply()
    }

    private fun load() {
        pt = prefs.getBoolean("pt", false)
        try { termDir(); serDir(); logsDir() } catch (e: Exception) { }
        try {
            val o = JSONObject(prefs.getString("d", "{}")!!)
            val ta = o.optJSONArray("tabs")
            if (ta != null) for (i in 0 until ta.length()) {
                val j = ta.getJSONObject(i)
                tabs.add(TabData(j.getString("n"), j.getString("c"), StringBuilder(j.optString("l"))).also { it.file = j.optString("f"); it.tpl = j.optString("tp"); it.srvName = j.optString("sn") })
            }
            val sa = o.optJSONArray("servers")
            if (sa != null) for (i in 0 until sa.length()) {
                val j = sa.getJSONObject(i)
                servers.add(Srv(j.getString("n"), j.getString("t"), j.getInt("p"), j.optString("c")).also { it.file = j.optString("f") })
            }
            val pk = o.optJSONArray("pk")
            if (pk != null) { pkgs.clear(); for (i in 0 until pk.length()) pkgs.add(pk.getString(i).let { if (it == "arvore") "tree" else it }) }
            o.optJSONObject("alias")?.let { j -> j.keys().forEach { k -> aliases[k] = j.getString(k) } }
            o.optJSONObject("env")?.let { j -> j.keys().forEach { k -> envVars[k] = j.getString(k) } }
            val kn = o.optJSONArray("known")
            if (kn != null) for (i in 0 until kn.length()) known.add(kn.getString(i))
            cur = o.optInt("cur", 0)
        } catch (e: Exception) { }
        try { scanFolders() } catch (e: Exception) { }
        writeIndex()
        if (tabs.isEmpty()) tabs.add(newTabData("Terminal 1"))
        cur = cur.coerceIn(0, tabs.size - 1)
    }

    private fun newTabData(name: String) =
        TabData(name, filesDir.path, StringBuilder(tr("TermWin $VERSION — type 'help' to see the commands.\n", "TermWin $VERSION — digite 'ajuda' para ver os comandos.\n")))

    // ---------- permissions and notifications ----------
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
        toast(tr("To turn off, remove the permission in Android settings", "Para desligar, remova a permissão nas configurações do Android"))
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e: Exception) { }
    }

    private fun notifyDone(title: String, text: String, ok: Boolean, force: Boolean = false): Boolean {
        if (!bg && !force) return false
        if (!notifOk()) return false
        return try {
            ensureChannels(this)
            val pi = PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(this, "done")
                .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(title).setContentText(text).setContentIntent(pi).setAutoCancel(true).build()
            getSystemService(NotificationManager::class.java).notify(notifId++, n)
            true
        } catch (e: Exception) { false }
    }

    private fun notifyCmd(t: TabData, text: String) {
        val msg = text.ifEmpty { tr("Hello!", "Olá!") }
        if (!notifOk()) {
            err(t, tr("Notifications are off. Allow them in the screen that opens, then try again.", "As notificações estão desligadas. Permita na tela que abrir e tente de novo."))
            askNotif()
            return
        }
        if (notifyDone("TermWin", msg, true, true)) append(t, tr("notification sent: $msg\n", "notificação enviada: $msg\n"))
        else err(t, tr("Could not send the notification", "Não foi possível enviar a notificação"))
    }

    private fun busy(d: Int) {
        val before = busyN
        busyN = (busyN + d).coerceAtLeast(0)
        try {
            if (before == 0 && busyN > 0) startForegroundService(Intent(this, TermService::class.java))
            else if (before > 0 && busyN == 0) stopService(Intent(this, TermService::class.java))
        } catch (e: Exception) { }
    }

    // ---------- Settings / Credits ----------
    private fun showSettings() {
        val p = panel(tr("Settings", "Configurações"), 0.55f)
        var syncing = false
        fun row(title: String, sub: String, onToggle: (Boolean) -> Unit): Switch {
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
            sw.setOnCheckedChangeListener { _, checked -> if (!syncing) onToggle(checked) }
            r.addView(sw)
            p.body.addView(r, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            return sw
        }
        val sSto = row(tr("Storage", "Armazenamento"), tr("Access /sdcard in the terminal (cd storage)", "Acessar /sdcard no terminal (cd storage)")) { if (!storageOk()) askStorage() else openAppSettings() }
        val sNot = row(tr("Notifications", "Notificações"), tr("Tell me when a command finishes (success or error) while the app is in the background", "Avisar quando um comando terminar (deu certo ou errado) com o app fora da tela")) { if (!notifOk()) askNotif() else openAppSettings() }
        val sLang = row("Português", tr("Show everything in Portuguese (screens and commands)", "Mostrar tudo em português (telas e comandos)")) { on ->
            pt = on
            prefs.edit().putBoolean("pt", on).apply()
            rebuildUi(true)
        }
        syncing = true; sLang.isChecked = pt; syncing = false
        val sync = { syncing = true; sSto.isChecked = storageOk(); sNot.isChecked = notifOk(); syncing = false }
        settingsSync = sync
        p.onClose = { settingsSync = null }
        sync()
        p.button(tr("Test notification", "Testar notificação")) {
            if (!notifyDone("✔ TermWin", tr("Test notification working", "Notificação de teste funcionando"), true, true)) toast(tr("Turn on notifications first", "Ligue as notificações primeiro"))
        }
        p.button(tr("Close", "Fechar"), true) { p.close() }
    }

    private fun showCredits() {
        val p = panel(tr("Credits", "Créditos"), 0.6f)
        fun line(a: String, b: String) {
            p.body.addView(tv(a, 12f, 0xFF9AA5B1.toInt()).apply { setPadding(0, dp(10), 0, 0) })
            p.body.addView(tv(b, 17f, Color.WHITE))
        }
        line(tr("Creator", "Criador"), "Weverson Isaque  (GABEDEVELOPER)")
        line(tr("Code written with", "Código escrito com"), tr("Claude — AI made by Anthropic", "Claude — IA criada pela Anthropic"))
        p.body.addView(tv(tr("What TermWin does", "O que o TermWin faz"), 12f, 0xFF9AA5B1.toInt()).apply { setPadding(0, dp(14), 0, dp(4)) })
        p.body.addView(tv(tr(
            "• Windows 11 style window: move, minimize, maximize\n" +
            "• Several terminal tabs with saved history (.winv files)\n" +
            "• Android commands + built-in ones (info, memory, ip, battery…)\n" +
            "• pkg / termwin upgrade and packages: neofetch, cowsay, tree\n" +
            "• Local servers (.winser files): YouTube, Web, Files, JSON API, Command and Custom\n" +
            "• Templates: YouTube, Windows 10 and AI (ai//question//app)\n" +
            "• Runs in the background and notifies you when done\n" +
            "• Load .winv / .winser files from anywhere\n" +
            "• One-tap storage access",
            "• Janela estilo Windows 11: mover, minimizar, maximizar\n" +
            "• Várias abas de terminal com histórico salvo (arquivos .winv)\n" +
            "• Comandos do Android + comandos próprios (info, memoria, ip, bateria…)\n" +
            "• pkg / termwin atualizar e pacotes: neofetch, cowsay, arvore\n" +
            "• Servidores locais (arquivos .winser): YouTube, Web, Arquivos, API JSON, Comando e Personalizado\n" +
            "• Roda em segundo plano e avisa por notificação quando termina\n" +
            "• Carrega arquivos .winv / .winser de qualquer lugar\n" +
            "• Acesso ao armazenamento com um toque"), 13f, Color.WHITE))
        p.body.addView(tv("TermWin $VERSION", 11f, 0xFF667788.toInt()).apply { setPadding(0, dp(12), 0, 0) })
        p.button(tr("Close", "Fechar"), true) { p.close() }
    }

    // ---------- Load files panel ----------
    private fun showLoad() {
        val p = panel(tr("Load file", "Carregar arquivo"), 0.7f)
        fun fill() {
            p.body.removeAllViews()
            p.body.addView(tv(tr("Folder: ", "Pasta: ") + dataDir().path, 11f, 0xFF9AA5B1.toInt()))
            p.body.addView(tv(tr("All saved files. Files you put in terminal/ (.winv) or servidores/ (.winser) load automatically; the list is also saved in dados.wintext.", "Todos os arquivos salvos. Arquivos colocados em terminal/ (.winv) ou servidores/ (.winser) carregam sozinhos; a lista também fica em dados.wintext."), 12f, Color.WHITE).apply { setPadding(0, dp(8), 0, dp(4)) })
            fun title(s: String) = p.body.addView(tv(s, 15f, ACCENT).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(12), 0, dp(2)) })
            fun empty() = p.body.addView(tv(tr("(empty)", "(vazio)"), 13f, 0xFFAAAAAA.toInt()).apply { setPadding(dp(4), dp(4), 0, dp(4)) })
            fun entry(label: String, loaded: Boolean, load: () -> Unit) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.background = rounded(0xFF2A2A2A.toInt(), dp(8))
                row.setPadding(dp(12), dp(8), dp(8), dp(8))
                row.addView(tv(label, 14f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP, 1f))
                if (loaded) row.addView(tv(tr("loaded ✔", "carregado ✔"), 12f, GREEN))
                else row.addView(smallBtn(tr("Load", "Carregar")) { load() })
                p.body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
            }
            fun peek(f: File): JSONObject? = try { JSONObject(f.readText()) } catch (e: Exception) { null }
            title("Normal")
            val tf = termDir().listFiles()?.filter { it.isFile && it.extension.equals("winv", true) }?.sortedBy { it.name } ?: emptyList()
            if (tf.isEmpty()) empty()
            tf.forEach { f ->
                val nm = peek(f)?.optString("name")?.ifEmpty { null } ?: f.nameWithoutExtension
                entry("terminal/${f.name}", tabs.any { it.name.equals(nm, true) }) {
                    importAny(f.readText(), f.name, true); save(); refreshTabs(); showTab(); fill()
                }
            }
            title("Servers")
            val sf = serDir().listFiles()?.filter { it.isFile && it.extension.equals("winser", true) }?.sortedBy { it.name } ?: emptyList()
            if (sf.isEmpty()) empty()
            sf.forEach { f ->
                val nm = peek(f)?.optString("name")?.ifEmpty { null } ?: f.nameWithoutExtension
                entry("servidores/${f.name}", servers.any { it.name.equals(nm, true) }) {
                    importAny(f.readText(), f.name, false); save(); fill()
                }
            }
        }
        loadRefresh = { fill() }
        p.onClose = { loadRefresh = null }
        fill()
        p.button(tr("Rescan folders", "Reler pastas")) {
            val n = scanFolders()
            save(); refreshTabs(); showTab(); fill()
            toast(tr("$n new file(s) loaded", "$n arquivo(s) novo(s) carregado(s)"))
        }
        p.button(tr("Choose file…", "Escolher arquivo…")) { pickFile() }
        p.button(tr("Close", "Fechar"), true) { p.close() }
    }

    @Suppress("DEPRECATION")
    private fun pickFile() {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(Intent.createChooser(i, null), 77) }
        catch (e: Exception) { toast(tr("No file picker found", "Nenhum seletor de arquivos encontrado")) }
    }

    // ---------- Windows 11 window ----------
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
        rb(tr("Servers", "Servidores"), 88) { showServers() }
        rb(tr("＋ Server", "＋ Servidor"), 88, true) { createServerDialog() }
        rb(tr("📂 Load", "📂 Carregar"), 92, true) { showLoad() }
        rb(tr("🎨 Templates", "🎨 Modelos"), 104, true) { showTemplates() }
        rb("⚙", 40) { showSettings() }
        rb("ⓘ", 40) { showCredits() }
        rb("📝", 40) { showDados() }
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

    // ---------- tabs ----------
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
            item.setOnLongClickListener {
                promptText(tr("Rename tab", "Renomear aba"), t.name) { n ->
                    if (tabs.any { it !== t && it.name.equals(n, true) }) toast(tr("A tab with that name already exists", "Já existe uma aba com esse nome"))
                    else { t.name = n; refreshTabs(); save() }
                }
                true
            }
            bar.addView(item, LinearLayout.LayoutParams(WRAP, dp(34)))
        }
    }

    private fun addTab() {
        tabs.add(newTabData(nextTabName()))
        cur = tabs.size - 1
        refreshTabs(); showTab(); save()
    }

    private fun closeTab(i: Int) {
        if (tabs.size == 1) { toast(tr("Keep at least one tab", "Mantenha pelo menos uma aba")); return }
        tabs[i].proc?.destroy()
        writeWinv(tabs[i]) // the file stays in the terminal folder
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
        et.hint = tr("command", "comando")
        et.setHintTextColor(0xFF555555.toInt())
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        et.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        et.setOnEditorActionListener { _, _, _ -> submit(et); true }
        inp.addView(et, LinearLayout.LayoutParams(0, dp(44), 1f))
        inp.addView(capBtn("⇥", false, 44) { tabComplete(et) }.apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) })
        inp.addView(capBtn("↑", false, 44) { histMove(et, -1) }.apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) })
        inp.addView(capBtn("↓", false, 44) { histMove(et, 1) }.apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) })
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

    /** Shows an error in the terminal AND saves it to errors.log (and, through the log, to the .winv). */
    private fun err(t: TabData, msg: String) { append(t, msg + "\n"); logError(t.name, msg) }
    private fun sayErr(msg: String) { sayCur(msg + "\n"); logError("server", msg) }

    // ---------- commands ----------
    private fun submit(et: EditText) {
        val t = tabs[cur]
        val c = et.text.toString().trim()
        et.setText("")
        hIdx = -1
        append(t, "${short(t.cwd)} \$ $c\n")
        if (c.isNotEmpty()) history.add(c)
        exec(t, c)
    }

    private fun neofetch(t: TabData) {
        val mi = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        val used = (mi.totalMem - mi.availMem) shr 20
        val up = SystemClock.elapsedRealtime() / 1000
        val user = "termwin@android"
        val info = listOf(
            user,
            "-".repeat(user.length),
            "OS: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Host: ${Build.MANUFACTURER} ${Build.MODEL}",
            "Kernel: ${System.getProperty("os.version") ?: "?"}",
            "Uptime: ${up / 86400}d ${(up % 86400) / 3600}h ${(up % 3600) / 60}m",
            "Packages: ${pkgs.size}",
            "Shell: sh",
            "Terminal: TermWin $VERSION",
            "CPU: ${Build.HARDWARE} (${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"})",
            "Memory: ${used}MiB / ${mi.totalMem shr 20}MiB"
        )
        val sb = StringBuilder()
        LOGO.forEachIndexed { i, l ->
            sb.append("\u0001").append(l.padEnd(34)).append("\u0002")
            if (i < info.size) sb.append(info[i])
            sb.append('\n')
        }
        append(t, sb.toString())
    }

    private fun expandAlias(l: String): String {
        val tl = l.trimStart()
        val first = tl.substringBefore(' ')
        val a = aliases[first] ?: return l
        return a + tl.removePrefix(first)
    }

    private fun exec(t: TabData, rawLine: String) {
        if (rawLine.isEmpty()) return
        val line = expandAlias(rawLine)
        if (t.tpl.isNotEmpty()) { tplExec(t, line); save(); return }
        val p = line.split(" ").filter { it.isNotEmpty() }
        when (cmd(p[0])) {
            "help" -> append(t, help())
            "clear" -> { t.log.setLength(0); outView?.text = "" }
            "cd" -> {
                val target = p.getOrNull(1) ?: filesDir.path
                val tg = when (target) {
                    "storage", "sdcard", "~/storage" -> Environment.getExternalStorageDirectory().path
                    "~" -> filesDir.path
                    "-" -> t.prev.ifEmpty { t.cwd }
                    else -> if (target.startsWith("~/")) filesDir.path + target.drop(1) else target
                }
                val f = try { (if (tg.startsWith("/")) File(tg) else File(t.cwd, tg)).canonicalFile } catch (e: Exception) { null }
                if (f != null && f.isDirectory) { t.prev = t.cwd; t.cwd = f.path }
                else err(t, "cd: $target: " + tr("No such file or directory", "Arquivo ou diretório inexistente") +
                    if (!storageOk() && tg.startsWith("/storage")) tr(" (turn on storage in ⚙ Settings)", " (ligue o armazenamento em ⚙ Configurações)") else "")
            }
            "exit", "logout" -> { val i = tabs.indexOf(t); if (tabs.size <= 1) closeWindow() else closeTab(i) }
            "nano", "edit", "vi" -> editFile(t, p.getOrNull(1))
            "alias" -> {
                val rest = line.trim().removePrefix("alias").trim()
                if (rest.isEmpty()) append(t, aliases.entries.joinToString("") { "alias ${it.key}='${it.value}'\n" })
                else if ('=' in rest) {
                    val k = rest.substringBefore('=').trim()
                    val v = rest.substringAfter('=').trim().removeSurrounding("'").removeSurrounding("\"")
                    if (k.isEmpty()) err(t, tr("usage: alias ll='ls -la'", "uso: alias ll='ls -la'")) else { aliases[k] = v }
                } else append(t, aliases[rest]?.let { "alias $rest='$it'\n" } ?: "")
            }
            "unalias" -> { val k = p.getOrNull(1) ?: ""; if (aliases.remove(k) == null) err(t, "unalias: $k: " + tr("not found", "não encontrado")) }
            "export" -> {
                val rest = line.trim().removePrefix("export").trim()
                if (rest.isEmpty()) append(t, envVars.entries.joinToString("") { "export ${it.key}=${it.value}\n" })
                else if ('=' in rest) {
                    val k = rest.substringBefore('=').trim()
                    val v = rest.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
                    if (k.isEmpty()) err(t, tr("usage: export NAME=value", "uso: export NOME=valor")) else { envVars[k] = v }
                } else err(t, tr("usage: export NAME=value", "uso: export NOME=valor"))
            }
            "unset" -> p.drop(1).forEach { envVars.remove(it) }
            "env" -> append(t, (mapOf("HOME" to filesDir.path, "TMPDIR" to cacheDir.path) + envVars).entries.joinToString("") { "${it.key}=${it.value}\n" })
            "wget" -> apiCmd(t, listOf("termwin-download") + p.drop(1))
            "curl" -> {
                val u = p.drop(1).firstOrNull { it.startsWith("http") }
                if (u == null) err(t, tr("usage: curl https://site.com", "uso: curl https://site.com"))
                else {
                    busy(1)
                    thread {
                        try {
                            val bodyText = java.net.URL(u).readText().take(20000)
                            ui.post { append(t, bodyText + "\n") }
                        } catch (e: Exception) { ui.post { err(t, "curl: ${e.message}") } }
                        ui.post { busy(-1) }
                    }
                }
            }
            "server" -> serverCmd(t, p)
            "pkg" -> pkgCmd(t, p)
            "termwin" -> when (sub(p.getOrNull(1))) {
                "upgrade" -> upgrade(t)
                "version" -> append(t, "TermWin $VERSION\n")
                "about" -> append(t, tr("TermWin $VERSION — created by Weverson Isaque with Claude (Anthropic).\n", "TermWin $VERSION — criado por Weverson Isaque com Claude (Anthropic).\n"))
                else -> err(t, tr("usage: termwin upgrade | version | about", "uso: termwin atualizar | versao | sobre"))
            }
            "neofetch" ->
                if ("neofetch" !in pkgs) err(t, tr("neofetch: command not found. Use: pkg install neofetch", "neofetch: comando não encontrado. Use: pkg instalar neofetch"))
                else neofetch(t)
            "cowsay" ->
                if ("cowsay" !in pkgs) err(t, tr("cowsay: command not found. Use: pkg install cowsay", "cowsay: comando não encontrado. Use: pkg instalar cowsay"))
                else {
                    val msg = p.drop(1).joinToString(" ").ifEmpty { tr("moo", "muuu") }
                    val bar = "-".repeat(msg.length + 2)
                    append(t, " $bar\n< $msg >\n $bar\n$COW\n")
                }
            "tree" ->
                if ("tree" !in pkgs) err(t, tr("tree: command not found. Use: pkg install tree", "arvore: comando não encontrado. Use: pkg instalar arvore"))
                else { val sb = StringBuilder(File(t.cwd).name + "/\n"); tree(File(t.cwd), "", 3, sb); append(t, sb.toString()) }
            "info" -> append(t, "Device   : ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid  : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nCPU      : ${Build.SUPPORTED_ABIS.joinToString()}\nTermWin  : $VERSION\n")
            "memory" -> {
                val mi = ActivityManager.MemoryInfo()
                getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
                append(t, tr("Free RAM: ${mi.availMem shr 20} MiB of ${mi.totalMem shr 20} MiB\n", "RAM livre: ${mi.availMem shr 20} MiB de ${mi.totalMem shr 20} MiB\n"))
            }
            "storage" -> {
                fun sp(f: File) = StatFs(f.path).let { tr("${it.availableBytes shr 20} MiB free of ${it.totalBytes shr 20} MiB", "${it.availableBytes shr 20} MiB livres de ${it.totalBytes shr 20} MiB") }
                append(t, tr("App internal: ", "Interno do app: ") + sp(filesDir) + "\n")
                if (storageOk()) append(t, tr("Shared      : ", "Compartilhado : ") + sp(Environment.getExternalStorageDirectory()) + "\n")
                else append(t, tr("Shared      : off (⚙ Settings)\n", "Compartilhado : desligado (⚙ Configurações)\n"))
            }
            "ip" -> {
                val l = try { NetworkInterface.getNetworkInterfaces().toList().flatMap { ni -> ni.inetAddresses.toList().filter { !it.isLoopbackAddress }.map { "${ni.name}: ${it.hostAddress}" } } } catch (e: Exception) { emptyList() }
                append(t, if (l.isEmpty()) tr("no network\n", "sem rede\n") else l.joinToString("\n") + "\n")
            }
            "battery" -> append(t, tr("Battery: ", "Bateria: ") + "${getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%\n")
            "notify" -> notifyCmd(t, p.drop(1).joinToString(" "))
            "open" -> {
                val u = p.getOrNull(1)
                if (u == null) err(t, tr("usage: open youtube.com", "uso: abrir youtube.com"))
                else try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if ("://" in u) u else "https://$u"))) } catch (e: Exception) { err(t, tr("could not open", "não foi possível abrir")) }
            }
            "history" -> append(t, history.mapIndexed { i, c -> "${i + 1}  $c" }.joinToString("\n") + "\n")
            "data" -> append(t, tr(
                "Data folder: ${dataDir().path}\n  terminal/       one .winv file per terminal\n  servidores/     one .winser file per server\n  dados.wintext   list of all the files\nLogs folder: ${logsDir().path}\n  errors.log      saved errors\n",
                "Pasta de dados: ${dataDir().path}\n  terminal/       um arquivo .winv por terminal\n  servidores/     um arquivo .winser por servidor\n  dados.wintext   lista de todos os arquivos\nPasta de logs: ${logsDir().path}\n  errors.log      erros salvos\n"))
            "errors" -> {
                val f = File(logsDir(), "errors.log")
                if (sub(p.getOrNull(1)) == "clear") { f.delete(); append(t, tr("errors cleared\n", "erros apagados\n")) }
                else append(t, if (f.exists() && f.length() > 0) f.readLines().takeLast(30).joinToString("\n") + "\n" else tr("no errors saved\n", "nenhum erro salvo\n"))
            }
            else -> if (!apiCmd(t, p)) shell(t, line)
        }
        save()
    }


    // ---------- termwin-* (Termux API commands, renamed) ----------
    private var tts: TextToSpeech? = null
    private var wake: PowerManager.WakeLock? = null
    private var torchOn = false

    private val API_CMDS = listOf(
        "termwin-info", "termwin-setup-storage", "termwin-battery-status", "termwin-toast", "termwin-vibrate",
        "termwin-clipboard-get", "termwin-clipboard-set", "termwin-open-url", "termwin-notification",
        "termwin-volume", "termwin-torch", "termwin-tts-speak", "termwin-wake-lock", "termwin-wake-unlock",
        "termwin-download"
    )

    /** Returns true when the command was handled here (false = let the Android shell try it). */
    private fun apiCmd(t: TabData, p: List<String>): Boolean {
        val name = p[0].lowercase()
        if (!name.startsWith("termwin-") && !name.startsWith("termux-")) return false
        val a = p.drop(1)
        val text = a.joinToString(" ")
        when (name) {
            "termwin-info" -> append(t, "Device   : ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid  : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nCPU      : ${Build.SUPPORTED_ABIS.joinToString()}\nTermWin  : $VERSION\n")
            "termwin-setup-storage" ->
                if (storageOk()) append(t, tr("storage access already granted\n", "acesso ao armazenamento já liberado\n"))
                else askStorage()
            "termwin-battery-status" -> {
                val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
                val stName = when (st) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
                    BatteryManager.BATTERY_STATUS_FULL -> "FULL"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
                    else -> "UNKNOWN"
                }
                val o = JSONObject()
                o.put("percentage", if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1)
                o.put("plugged", if (plugged == 0) "UNPLUGGED" else "PLUGGED")
                o.put("status", stName)
                o.put("temperature", temp)
                append(t, o.toString(2) + "\n")
            }
            "termwin-toast" ->
                if (text.isEmpty()) err(t, tr("usage: termwin-toast hello", "uso: termwin-toast oi")) else toast(text)
            "termwin-vibrate" -> {
                try {
                    val di = a.indexOf("-d")
                    val ms = (if (di >= 0) a.getOrNull(di + 1)?.toLongOrNull() else null) ?: 1000L
                    val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(1L, 5000L), VibrationEffect.DEFAULT_AMPLITUDE))
                } catch (e: Exception) { err(t, "termwin-vibrate: ${e.message}") }
            }
            "termwin-clipboard-get" -> {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val c = cm.primaryClip
                val s = if (c != null && c.itemCount > 0) c.getItemAt(0).coerceToText(this).toString() else ""
                append(t, s + "\n")
            }
            "termwin-clipboard-set" -> {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("TermWin", text))
                append(t, tr("copied\n", "copiado\n"))
            }
            "termwin-open-url" -> exec(t, "open $text")
            "termwin-notification" -> {
                val c = a.indexOf("--content")
                notifyCmd(t, if (c >= 0) a.drop(c + 1).joinToString(" ") else text)
            }
            "termwin-volume" -> {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val streams = listOf(
                    "call" to AudioManager.STREAM_VOICE_CALL, "system" to AudioManager.STREAM_SYSTEM,
                    "ring" to AudioManager.STREAM_RING, "music" to AudioManager.STREAM_MUSIC,
                    "alarm" to AudioManager.STREAM_ALARM, "notification" to AudioManager.STREAM_NOTIFICATION
                )
                val target = streams.firstOrNull { it.first == a.getOrNull(0)?.lowercase() }
                val level = a.getOrNull(1)?.toIntOrNull()
                if (target != null && level != null) {
                    try { am.setStreamVolume(target.second, level.coerceIn(0, am.getStreamMaxVolume(target.second)), 0) }
                    catch (e: Exception) { err(t, "termwin-volume: ${e.message}") }
                }
                val arr = JSONArray()
                streams.forEach { s ->
                    arr.put(JSONObject().put("stream", s.first).put("volume", am.getStreamVolume(s.second)).put("max_volume", am.getStreamMaxVolume(s.second)))
                }
                append(t, arr.toString(2) + "\n")
            }
            "termwin-torch" -> {
                val want = when (a.getOrNull(0)?.lowercase()) {
                    "on" -> true
                    "off" -> false
                    else -> !torchOn
                }
                try {
                    val cm = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                    val id = cm.cameraIdList.firstOrNull {
                        cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                    if (id == null) err(t, tr("termwin-torch: no flashlight found", "termwin-torch: lanterna não encontrada"))
                    else { cm.setTorchMode(id, want); torchOn = want; append(t, "torch " + (if (want) "on" else "off") + "\n") }
                } catch (e: Exception) { err(t, "termwin-torch: ${e.message}") }
            }
            "termwin-tts-speak" ->
                if (text.isEmpty()) err(t, tr("usage: termwin-tts-speak hello", "uso: termwin-tts-speak oi"))
                else {
                    var engine: TextToSpeech? = null
                    engine = TextToSpeech(this) { status ->
                        if (status == TextToSpeech.SUCCESS) engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "termwin")
                        else ui.post { err(t, "termwin-tts-speak: " + tr("no speech engine", "sem motor de voz")) }
                    }
                    tts = engine
                }
            "termwin-wake-lock" -> {
                if (wake?.isHeld == true) append(t, tr("already holding the wake lock\n", "wake lock já ativo\n"))
                else try {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    val w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TermWin::wake")
                    w.acquire()
                    wake = w
                    append(t, tr("wake lock on\n", "wake lock ligado\n"))
                } catch (e: Exception) { err(t, "termwin-wake-lock: ${e.message}") }
            }
            "termwin-wake-unlock" -> {
                try { wake?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
                wake = null
                append(t, tr("wake lock off\n", "wake lock desligado\n"))
            }
            "termwin-download" -> {
                val u = a.firstOrNull { it.startsWith("http") }
                if (u == null) err(t, tr("usage: termwin-download https://site/file.zip", "uso: termwin-download https://site/arquivo.zip"))
                else {
                    val fname = u.substringBefore('?').substringAfterLast('/').ifEmpty { "download" }
                    busy(1)
                    append(t, tr("downloading $u ...\n", "baixando $u ...\n"))
                    thread {
                        try {
                            val out = File(t.cwd, fname)
                            java.net.URL(u).openStream().use { inp -> out.outputStream().use { o -> inp.copyTo(o) } }
                            ui.post { append(t, tr("saved: ", "salvo: ") + short(out.path) + " (${out.length() / 1024} KiB)\n") }
                        } catch (e: Exception) {
                            ui.post { err(t, "termwin-download: ${e.message}") }
                        }
                        ui.post { busy(-1) }
                    }
                }
            }
            else -> {
                if (!name.startsWith("termux-")) return false
                val alt = "termwin-" + name.removePrefix("termux-")
                if (alt in API_CMDS) err(t, "$name: " + tr("command not found. In TermWin it is called: $alt", "comando não encontrado. No TermWin ele se chama: $alt"))
                else err(t, "$name: " + tr("not available in TermWin", "não disponível no TermWin"))
            }
        }
        return true
    }

    // ---------- server templates (YouTube / Windows 10) ----------
    private fun openUrl(t: TabData, u: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
        catch (e: Exception) { err(t, tr("could not open the browser", "não foi possível abrir o navegador")) }
    }

    private fun tplHelp(label: String, name: String, s: Srv): String {
        val st = if (s.running) "ON" else "OFF"
        val en = listOf("help" to "this help", "play $name" to "turn the server on and open the page",
            "port 4089" to "set the port (then open localhost:4089)", "turn off" to "turn the server off",
            "status" to "show state and port", "clear" to "clear the screen", "exit" to "close this tab")
        val br = listOf("help" to "esta ajuda", "play $name" to "liga o servidor e abre a página",
            "port 4089" to "define a porta (depois abra localhost:4089)", "turn off" to "desliga o servidor",
            "status" to "mostra estado e porta", "clear" to "limpa a tela", "exit" to "fecha esta aba")
        val rows = (if (pt) br else en).joinToString("") { "  " + it.first.padEnd(18) + it.second + "\n" }
        val ai = if (name != "ai") "" else if (pt) "  ia//pergunta//app   gera resposta ou código (app é opcional)\n  ia//faça um jogo//GDScript   exemplo (também: ai// e aí//)\n  ia key SUA_CHAVE   guarda a chave da API (ia key limpar apaga)\n"
            else "  ai//question//app   answer or code (app is optional)\n  ai//make a game//GDScript   example (also: ia// and aí//)\n  ai key YOUR_KEY   store the API key (ai key clear deletes it)\n"
        return tr("$label server — $st, port ${s.port}\n", "Servidor $label — $st, porta ${s.port}\n") + rows + ai
    }

    // ---------- AI template ----------
    private fun aiKey() = prefs.getString("aikey", "") ?: ""

    /** Blocking call to the Anthropic Messages API. Run it off the UI thread. */
    private fun askAi(q: String, spec: String): String {
        val key = aiKey()
        if (key.isEmpty()) return tr("No API key yet. Type: ai key YOUR_KEY  (get one at console.anthropic.com)", "Sem chave da API ainda. Digite: ia key SUA_CHAVE  (crie em console.anthropic.com)")
        return try {
            val sys = "You are the AI inside a mobile terminal app. Answer in the user's language. " +
                "If the user wants code, reply with working code in the requested language (Python, GDScript/GD, Kotlin, JS, Lua, C, etc.) in one fenced block and at most a few short lines of explanation. " +
                "If a target app/engine is given, write the code for exactly that app. Keep it compact; no long introductions."
            val msg = if (spec.isBlank()) q else "$q\n\nTarget app/language: $spec"
            val body = JSONObject().put("model", "claude-sonnet-5-5").put("max_tokens", 4000).put("system", sys)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", msg)))
            val c = java.net.URL("https://api.anthropic.com/v1/messages").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 120000; c.doOutput = true
            c.setRequestProperty("x-api-key", key); c.setRequestProperty("anthropic-version", "2023-06-01")
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val j = JSONObject(txt)
            if (code !in 200..299) {
                val m = j.optJSONObject("error")?.optString("message") ?: txt.take(200)
                logError("ai", "HTTP $code: $m")
                "[$code] $m"
            } else {
                val arr = j.getJSONArray("content")
                (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("\n") { it.getString("text") }.trim()
            }
        } catch (e: Exception) {
            logError("ai", e.toString())
            tr("Could not reach the AI: ${e.message} (check your internet)", "Não consegui falar com a IA: ${e.message} (veja a internet)")
        }
    }

    private fun aiPageHtml(): String = """<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>AI</title>
<style>*{box-sizing:border-box}body{margin:0;background:#0d1117;color:#e6edf3;font:15px system-ui,sans-serif;display:flex;flex-direction:column;height:100vh}
header{padding:14px 16px;font-weight:700;background:#161b22;border-bottom:1px solid #30363d}header span{color:#7ee787}
#log{flex:1;overflow:auto;padding:14px}.m{max-width:760px;margin:0 auto 12px;padding:10px 14px;border-radius:14px;white-space:pre-wrap;word-break:break-word}
.u{background:#1f6feb;margin-left:auto;width:fit-content}.a{background:#161b22;border:1px solid #30363d}
form{display:flex;gap:8px;padding:10px;background:#161b22;border-top:1px solid #30363d;flex-wrap:wrap}
input{flex:1;min-width:140px;padding:12px;border-radius:12px;border:1px solid #30363d;background:#0d1117;color:#fff;font-size:15px}
#sp{flex:0 0 150px;min-width:110px}button{padding:12px 18px;border:0;border-radius:12px;background:#7ee787;color:#000;font-weight:700}</style>
<header><span>●</span> AI — TermWin</header><div id=log><div class="m a">Ask anything. Optional field: app / language (GDScript, Python, Roblox Lua…).</div></div>
<form id=f><input id=q placeholder="Question / Pergunta" autocomplete=off><input id=sp placeholder="App (optional)"><button>Send</button></form>
<script>const L=document.getElementById('log');function add(c,t){const d=document.createElement('div');d.className='m '+c;d.textContent=t;L.appendChild(d);L.scrollTop=L.scrollHeight;return d}
document.getElementById('f').onsubmit=async e=>{e.preventDefault();const q=document.getElementById('q').value.trim();if(!q)return;const sp=document.getElementById('sp').value.trim();document.getElementById('q').value='';add('u',q);const w=add('a','…');
try{const r=await fetch('/ask?q='+encodeURIComponent(q)+'&spec='+encodeURIComponent(sp));w.textContent=(await r.json()).answer}catch(x){w.textContent='Error: '+x}}</script>"""


    private fun tplExec(t: TabData, line: String) {
        val s = findSrv(t.srvName)
        val name = when (t.tpl) { "youtube" -> "youtube"; "ai" -> "ai"; else -> "windows 10" }
        val label = when (t.tpl) { "youtube" -> "YouTube"; "ai" -> "AI"; else -> "Windows 10" }
        val l = line.trim().lowercase().split(" ").filter { it.isNotEmpty() }.joinToString(" ")
        if (l.isEmpty()) return
        if (l == "clear" || l == "limpar") { t.log.setLength(0); outView?.text = ""; return }
        if (l == "exit") { val i = tabs.indexOf(t); if (tabs.size <= 1) closeWindow() else closeTab(i); return }
        if (t.tpl == "ai") {
            val raw = line.trim()
            val low = raw.lowercase()
            val m = Regex("^(ai|ia|aí)\\s*//", RegexOption.IGNORE_CASE).find(raw)
            if (m != null) {
                val parts = raw.substring(m.range.last + 1).split("//")
                val q = parts[0].trim(); val spec = parts.drop(1).joinToString(" ").trim()
                if (q.isEmpty()) { err(t, tr("usage: ai//question//app", "uso: ia//pergunta//app")); return }
                append(t, tr("thinking…\n", "pensando…\n"))
                thread { val a = askAi(q, spec); ui.post { append(t, a + "\n") } }
                return
            }
            val km = Regex("^(ai|ia|aí)\\s+(key|chave)\\s*(.*)$", RegexOption.IGNORE_CASE).find(raw)
            if (km != null) {
                val v = km.groupValues[3].trim()
                if (v.isEmpty()) append(t, if (aiKey().isEmpty()) tr("no key saved\n", "nenhuma chave salva\n") else tr("key saved (…${aiKey().takeLast(4)})\n", "chave salva (…${aiKey().takeLast(4)})\n"))
                else if (v == "clear" || v == "limpar") { prefs.edit().remove("aikey").apply(); append(t, tr("key deleted\n", "chave apagada\n")) }
                else { prefs.edit().putString("aikey", v).apply(); append(t, tr("key saved\n", "chave salva\n")) }
                return
            }
            if (low == "play ia") { tplExec(t, "play ai"); return }
        }
        if (s == null) {
            err(t, tr("the server of this template was removed. Close this tab and open the template again.", "o servidor deste modelo foi removido. Feche esta aba e abra o modelo de novo."))
            return
        }
        when {
            l == "help" || l == "ajuda" -> append(t, tplHelp(label, name, s))
            l == "play $name" -> {
                if (!s.running) startServer(s)
                val u = "http://localhost:${s.port}"
                append(t, "▶ $label: $u\n")
                ui.postDelayed({ openUrl(t, u) }, 700)
            }
            l == "play" || l.startsWith("play ") -> err(t, tr("usage: play $name", "uso: play $name"))
            l == "turn off" || l == "turnoff" || l == "desligar" ->
                if (s.running) stopServer(s) else append(t, tr("the server is already off\n", "o servidor já está desligado\n"))
            l == "port" -> append(t, tr("port: ${s.port}\n", "porta: ${s.port}\n"))
            l.startsWith("port ") -> {
                val n = l.removePrefix("port ").trim().toIntOrNull()
                if (n == null || n < 1024 || n > 65535) err(t, tr("usage: port 4089 (between 1024 and 65535)", "uso: port 4089 (entre 1024 e 65535)"))
                else if (servers.any { it !== s && it.type != "cmd" && it.port == n }) err(t, tr("port $n is already used by another server", "a porta $n já é usada por outro servidor"))
                else {
                    val was = s.running
                    if (was) stopServer(s)
                    s.port = n
                    append(t, if (was) tr("port set to $n — http://localhost:$n\n", "porta definida para $n — http://localhost:$n\n")
                        else tr("port set to $n. Type: play $name\n", "porta definida para $n. Digite: play $name\n"))
                    if (was) ui.postDelayed({ startServer(s) }, 500)
                    serversRefresh?.invoke()
                }
            }
            l == "status" -> append(t, tr("server: ${s.name}\nstate: ", "servidor: ${s.name}\nestado: ") + (if (s.running) "ON" else "OFF") + "\nport: ${s.port}\nurl: http://localhost:${s.port}\n")
            else -> err(t, "$l: " + tr("unknown command. Type help", "comando desconhecido. Digite help"))
        }
    }

    private fun openTemplate(key: String) {
        val label = when (key) { "youtube" -> "YouTube"; "ai" -> "AI"; else -> "Windows 10" }
        val s = Srv(uniqueSrvName(label), "tpl-$key", freePort(), "")
        servers.add(s)
        val t = TabData(uniqueTabName(label), filesDir.path,
            StringBuilder(tr("$label template — type 'help' to see the commands.\n", "Modelo $label — digite 'help' para ver os comandos.\n")))
        t.tpl = key
        t.srvName = s.name
        tabs.add(t)
        cur = tabs.size - 1
        refreshTabs(); showTab(); save()
    }

    private fun showTemplates() {
        val p = panel(tr("Server templates", "Modelos de servidor"), 0.7f)
        p.body.addView(tv(tr("Pick a template. It opens a new tab — type help there.", "Escolha um modelo. Ele abre uma aba nova — digite help lá."), 12f, Color.WHITE))
        fun card(icon: String, title: String, desc: String, key: String) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = rounded(0xFF2A2A2A.toInt(), dp(8))
            row.setPadding(dp(12), dp(10), dp(8), dp(10))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(tv("$icon  $title", 15f, Color.WHITE))
            col.addView(tv(desc, 11f, 0xFF9AA5B1.toInt()))
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(smallBtn(tr("Use", "Usar")) { p.close(); openTemplate(key) })
            p.body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        card("▶", "YouTube Mobile", tr("video feed, Shorts, player — command: play youtube", "feed de vídeos, Shorts, player — comando: play youtube"), "youtube")
        card("✦", tr("AI Assistant", "IA Assistente"), tr("ask or generate code in any language — command: ai//question//app (play ai opens the chat page)", "responde ou gera código em qualquer linguagem — comando: ia//pergunta//app (play ai abre o chat)"), "ai")
        card("⊞", "Windows 10 Mobile", tr("lock screen, live tiles, apps — command: play windows 10", "tela de bloqueio, blocos dinâmicos, apps — comando: play windows 10"), "windows10")
        p.button(tr("Close", "Fechar"), true) { p.close() }
    }

    // ---------- dados.wintext editor ----------
    private fun showDados() {
        writeIndex()
        val f = File(dataDir(), "dados.wintext")
        val p = panel("dados.wintext", 0.85f)
        p.body.addView(tv(tr("Edit the file. The [Normal] and [Servers] lists update by themselves; write your own text under [Notes].", "Edite o arquivo. As listas [Normal] e [Servers] se atualizam sozinhas; escreva o seu texto em [Notes]."), 12f, 0xFF9AA5B1.toInt()))
        val et = field("dados.wintext", try { f.readText() } catch (e: Exception) { "" }, true)
        et.typeface = Typeface.MONOSPACE
        et.minLines = 12
        p.body.addView(et)
        p.button(tr("Close", "Fechar")) { p.close() }
        p.button(tr("Save", "Salvar"), true) {
            try { f.writeText(et.text.toString()); writeIndex(); toast(tr("saved", "salvo")); p.close() }
            catch (e: Exception) { toast("dados.wintext: ${e.message}") }
        }
    }

    /** Built-in text editor (nano/edit/vi): opens a panel with the file's text. */
    private fun editFile(t: TabData, name: String?) {
        if (name == null) { err(t, tr("usage: nano file.txt", "uso: nano arquivo.txt")); return }
        val f = if (name.startsWith("/")) File(name) else File(t.cwd, name)
        if (f.isDirectory) { err(t, "nano: $name: " + tr("is a folder", "é uma pasta")); return }
        if (f.length() > 300_000) { err(t, "nano: $name: " + tr("file too big", "arquivo muito grande")); return }
        val init = try { if (f.exists()) f.readText() else "" } catch (e: Exception) { err(t, "nano: ${e.message}"); return }
        val p = panel("nano ${f.name}", 0.85f)
        val et = field(tr("empty file", "arquivo vazio"), init, true)
        et.typeface = Typeface.MONOSPACE
        et.minLines = 10
        p.body.addView(et)
        p.button(tr("Cancel", "Cancelar")) { p.close() }
        p.button(tr("Save", "Salvar"), true) {
            try {
                f.parentFile?.mkdirs()
                f.writeText(et.text.toString())
                append(t, tr("saved ", "salvo ") + short(f.path) + "\n")
                p.close()
            } catch (e: Exception) { err(t, "nano: ${e.message}") }
        }
    }

    private fun histMove(et: EditText, d: Int) {
        if (history.isEmpty()) return
        if (d < 0) hIdx = if (hIdx < 0) history.size - 1 else (hIdx - 1).coerceAtLeast(0)
        else if (hIdx >= 0) hIdx++
        else return
        if (hIdx >= history.size) { hIdx = -1; et.setText("") } else et.setText(history[hIdx])
        et.setSelection(et.text.length)
    }

    private fun tabComplete(et: EditText) {
        val t = tabs[cur]
        val txt = et.text.toString()
        val tok = txt.substringAfterLast(' ')
        val base = tok.substringAfterLast('/')
        val dir = when {
            !tok.contains('/') -> File(t.cwd)
            tok.startsWith("/") -> File(tok.substringBeforeLast('/').ifEmpty { "/" })
            else -> File(t.cwd, tok.substringBeforeLast('/'))
        }
        val m = (dir.listFiles() ?: return).filter { it.name.startsWith(base) }.sortedBy { it.name }
        if (m.isEmpty()) return
        if (m.size == 1) {
            et.setText(txt.dropLast(base.length) + m[0].name + (if (m[0].isDirectory) "/" else ""))
        } else {
            var common = m[0].name
            m.forEach { x -> while (!x.name.startsWith(common)) common = common.dropLast(1) }
            if (common.length > base.length) et.setText(txt.dropLast(base.length) + common)
            else append(t, m.joinToString("  ") { it.name + (if (it.isDirectory) "/" else "") } + "\n")
        }
        et.setSelection(et.text.length)
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
        append(t, tr("Checking internal packages...\n${pkgs.size} package(s) installed — all up to date.\nTermWin $VERSION\n",
            "Verificando pacotes internos...\n${pkgs.size} pacote(s) instalado(s) — tudo em dia.\nTermWin $VERSION\n"))

    private fun pkgCmd(t: TabData, p: List<String>) {
        val n = p.getOrNull(2)?.lowercase()?.let { if (pt && it == "arvore") "tree" else it }
        when (sub(p.getOrNull(1))) {
            "install" -> when {
                n == null -> err(t, tr("usage: pkg install cowsay", "uso: pkg instalar cowsay"))
                n == "termwin" -> append(t, tr("termwin is the core and is already installed.\n", "termwin é o núcleo e já está instalado.\n"))
                n !in CATALOG -> err(t, tr("package '$n' not found. See: pkg list", "pacote '$n' não encontrado. Veja: pkg listar"))
                n in pkgs -> append(t, tr("$n is already installed.\n", "$n já está instalado.\n"))
                else -> { pkgs.add(n); append(t, tr("Installing $n ...\n$n installed ✔\n", "Instalando $n ...\n$n instalado ✔\n")) }
            }
            "remove", "uninstall" -> when {
                n == null -> err(t, tr("usage: pkg remove cowsay", "uso: pkg remover cowsay"))
                pkgs.remove(n) -> append(t, tr("$n removed.\n", "$n removido.\n"))
                else -> err(t, tr("$n is not installed.", "$n não está instalado."))
            }
            "list-installed" -> append(t, if (pkgs.isEmpty()) tr("no packages installed\n", "nenhum pacote instalado\n") else pkgs.sorted().joinToString("\n") + "\n")
            "search" -> {
                val q = (p.getOrNull(2) ?: "").lowercase()
                val r = CATALOG.filter { q in it || q in pkgDesc(it).lowercase() }
                if (r.isEmpty()) err(t, tr("no packages found for '$q'", "nenhum pacote encontrado para '$q'"))
                else r.forEach { k -> append(t, k.padEnd(10) + pkgDesc(k) + "\n") }
            }
            "show" -> when {
                n == null -> err(t, tr("usage: pkg show cowsay", "uso: pkg mostrar cowsay"))
                n !in CATALOG -> err(t, tr("package '$n' not found. See: pkg list", "pacote '$n' não encontrado. Veja: pkg listar"))
                else -> append(t, "Package: $n\n" + tr("Description: ", "Descrição: ") + pkgDesc(n) + "\n" +
                    tr("Status: ", "Situação: ") + (if (n in pkgs) tr("installed", "instalado") else tr("not installed", "não instalado")) + "\n")
            }
            "list", "list-all" -> {
                append(t, tr("TermWin internal packages:\n", "Pacotes internos do TermWin:\n"))
                CATALOG.forEach { k -> append(t, (if (k in pkgs) "[x] " else "[ ] ") + k.padEnd(10) + pkgDesc(k) + "\n") }
            }
            "upgrade", "update", "full-upgrade" -> upgrade(t)
            else -> err(t, tr("usage: pkg install | remove | list | upgrade", "uso: pkg instalar | remover | listar | atualizar"))
        }
    }

    private fun shell(t: TabData, line: String) {
        if (t.proc != null) { err(t, tr("A command is already running (use ^C)", "Já existe um comando rodando (use ^C)")); return }
        busy(1)
        thread {
            var ok = false
            var info = ""
            val tail = StringBuilder()
            try {
                val pb = ProcessBuilder("sh", "-c", line).directory(File(t.cwd)).redirectErrorStream(true)
                pb.environment()["HOME"] = filesDir.path
                pb.environment()["TMPDIR"] = cacheDir.path
                envVars.forEach { (k, v) -> pb.environment()[k] = v }
                val pr = pb.start()
                t.proc = pr
                pr.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(1024)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        val s = String(buf, 0, n)
                        tail.append(s)
                        if (tail.length > 600) tail.delete(0, tail.length - 600)
                        ui.post { append(t, s) }
                    }
                }
                val code = pr.waitFor()
                ok = code == 0
                info = if (ok) tr("finished successfully", "terminou com sucesso") else tr("failed (code $code)", "falhou (código $code)")
                if (!ok) logError(t.name, "$line -> exit $code | ${tail.toString().trim().takeLast(300)}")
                ui.post { append(t, tr("[exit: $code]\n", "[saiu: $code]\n")) }
            } catch (e: Exception) {
                info = tr("error: ${e.message}", "erro: ${e.message}")
                logError(t.name, "$line -> ${e.message}")
                ui.post { append(t, info + "\n") }
            }
            t.proc = null
            ui.post {
                busy(-1); save()
                notifyDone(if (ok) tr("✔ Command finished", "✔ Comando concluído") else tr("✖ Command failed", "✖ Comando falhou"), "$line — $info", ok)
            }
        }
    }

    // ---------- servers ----------
    private fun freePort(): Int = (8080 until 9000).first { p -> servers.none { it.port == p } }
    private fun findSrv(n: String?) = servers.firstOrNull { it.name.equals(n, true) }

    private fun serverCmd(t: TabData, p: List<String>) {
        val nf = tr("server not found", "servidor não encontrado")
        when (sub(p.getOrNull(1))) {
            "create" -> {
                val n = p.getOrNull(2)
                if (n == null) { err(t, tr("usage: server create mysite web 8080", "uso: servidor criar meusite web 8080")); return }
                if (findSrv(n) != null) { err(t, tr("a server with that name already exists", "já existe um servidor com esse nome")); return }
                val ty = cmdType((p.getOrNull(3) ?: "web").lowercase())
                if (ty == null) { err(t, tr("invalid type (youtube, web, files, json, cmd, custom)", "tipo inválido (youtube, web, arquivos, json, comando, personalizado)")); return }
                val po = p.getOrNull(4)?.toIntOrNull() ?: freePort()
                val extra = p.drop(5).joinToString(" ")
                servers.add(Srv(n, ty, po, extra))
                append(t, tr("server '$n' created ($ty, port $po). Use: server start $n\n", "servidor '$n' criado ($ty, porta $po). Use: servidor iniciar $n\n"))
            }
            "list" -> {
                if (servers.isEmpty()) append(t, tr("no servers\n", "nenhum servidor\n"))
                servers.forEach { append(t, (if (it.running) "● " else "○ ") + "${it.name}  [${it.type}] :${it.port}\n") }
            }
            "start" -> { val s = findSrv(p.getOrNull(2)); if (s == null) err(t, nf) else startServer(s) }
            "stop" -> { val s = findSrv(p.getOrNull(2)); if (s == null) err(t, nf) else stopServer(s) }
            "remove" -> { val s = findSrv(p.getOrNull(2)); if (s == null) err(t, nf) else { stopServer(s); servers.remove(s) } }
            "rename" -> {
                val s = findSrv(p.getOrNull(2)); val novo = p.getOrNull(3)
                if (s == null || novo == null) err(t, tr("usage: server rename mysite newname", "uso: servidor renomear meusite novo"))
                else if (findSrv(novo) != null) err(t, tr("a server with that name already exists", "já existe um servidor com esse nome"))
                else { s.name = novo; append(t, tr("renamed to $novo\n", "renomeado para $novo\n")) }
            }
            else -> err(t, tr("usage: server list | create | start | stop | remove | rename", "uso: servidor listar | criar | iniciar | parar | remover | renomear"))
        }
    }

    private fun startServer(s: Srv) {
        if (s.running) { sayCur(tr("'${s.name}' is already running\n", "'${s.name}' já está rodando\n")); return }
        s.running = true
        busy(1)
        if (s.type == "cmd") {
            thread {
                var ok = false
                try {
                    val pr = ProcessBuilder("sh", "-c", s.cmd).directory(filesDir).redirectErrorStream(true).start()
                    s.proc = pr
                    ui.post { sayCur(tr("[${s.name}] started\n", "[${s.name}] iniciado\n")) }
                    pr.inputStream.bufferedReader().forEachLine { l -> ui.post { sayCur("[${s.name}] $l\n") } }
                    ok = pr.waitFor() == 0
                } catch (e: Exception) { ui.post { sayErr(tr("error: ${e.message}", "erro: ${e.message}")) } }
                if (!ok) logError("server", "${s.name}: ${s.cmd} failed")
                s.running = false
                ui.post {
                    sayCur(tr("[${s.name}] stopped\n", "[${s.name}] encerrado\n")); busy(-1); serversRefresh?.invoke()
                    notifyDone(if (ok) tr("✔ Server '${s.name}' finished", "✔ Servidor '${s.name}' terminou") else tr("✖ Server '${s.name}' failed", "✖ Servidor '${s.name}' falhou"), s.cmd, ok)
                }
            }
        } else {
            thread {
                try {
                    val ss = ServerSocket(s.port, 50, InetAddress.getByName("127.0.0.1"))
                    s.sock = ss
                    ui.post { sayCur(tr("Server '${s.name}' online: http://localhost:${s.port}\n", "Servidor '${s.name}' no ar: http://localhost:${s.port}\n")); serversRefresh?.invoke() }
                    while (s.running) {
                        val c = ss.accept()
                        thread { serve(c, s) }
                    }
                } catch (e: Exception) {
                    if (s.running) ui.post { sayErr(tr("server '${s.name}' error: ${e.message}", "erro no servidor '${s.name}': ${e.message}")) }
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
        sayCur(tr("'${s.name}' stopped\n", "'${s.name}' parado\n"))
        serversRefresh?.invoke()
    }

    private fun send(o: OutputStream, ctype: String, data: ByteArray, status: String = "200 OK") {
        o.write("HTTP/1.1 $status\r\nContent-Type: $ctype\r\nContent-Length: ${data.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n".toByteArray())
        o.write(data)
    }

    private fun sendAsset(o: OutputStream, name: String) {
        val data = try { assets.open(name).use { it.readBytes() } } catch (e: Exception) { "template not found".toByteArray() }
        send(o, "text/html; charset=utf-8", data)
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
                "tpl-youtube" -> sendAsset(o, "template_youtube.html")
                "tpl-windows10" -> sendAsset(o, "template_windows10.html")
                "tpl-ai" -> if (path == "/ask") {
                    val qs = first.split(" ").getOrNull(1)?.substringAfter("?", "") ?: ""
                    fun qp(k: String) = qs.split("&").firstOrNull { it.startsWith("$k=") }?.substringAfter("=")?.let { Uri.decode(it.replace("+", " ")) } ?: ""
                    send(o, "application/json; charset=utf-8", JSONObject().put("answer", askAi(qp("q"), qp("spec"))).toString().toByteArray())
                } else send(o, "text/html; charset=utf-8", aiPageHtml().toByteArray())
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
            head + "<header><b>▶</b> <span>$n</span><input id=u placeholder='${tr("Paste a YouTube link", "Cole um link do YouTube")}'><button onclick=\"var m=document.getElementById('u').value.match(/(?:v=|youtu\\.be\\/)([\\w-]{11})/);if(m)document.getElementById('f').src='https://www.youtube.com/embed/'+m[1]\">${tr("Watch", "Assistir")}</button></header><iframe id=f allowfullscreen></iframe>"
        else
            head + "<header><b>●</b> $n</header><p style='padding:16px'>" + tr("Server online ✔ (port ${s.port})", "Servidor online ✔ (porta ${s.port})") + "</p>"
    }

    // ---------- server panels ----------
    private fun smallBtn(text: String, red: Boolean = false, click: () -> Unit) =
        tv(text, 12f, if (red) 0xFFFF8A80.toInt() else Color.WHITE).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(0xFF3A3A3A.toInt(), dp(6))
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) }
        }

    private fun showServers() {
        val p = panel(tr("Servers", "Servidores"), 0.75f)
        fun fill() {
            p.body.removeAllViews()
            if (servers.isEmpty()) p.body.addView(tv(tr("No servers yet. Tap ＋ Server.", "Nenhum servidor ainda. Toque em ＋ Servidor."), 14f, 0xFFAAAAAA.toInt()).apply { setPadding(0, dp(12), 0, dp(12)) })
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
                row.addView(smallBtn(if (s.running) tr("Stop", "Parar") else tr("Start", "Iniciar")) { if (s.running) stopServer(s) else startServer(s); fill() })
                if (s.running && s.type != "cmd") row.addView(smallBtn(tr("Open", "Abrir")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:${s.port}"))) } catch (e: Exception) { toast(tr("No browser", "Sem navegador")) }
                })
                row.addView(smallBtn(tr("Rename", "Renomear")) {
                    promptText(tr("New name", "Novo nome"), s.name) { n ->
                        if (servers.any { it !== s && it.name.equals(n, true) }) toast(tr("A server with that name already exists", "Já existe um servidor com esse nome"))
                        else { s.name = n; save(); fill() }
                    }
                })
                row.addView(smallBtn(tr("Remove", "Remover"), true) { stopServer(s); servers.remove(s); save(); fill() })
                p.body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
            }
        }
        serversRefresh = { fill() }
        p.onClose = { serversRefresh = null }
        fill()
        p.button(tr("Close", "Fechar"), true) { p.close() }
    }

    private fun createServerDialog() {
        val p = panel(tr("Create server", "Criar servidor"), 0.6f)
        val name = field(tr("Name (e.g. YouTube)", "Nome (ex.: YouTube)"))
        var sel = 0
        val combo = tv("${typeLabel(TYPE_KEYS[0])}   ▾", 14f, Color.WHITE).apply {
            background = rounded(0xFF2D2D2D.toInt(), dp(6), 0xFF454545.toInt())
            setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
        }
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.background = rounded(0xFF262626.toInt(), dp(6), 0xFF454545.toInt())
        list.visibility = View.GONE
        val port = field(tr("Port", "Porta"), freePort().toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val extra = field("", "", true)
        fun upd() {
            val k = TYPE_KEYS[sel]
            extra.visibility = if (extraHint(k) != null) View.VISIBLE else View.GONE
            extra.hint = extraHint(k) ?: ""
            port.visibility = if (k == "cmd") View.GONE else View.VISIBLE
        }
        TYPE_KEYS.forEachIndexed { i, k ->
            list.addView(tv(typeLabel(k), 14f, Color.WHITE).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { sel = i; combo.text = "${typeLabel(k)}   ▾"; list.visibility = View.GONE; upd() }
            })
        }
        combo.setOnClickListener { list.visibility = if (list.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        p.body.addView(name); p.body.addView(combo); p.body.addView(list, LinearLayout.LayoutParams(MATCH, WRAP))
        p.body.addView(port); p.body.addView(extra)
        upd()
        p.button(tr("Cancel", "Cancelar")) { p.close() }
        p.button(tr("Create", "Criar"), true) {
            val n = name.text.toString().trim()
            if (n.isEmpty() || findSrv(n) != null) { toast(tr("Empty name or already exists", "Nome vazio ou já existe")); return@button }
            val ty = TYPE_KEYS[sel]
            val po = port.text.toString().toIntOrNull() ?: freePort()
            if (ty != "cmd" && servers.any { it.type != "cmd" && it.port == po }) { toast(tr("Port $po is already used by another server", "Porta $po já usada por outro servidor")); return@button }
            val ex = extra.text.toString().trim()
            if (ty == "cmd" && ex.isEmpty()) { toast(tr("Type the command", "Digite o comando")); return@button }
            servers.add(Srv(n, ty, po, ex))
            save()
            sayCur(tr("server '$n' created ($ty). Start it in Servers.\n", "servidor '$n' criado ($ty). Inicie em Servidores.\n"))
            p.close()
        }
    }

    private fun promptText(title: String, init: String, ok: (String) -> Unit) {
        val p = panel(title, 0.45f)
        val et = field(tr("Name", "Nome"), init)
        p.body.addView(et)
        p.button(tr("Cancel", "Cancelar")) { p.close() }
        p.button("OK", true) { val s = et.text.toString().trim(); if (s.isNotEmpty()) ok(s); p.close() }
    }
}
