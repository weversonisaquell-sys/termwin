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
import android.provider.DocumentsContract
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

const val VERSION = "2.4"

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

/** Keeps TermWin alive in the background while a command/server is running, and for 1h22 after you leave the app. */
class TermService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var lock: PowerManager.WakeLock? = null
    private val expire = Runnable {
        MainActivity.keepUntil = 0L
        releaseLock()
        if (MainActivity.instance?.isBusy() != true) { try { stopForeground(Service.STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }; stopSelf() }
    }
    private fun releaseLock() { try { if (lock?.isHeld == true) lock?.release() } catch (e: Exception) { }; lock = null }
    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { h.removeCallbacks(expire); releaseLock(); super.onDestroy() }
    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == "com.termwin.OFF") {
            h.removeCallbacks(expire); releaseLock(); MainActivity.keepUntil = 0L
            val a = MainActivity.instance
            if (a != null) a.turnOffAll() else { stopSelf(); android.os.Process.killProcess(android.os.Process.myPid()) }
            return START_NOT_STICKY
        }
        if (i?.action == "com.termwin.KEEP_OFF") {
            h.removeCallbacks(expire); releaseLock(); MainActivity.keepUntil = 0L
            if (MainActivity.instance?.isBusy() != true) { try { stopForeground(Service.STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }; stopSelf() }
            return START_NOT_STICKY
        }
        val keep = i?.action == "com.termwin.KEEP"
        ensureChannels(this)
        val pt = getSharedPreferences("termwin", 0).getBoolean("pt", false)
        val pf = android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        val off = android.app.PendingIntent.getService(this, 11, Intent(this, TermService::class.java).setAction("com.termwin.OFF"), pf)
        val enter = android.app.PendingIntent.getActivity(this, 12, Intent(this, MainActivity::class.java).setAction("com.termwin.ENTER")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), pf)
        val ic = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_termwin)
        val text = if (keep) (if (pt) "Aberto por até 1h22 depois que você saiu…" else "Staying open for up to 1h22 after you left…")
                   else "Running commands / servers…"
        val n = Notification.Builder(this, "run")
            .setSmallIcon(R.drawable.ic_stat_termwin)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(resources, R.drawable.ic_notif_large))
            .setContentTitle("TermWin")
            .setContentText(text)
            .setContentIntent(enter)
            .addAction(Notification.Action.Builder(ic, "turn off", off).build())
            .addAction(Notification.Action.Builder(ic, "enter", enter).build())
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
        if (keep) {
            MainActivity.keepUntil = System.currentTimeMillis() + MainActivity.KEEP_MS
            h.removeCallbacks(expire)
            h.postDelayed(expire, MainActivity.KEEP_MS)
            releaseLock()
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TermWin::keep").also { it.acquire(MainActivity.KEEP_MS) }
            } catch (e: Exception) { }
        }
        return START_NOT_STICKY
    }
}

class MaxScroll(c: Context, private val maxH: Int) : ScrollView(c) {
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
}

class MainActivity : Activity() {
    companion object {
        @Volatile var instance: MainActivity? = null
        /** 1 h 22 min: how long the app stays alive after you leave it. */
        const val KEEP_MS = 82L * 60L * 1000L
        @Volatile var keepUntil = 0L
    }
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
  botão 📜 Savedata           comandos recentes (Files/Savedata.wintext); toque para executar
  apk//open//nome.apk        procura o APK, abre o app em uma janela e guarda a pasta dele em Files/App data
  shizuku                    mostra o estado do Shizuku e pede a permissão (usado no lugar do root)
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
  creat//Projeto//html//index.html   cria o arquivo em Files/Projects/Projeto e abre o editor (py, js, css, json…)
  open//Projeto//index.html  abre o arquivo no editor
  Roblox//arquivo.rbxl       acha o arquivo no celular, publica como modelo no Roblox e abre o link da loja numa janela
  Roblox//chave              salva a chave da API e o seu ID do Roblox (só na primeira vez)
  open//github.com/usuario/repo   abre o site numa janela do app (movível) e dá para baixar arquivos de qualquer site
  ct arquivo.txt             cria o arquivo em Files/Files Created e abre o editor (sem nome: pergunta o nome)
  cmd1 && cmd2 && cmd3       encadeia comandos como no Termux (help && clear funciona; até 1780 &&, com 1781 para e avisa)
  copy NomePasta             procura no celular todo e copia para Files/Copied/NomePasta
  copy                       abre o seletor para copiar pastas de OUTROS apps (ex.: Termux) para Files/Copied  |  depois: cd copied/NomePasta
  🛡 PROTEÇÃO: rm, mv, find -delete etc. só funcionam dentro da pasta do app (~). Fora dela (Download, fotos…) são bloqueados.
  projetos                   lista os projetos
  ls storage                 mostra tudo de storage/emulated/0 com ícone de pasta (ls storage -r = com subpastas)
  cd qualquer/caminho        funciona com sdcard, storage/emulated/0/download, ~, .., maiúsculas/minúsculas
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
  📜 Savedata button         recent commands (Files/Savedata.wintext); tap one to run it
  apk//open//name.apk        find the APK, open the app in a window and keep its folder in Files/App data
  shizuku                    show Shizuku status and ask for permission (used instead of root)
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
  creat//Project//html//index.html   create the file in Files/Projects/Project and open the editor (py, js, css, json…)
  open//Project//index.html  open the file in the editor
  Roblox//file.rbxl          find the file on the phone, publish it as a Roblox model and open the store link in a window
  Roblox//key                save the API key and your Roblox ID (first time only)
  open//github.com/user/repo   open the site in an app window (movable); you can download files from any site
  ct file.txt                create the file in Files/Files Created and open the editor (no name: it asks)
  cmd1 && cmd2 && cmd3       chain commands like Termux (help && clear works; up to 1780 &&, 1781 stops and warns)
  copy FolderName            search the whole phone and copy to Files/Copied/FolderName
  copy                       opens the picker to copy folders from OTHER apps (e.g. Termux) to Files/Copied  |  then: cd copied/FolderName
  🛡 PROTECTION: rm, mv, find -delete etc. only work inside the app folder (~). Anywhere else (Downloads, photos…) they are blocked.
  projects                   list projects
  ls storage                 show everything in storage/emulated/0 with folder icons (ls storage -r = with subfolders)
  cd any/path                works with sdcard, storage/emulated/0/download, ~, .., any letter case
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
        tv(text, 13f, if (primary) Color.BLACK else Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(if (primary) ACCENT else 0xFF2D2D2D.toInt(), dp(8), if (primary) 0 else 0xFF454545.toInt())
            setOnClickListener { click() }
        }

    private fun winBtn(text: String, primary: Boolean, click: () -> Unit) = pill(text, primary, click).apply {
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) }
    }

    private fun field(hint: String, init: String = "", multi: Boolean = false) = EditText(this).apply {
        enableImagePaste(this)
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
        bodyBox.setPadding(dp(16), dp(6), dp(16), dp(10))
        val sc = MaxScroll(this, (dm.heightPixels * 0.6).toInt())
        sc.addView(bodyBox)
        card.addView(sc, LinearLayout.LayoutParams(MATCH, WRAP))

        val foot = LinearLayout(this)
        foot.orientation = LinearLayout.HORIZONTAL
        foot.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        foot.setBackgroundColor(0xFF1A1A1A.toInt())
        foot.setPadding(dp(12), dp(6), dp(12), dp(6))
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
        instance = this
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        load()
        System.setProperty("user.home", filesDir.path)
        try { projectsDir(); copiedDir(); createdDir(); downloadsDir(); photosSentDir(); appDataDir() } catch (e: Exception) { }
        ensureStorageLinks()
        ensureChannels(this)
        root = FrameLayout(this)
        root.setBackgroundColor(0xFF0B0F14.toInt())
        buildHome()
        val wrap = FrameLayout(this)
        wrap.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        gMouse = MouseLayer(root, true)
        wrap.addView(gMouse, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(wrap)
        applyMouse()
        handleIncoming(intent)
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        handleIncoming(i)
    }

    /** A .winv / .winser / .wintext tapped in a file manager opens here. */
    private fun handleIncoming(i: Intent?) {
        val it2 = i ?: return
        if (it2.action == "com.termwin.ENTER") { it2.action = null; ui.post { enterRunning() }; return }
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
        btns.addView(pill("🔑  API Keys", false) { showApiKeys() }, gap())
        btns.addView(pill(tr("👤  Profile", "👤  Perfil"), false) { showProfile() }, gap())
        home.addView(btns, LinearLayout.LayoutParams(WRAP, WRAP))
        val btns2 = LinearLayout(this)
        btns2.orientation = LinearLayout.HORIZONTAL
        btns2.gravity = Gravity.CENTER
        btns2.addView(pill("📜  Savedata", false) { showSavedata() })
        home.addView(btns2, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(10) })
        root.addView(home, FrameLayout.LayoutParams(MATCH, MATCH))

        val mb = pill(mouseLabel(), false) { toggleGlobalMouse() }
        homeMouseBtn = mb
        root.addView(mb, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { topMargin = dp(8); rightMargin = dp(10) })

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
        webMice.clear()
        buildHome()
        applyMouse()
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
    override fun onBackPressed() {
        if (panels.isNotEmpty()) panels.last().close()
        else { keepAlive(); moveTaskToBack(true) } // leaving does not close the app: it stays open for 1h22
    }
    override fun onStart() { super.onStart(); bg = false; keepOff() }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); keepAlive() }
    override fun onStop() { super.onStop(); bg = true; keepAlive() }
    override fun onResume() { super.onResume(); settingsSync?.invoke(); ensureStorageLinks() }
    override fun onPause() { super.onPause(); save() }
    override fun onDestroy() { if (instance === this) instance = null; try { instRx?.let { unregisterReceiver(it) } } catch (e: Exception) { }; instRx = null; super.onDestroy() }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        settingsSync?.invoke()
        val denied = res.isNotEmpty() && res[0] != PackageManager.PERMISSION_GRANTED
        if (code == 3) { val wasPending = voicePending; voicePending = false; if (denied) voiceJs("error", tr("Microphone permission denied", "Permissão do microfone negada")) else if (wasPending) startVoice(null) }
        if (code == 2 && denied && Build.VERSION.SDK_INT >= 33 && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openNotifSettings()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 78) { val cb = pendingAuth; pendingAuth = null; if (res == RESULT_OK) cb?.invoke() else toast(tr("Not authenticated", "Não autenticado")); return }
        if (req == 81) {
            val cb = fileCb; fileCb = null
            val uris = android.webkit.WebChromeClient.FileChooserParams.parseResult(res, data)
            cb?.onReceiveValue(uris)
            uris?.forEach { savePhotoSent(it) }
            return
        }
        if (req == 79) { if (res == RESULT_OK) data?.data?.let { showPublish(it) }; return }
        if (req == 80) { if (res == RESULT_OK) data?.data?.let { copyFromTree(it) } else copyTab?.let { append(it, tr("copy cancelled\n", "cópia cancelada\n")) }; return }
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
            sb.append("\n[Profile]\n")
            val pr = profile()
            if (pr == null) sb.append("(not signed in)\n") else sb.append("name=").append(pr.optString("name")).append("\nemail=").append(pr.optString("email")).append("\nchannel=").append(pr.optString("channel")).append('\n')
            sb.append("\n[Videos]\n")
            val va = vidsRead()
            if (va.length() == 0) sb.append("(none)\n") else for (i in 0 until va.length()) { val e = va.getJSONObject(i); sb.append("videos/").append(e.optString("file")).append(" | ").append(e.optString("title")).append(" | ").append(e.optString("channel")).append('\n') }
            sb.append("\n")
            sb.append(if (ni >= 0) old.substring(ni).trimEnd() + "\n" else "[Notes]\n" + tr("(write anything here - this part is kept)", "(escreva o que quiser aqui - esta parte fica salva)") + "\n")
            f.writeText(sb.toString())
        } catch (e: Exception) { }
    }

    private val saver = Runnable { save() }
    private fun saveSoon() { ui.removeCallbacks(saver); ui.postDelayed(saver, 1500) }

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
        try { seedVideos() } catch (e: Exception) { }
        writeIndex()
        try { loadRecent(); writeRecent() } catch (e: Exception) { }
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
                .setSmallIcon(R.drawable.ic_stat_termwin)
                .setLargeIcon(android.graphics.BitmapFactory.decodeResource(resources, R.drawable.ic_notif_large))
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

    fun isBusy() = busyN > 0

    /** Leaving the app: keep it alive (foreground service + wake lock) for 1h22 before it can close. */
    private fun keepAlive() {
        if (keepUntil > System.currentTimeMillis()) return
        try {
            keepUntil = System.currentTimeMillis() + KEEP_MS
            startForegroundService(Intent(this, TermService::class.java).setAction("com.termwin.KEEP"))
        } catch (e: Exception) { keepUntil = 0L; logError("keep", e.message ?: "start") }
    }

    /** Back in the app: the keep-alive is not needed any more. */
    private fun keepOff() {
        if (keepUntil == 0L) return
        keepUntil = 0L
        try { startService(Intent(this, TermService::class.java).setAction("com.termwin.KEEP_OFF")) } catch (e: Exception) { }
    }

    /** Notification button "turn off": stops every server and running command, then the background service. */
    fun turnOffAll() {
        keepUntil = 0L
        ui.post {
            servers.filter { it.running }.forEach { try { stopServer(it) } catch (e: Exception) { } }
            tabs.forEach { try { it.proc?.destroy() } catch (e: Exception) { } }
            busyN = 0
            try { stopService(Intent(this, TermService::class.java)) } catch (e: Exception) { }
            toast(tr("everything turned off", "tudo desligado"))
            serversRefresh?.invoke()
        }
    }

    /** Notification button "enter": opens the window on the running server (or the servers list). */
    private fun enterRunning() {
        restore()
        val s = servers.firstOrNull { it.running } ?: return
        val i = tabs.indexOfFirst { it.srvName == s.name }
        if (i >= 0) { cur = i; refreshTabs(); showTab() } else showServers()
    }

    private fun busy(d: Int) {
        val before = busyN
        busyN = (busyN + d).coerceAtLeast(0)
        try {
            if (before == 0 && busyN > 0) startForegroundService(Intent(this, TermService::class.java))
            else if (before > 0 && busyN == 0 && keepUntil <= System.currentTimeMillis()) stopService(Intent(this, TermService::class.java))
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
    // ---------- paste an image (IMG) into a text field: it is saved in Files/Photos sent and its path is typed ----------
    private fun pastedDir() = photosSentDir()
    private fun photosSentDir() = File(filesRoot(), "Photos sent").apply { mkdirs() }

    private fun enableImagePaste(et: EditText) {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            et.setOnReceiveContentListener(arrayOf("image/*", "text/*"), object : android.view.OnReceiveContentListener {
                override fun onReceiveContent(v: View, payload: android.view.ContentInfo): android.view.ContentInfo? {
                    val clip = payload.clip
                    var handled = false
                    for (i in 0 until clip.itemCount) {
                        val u = clip.getItemAt(i).uri
                        if (u != null && (contentResolver.getType(u) ?: "").startsWith("image/")) { savePastedImage(u, et); handled = true }
                    }
                    return if (handled) null else payload
                }
            })
        } catch (e: Exception) { logError("paste", e.message ?: "listener") }
    }

    private fun savePastedImage(u: Uri, et: EditText) {
        try {
            val mime = contentResolver.getType(u) ?: "image/png"
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
            val f = File(pastedDir(), "image_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + "." + ext)
            contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            val at = et.selectionStart.coerceAtLeast(0)
            et.text.insert(at, f.path)
            toast(tr("Image pasted → saved in Files/Photos sent", "Imagem colada → salva em Files/Photos sent"))
        } catch (e: Exception) {
            val m = tr("Could not paste the image: ${e.message}", "Não foi possível colar a imagem: ${e.message}")
            toast(m); logError("paste", m)
        }
    }

    private var fileCb: android.webkit.ValueCallback<Array<Uri>>? = null

    /** Any photo you send through a site (upload button) is also copied to Files/Photos sent. */
    private fun savePhotoSent(u: Uri) {
        thread {
            try {
                if ((contentResolver.getType(u) ?: "").startsWith("image/").not()) return@thread
                val raw = try {
                    contentResolver.query(u, null, null, null, null)?.use { c ->
                        val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && ix >= 0) c.getString(ix) else null
                    }
                } catch (e: Exception) { null } ?: ("photo_" + System.currentTimeMillis() + ".jpg")
                var f = File(photosSentDir(), safeSeg(raw))
                var n = 1
                while (f.exists()) { f = File(photosSentDir(), safeSeg(raw.substringBeforeLast('.')) + "_" + n + (if (raw.contains('.')) "." + raw.substringAfterLast('.') else "")); n++ }
                contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            } catch (e: Exception) { logError("photos", e.message ?: "copy") }
        }
    }

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
        tb.addView(capBtn("🖱", false, 46) { toggleGlobalMouse() })
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
        rb("＋", 36) { addTab() }
        rb("🎨", 36, true) { showTemplates() }
        rb("📜", 36) { showSavedata() }
        rb("🔑", 36) { showApiKeys() }
        rb("👤", 36) { showProfile() }
        rb("⋯", 36) { showMenu() }
        w.addView(row, LinearLayout.LayoutParams(MATCH, dp(38)))

        body = FrameLayout(this)
        w.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        refreshTabs()
        showTab()
    }

    private fun showMenu() {
        val p = panel(tr("Menu", "Menu"), 0.5f)
        val items = listOf(
            tr("🖥  Servers", "🖥  Servidores") to { showServers() },
            tr("＋  New server", "＋  Novo servidor") to { createServerDialog() },
            tr("📂  Load file", "📂  Carregar arquivo") to { showLoad() },
            tr("📝  Data file", "📝  Arquivo de dados") to { showDados() },
            tr("📜  Savedata (recent commands)", "📜  Savedata (comandos recentes)") to { showSavedata() },
            tr("⚙  Settings", "⚙  Configurações") to { showSettings() },
            tr("ⓘ  Credits", "ⓘ  Créditos") to { showCredits() })
        items.chunked(2).forEach { pair ->
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            pair.forEach { (label, act) ->
                r.addView(pill(label, false) { p.close(); act() }.apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { topMargin = dp(8); rightMargin = dp(6) })
            }
            p.body.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        p.button(tr("Close", "Fechar"), true) { p.close() }
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
        out.setOnLongClickListener { out.setTextIsSelectable(true); false }
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
        enableImagePaste(et)
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
    @Volatile private var errCount = 0
    private fun err(t: TabData, msg: String) { errCount++; append(t, msg + "\n"); logError(t.name, msg) }
    private fun sayErr(msg: String) { sayCur(msg + "\n"); logError("server", msg) }

    // ---------- commands ----------
    private fun submit(et: EditText) {
        val t = tabs[cur]
        val c = et.text.toString().trim()
        et.setText("")
        hIdx = -1
        runIn(t, c)
    }

    private fun runIn(t: TabData, c: String) {
        append(t, "${short(t.cwd)} \$ $c\n")
        if (c.isNotEmpty()) { history.add(c); recordCmd(t, c) }
        exec(t, c)
    }

    // ---------- Savedata.wintext (Files folder): every recent command ----------
    private class SavedCmd(val server: Boolean, val tab: String, val cmd: String)
    private val recent = mutableListOf<SavedCmd>() // newest first
    private val SAVE_MAX = 300
    private val RE_SAVED = Regex("^\\[([NS])\\]\\s+(.*?)\\s+::\\s+(.*)$")
    private fun saveFile() = File(filesRoot(), "Savedata.wintext")
    private fun isSrvTab(t: TabData) = t.tpl.isNotEmpty() || t.srvName.isNotEmpty()

    private fun loadRecent() {
        recent.clear()
        try {
            val f = saveFile()
            if (f.exists()) f.readLines().forEach { l ->
                val m = RE_SAVED.matchEntire(l.trim()) ?: return@forEach
                recent.add(SavedCmd(m.groupValues[1] == "S", m.groupValues[2], m.groupValues[3]))
            }
        } catch (e: Exception) { }
    }

    private fun writeRecent() {
        try {
            val sb = StringBuilder()
            sb.append("WINTEXT 1\n# TermWin - Savedata.wintext (recent commands, newest first)\n")
            sb.append("# [N] = normal terminal, [S] = server tab.   Format: [N] tab :: command\n")
            sb.append("# Tap the Savedata button in TermWin to run a line again.\n\n[Commands]\n")
            recent.take(SAVE_MAX).forEach { sb.append(if (it.server) "[S] " else "[N] ").append(it.tab).append(" :: ").append(it.cmd).append('\n') }
            saveFile().writeText(sb.toString())
        } catch (e: Exception) { logError("savedata", e.message ?: "write") }
    }

    private fun recordCmd(t: TabData, c: String) {
        val line = c.replace('\n', ' ').trim()
        if (line.isEmpty()) return
        loadRecent() // keeps edits you made to the file by hand
        val srv = isSrvTab(t)
        recent.removeAll { it.server == srv && it.tab == t.name && it.cmd == line }
        recent.add(0, SavedCmd(srv, t.name, line))
        while (recent.size > SAVE_MAX) recent.removeAt(recent.size - 1)
        writeRecent()
    }

    /** Runs a saved command: normal ones in the normal terminal, server ones in the server tab. */
    private fun runSaved(sc: SavedCmd) {
        restore()
        val idx: Int
        if (sc.server) {
            idx = tabs.indexOfFirst { isSrvTab(it) && it.name.equals(sc.tab, true) }.takeIf { it >= 0 }
                ?: tabs.indexOfFirst { isSrvTab(it) && it.srvName.equals(sc.tab, true) }
            if (idx < 0) {
                val m = tr("Server tab '${sc.tab}' is not open. Open the server first, then tap the command again.", "A aba do servidor '${sc.tab}' não está aberta. Abra o servidor primeiro e toque no comando de novo.")
                toast(m); sayCur(m + "\n"); logError("savedata", m); return
            }
        } else {
            idx = tabs.indexOfFirst { !isSrvTab(it) && it.name.equals(sc.tab, true) }.takeIf { it >= 0 }
                ?: (if (!isSrvTab(tabs[cur.coerceIn(0, tabs.size - 1)])) cur else tabs.indexOfFirst { !isSrvTab(it) })
            if (idx < 0) { tabs.add(newTabData(nextTabName())); cur = tabs.size - 1; refreshTabs(); showTab(); runIn(tabs[cur], sc.cmd); return }
        }
        cur = idx
        refreshTabs(); showTab()
        runIn(tabs[idx], sc.cmd)
    }

    private fun showSavedata() {
        loadRecent()
        val p = panel("📜 Savedata.wintext", 0.8f)
        p.body.addView(tv(tr("Recent commands. Tap one to run it: normal ones run in the normal terminal, server ones run in their server.", "Comandos recentes. Toque em um para executar: os normais rodam no terminal normal, os de servidor rodam no servidor."), 12f, 0xFF9AA5B1.toInt()))
        p.body.addView(tv(short(saveFile().path), 11f, 0xFF6B7785.toInt()).apply { setPadding(0, dp(2), 0, dp(4)) })
        if (recent.isEmpty()) p.body.addView(tv(tr("(no commands yet)", "(nenhum comando ainda)"), 13f, 0xFFAAAAAA.toInt()).apply { setPadding(dp(4), dp(8), 0, dp(4)) })
        recent.toList().forEach { sc ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = pressBg(0xFF2A2A2A.toInt())
            row.setPadding(dp(12), dp(8), dp(12), dp(8))
            row.addView(tv(if (sc.server) "SERVER" else "NORMAL", 10f, if (sc.server) 0xFFFFB454.toInt() else GREEN).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, dp(10), 0) })
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(tv(sc.cmd, 14f, Color.WHITE).apply { typeface = Typeface.MONOSPACE })
            col.addView(tv(sc.tab, 10f, 0xFF8899AA.toInt()))
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(tv("▶", 14f, ACCENT))
            row.setOnClickListener { p.close(); runSaved(sc) }
            p.body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        }
        p.button(tr("Clear", "Limpar")) { recent.clear(); writeRecent(); p.close(); showSavedata() }
        p.button(tr("Edit file", "Editar arquivo")) { p.close(); writeRecent(); openEditor(tabs[cur.coerceIn(0, tabs.size - 1)], saveFile()) }
        p.button(tr("Close", "Fechar"), true) { p.close() }
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

    private fun exec(t: TabData, rawLine: String, single: Boolean = false) {
        if (rawLine.isEmpty()) return
        val line = expandAlias(rawLine)
        if (apkCmd(t, line)) { saveSoon(); return }
        if (!single) {
            val n = chainOps(line)
            if (n > MAX_AND) { err(t, tr("&& limit exceeded: at most $MAX_AND (you used $n). Nothing was run.", "limite de && excedido: no máximo $MAX_AND (você usou $n). Nada foi executado.")); return }
        }
        if (t.tpl.isNotEmpty()) { if (single) tplExec(t, line) else if (!line.contains("//") && splitChain(line).size > 1) chain(t, line) else tplExec(t, line); saveSoon(); return }
        if (!single && needsChain(line)) { chain(t, line); saveSoon(); return }
        if (fileCmd(t, line)) { saveSoon(); return }
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
        saveSoon()
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
                            val vis = publishToDownloads(out)
                            ui.post { append(t, tr("saved: ", "salvo: ") + short(out.path) + " (${out.length() / 1024} KiB)\n" + (if (vis != null) tr("also in: ", "também em: ") + vis + "\n" else tr("could not copy to Downloads (turn on all files access in Settings)\n", "não consegui copiar para Downloads (ligue o acesso a todos os arquivos em Configurações)\n"))) }
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

    // ---------- virtual mouse ----------
    private var gMouse: MouseLayer? = null
    private var homeMouseBtn: TextView? = null
    private val webMice = mutableListOf<MouseLayer>()
    private var topWeb: android.webkit.WebView? = null

    private fun mouseLabel() = if (prefs.getBoolean("mouse_all", false)) tr("🖱 ON", "🖱 LIG") else "🖱"
    private fun applyMouse() {
        val all = prefs.getBoolean("mouse_all", false)
        gMouse?.setOn(all)
        val w = prefs.getBoolean("mouse_on", true) && !all
        webMice.forEach { it.setOn(w) }
        homeMouseBtn?.apply {
            text = mouseLabel()
            textSize = 11f
            background = rounded(if (all) ACCENT else 0xCC2D2D2D.toInt(), dp(12), if (all) 0 else 0xFF454545.toInt())
            setPadding(dp(9), dp(3), dp(9), dp(3))
            setTextColor(if (all) Color.BLACK else Color.WHITE)
        }
    }
    private fun toggleGlobalMouse() {
        val on = !prefs.getBoolean("mouse_all", false)
        prefs.edit().putBoolean("mouse_all", on).apply()
        applyMouse()
        toast(if (on) tr("Mouse on everywhere — drag to move, tap to click", "Mouse ligado em tudo — arraste para mover, toque para clicar")
              else tr("Mouse off — touch the screen directly", "Mouse desligado — toque direto na tela"))
    }

    /** Virtual mouse (yellow pointer). The finger works like a touchpad: move = cursor (with speed acceleration),
     *  tap = click, tap + hold + move = drag, two fingers = scroll. Real touch events are sent to [target] at the cursor.
     *  global = true: covers the whole app (native buttons too), not only a web page. */
    inner class MouseLayer(private val target: View, private val global: Boolean = false) : View(this@MainActivity) {
        private val dn = resources.displayMetrics.density
        private fun bmp(n: String) = assets.open(n).use { android.graphics.BitmapFactory.decodeStream(it) }
        private val arrow = bmp("cursor_arrow.png")
        private val hand = bmp("cursor_hand.png")
        private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val shade = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = android.graphics.PorterDuffColorFilter(0x66000000, android.graphics.PorterDuff.Mode.SRC_IN) }
        private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f * dn }
        private var cx = 0f; private var cy = 0f; private var ready = false
        private var isHand = false; private var lastProbe = 0L
        private var lx = 0f; private var ly = 0f; private var lt = 0L; private var px0 = 0f; private var py0 = 0f
        private var downT = 0L; private var moved = false; private var lastTap = 0L
        private var dragging = false; private var scrolling = false
        private var sT = 0L; private var ax0 = 0f; private var ay0 = 0f; private var ax = 0f; private var ay = 0f
        private var rippleT = 0L

        fun setOn(on: Boolean) { visibility = if (on) View.VISIBLE else View.GONE }

        private fun fire(action: Int, t0: Long, x: Float, y: Float) {
            val ev = MotionEvent.obtain(t0, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            ev.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            target.dispatchTouchEvent(ev); ev.recycle()
        }

        private fun click() {
            val t = android.os.SystemClock.uptimeMillis()
            val x = cx; val y = cy
            rippleT = t; invalidate()
            fire(MotionEvent.ACTION_DOWN, t, x, y)
            postDelayed({ fire(MotionEvent.ACTION_UP, t, x, y) }, 35)
        }

        /** Cursor speed -> gain: slow = precise, fast = crosses the screen. */
        private fun step(x: Float, y: Float, t: Long) {
            val dx = x - lx; val dy = y - ly
            val dt = maxOf(4L, t - lt).toFloat()
            val v = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat() / dn / dt
            val gain = 0.95f + minOf(v, 2.4f) * 0.9f
            cx = (cx + dx * gain).coerceIn(0f, width - 1f)
            cy = (cy + dy * gain).coerceIn(0f, height - 1f)
            lx = x; ly = y; lt = t
        }

        private fun hitClickable(v: View, x: Float, y: Float): Boolean {
            if (v !is ViewGroup) return false
            for (i in v.childCount - 1 downTo 0) {
                val c = v.getChildAt(i)
                if (c === this || c.visibility != View.VISIBLE) continue
                val xx = x + v.scrollX - c.x; val yy = y + v.scrollY - c.y
                if (xx < 0 || yy < 0 || xx > c.width || yy > c.height) continue
                if (c is android.webkit.WebView) return false
                if (c is ViewGroup) { if (hitClickable(c, xx, yy)) return true; if (c.isClickable && c.childCount == 0) return true }
                else if (c.isClickable) return true
            }
            return false
        }

        private fun probe() {
            val n = android.os.SystemClock.uptimeMillis()
            if (n - lastProbe < 60) return
            lastProbe = n
            var web: android.webkit.WebView? = target as? android.webkit.WebView
            var ox = 0f; var oy = 0f
            if (global) {
                val w = topWeb
                web = null
                if (w != null && w.isShown) {
                    val a = IntArray(2); val b = IntArray(2); w.getLocationOnScreen(a); getLocationOnScreen(b)
                    ox = (a[0] - b[0]).toFloat(); oy = (a[1] - b[1]).toFloat()
                    if (cx >= ox && cx <= ox + w.width && cy >= oy && cy <= oy + w.height) web = w
                }
                if (web == null) { val h = hitClickable(target, cx, cy); if (h != isHand) { isHand = h; invalidate() }; return }
            }
            val w2 = web ?: return
            val js = "(function(){var r=window.devicePixelRatio||1,e=document.elementFromPoint(" + (cx - ox).toInt() + "/r," + (cy - oy).toInt() + "/r);" +
                "for(var i=0;e&&i<7;i++,e=e.parentElement){var c=getComputedStyle(e).cursor,g=e.tagName;" +
                "if(c=='pointer'||g=='A'||g=='BUTTON'||g=='SELECT'||g=='SUMMARY')return 1}return 0})()"
            try { w2.evaluateJavascript(js) { v -> val h = v == "1"; if (h != isHand) { isHand = h; invalidate() } } } catch (e: Exception) { }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!ready) { cx = width / 2f; cy = height / 2f; ready = true }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lx = e.x; ly = e.y; lt = e.eventTime; px0 = e.x; py0 = e.y; moved = false; downT = e.eventTime
                    dragging = lastTap > 0 && e.eventTime - lastTap < 300
                    if (dragging) { sT = android.os.SystemClock.uptimeMillis(); fire(MotionEvent.ACTION_DOWN, sT, cx, cy) }
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2 && !dragging) {
                    scrolling = true; moved = true
                    ax0 = (e.getX(0) + e.getX(1)) / 2; ay0 = (e.getY(0) + e.getY(1)) / 2; ax = ax0; ay = ay0
                    sT = android.os.SystemClock.uptimeMillis(); fire(MotionEvent.ACTION_DOWN, sT, cx, cy)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scrolling && e.pointerCount >= 2) {
                        ax = (e.getX(0) + e.getX(1)) / 2; ay = (e.getY(0) + e.getY(1)) / 2
                        fire(MotionEvent.ACTION_MOVE, sT, cx + (ax - ax0), cy + (ay - ay0))
                    } else if (!scrolling) {
                        if (Math.hypot((e.x - px0).toDouble(), (e.y - py0).toDouble()) > 7 * dn) moved = true
                        for (h in 0 until e.historySize) step(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalEventTime(h))
                        step(e.x, e.y, e.eventTime)
                        if (dragging) fire(MotionEvent.ACTION_MOVE, sT, cx, cy)
                        postInvalidateOnAnimation(); probe()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> if (scrolling) {
                    fire(MotionEvent.ACTION_UP, sT, cx + (ax - ax0), cy + (ay - ay0)); scrolling = false
                    val i = if (e.actionIndex == 0) 1 else 0
                    lx = e.getX(i); ly = e.getY(i); lt = e.eventTime
                }
                MotionEvent.ACTION_UP -> {
                    if (scrolling) { fire(MotionEvent.ACTION_UP, sT, cx + (ax - ax0), cy + (ay - ay0)); scrolling = false }
                    else if (dragging) { fire(MotionEvent.ACTION_UP, sT, cx, cy); dragging = false; lastTap = 0 }
                    else if (!moved && e.eventTime - downT < 280) { click(); lastTap = e.eventTime }
                    else lastTap = 0
                    probe()
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (scrolling || dragging) fire(MotionEvent.ACTION_CANCEL, sT, cx, cy)
                    scrolling = false; dragging = false
                }
            }
            return true
        }

        override fun onDraw(c: android.graphics.Canvas) {
            if (!ready) return
            val b = if (isHand) hand else arrow
            val hx = if (isHand) 0.44f else 0.12f; val hy = 0.07f
            val s = 32f * dn / b.height
            val rt = android.os.SystemClock.uptimeMillis() - rippleT
            if (rt in 0..240) {
                val f = rt / 240f
                ring.color = android.graphics.Color.argb((200 * (1 - f)).toInt(), 244, 196, 48)
                c.drawCircle(cx, cy, (6f + 22f * f) * dn, ring)
                postInvalidateOnAnimation()
            }
            c.save(); c.translate(cx - hx * b.width * s, cy - hy * b.height * s); c.scale(s, s)
            c.drawBitmap(b, 1.8f / s * dn, 2.2f / s * dn, shade)
            c.drawBitmap(b, 0f, 0f, paint); c.restore()
        }
    }

    /** Opens the page inside a Windows-style window (WebView) instead of the phone's browser. */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun openWebWindow(title: String, url: String, yt: Boolean = false, ai: Boolean = false, gh: Boolean = false) {
        val dm = resources.displayMetrics
        val ov = FrameLayout(this)
        ov.setBackgroundColor(0x99000000.toInt())
        ov.isClickable = true
        ov.elevation = dp(40).toFloat()
        ov.outlineProvider = null
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(0xFF202020.toInt(), dp(10), 0xFF3A3A3A.toInt())
        card.clipToOutline = false
        val wv = android.webkit.WebView(this)
        lateinit var p: Panel
        var mouse: MouseLayer? = null
        val tb = LinearLayout(this)
        tb.setBackgroundColor(TITLE)
        tb.addView(tv("   ▣   $title — $url", 13f, Color.WHITE).apply { gravity = Gravity.CENTER_VERTICAL; setSingleLine(true) }, LinearLayout.LayoutParams(0, dp(40), 1f))
        if (yt) tb.addView(capBtn("＋", false, 46) { wv.evaluateJavascript("if(window.mk)mk()", null) })
        if (ai) tb.addView(capBtn("🔑", false, 46) { showProviderKey() })
        if (gh) tb.addView(capBtn("💾", false, 46) { if (saveGhTokenFromClipboard()) p.close() })
        tb.addView(capBtn("🖱", false, 46) {
            if (prefs.getBoolean("mouse_all", false)) { toggleGlobalMouse() }
            else {
                val on = !prefs.getBoolean("mouse_on", true)
                prefs.edit().putBoolean("mouse_on", on).apply(); applyMouse()
                toast(if (on) tr("Mouse on", "Mouse ligado") else tr("Mouse off — touch the page directly", "Mouse desligado — toque direto na página"))
            }
        })
        tb.addView(capBtn("⟳", false, 46) { wv.reload() })
        tb.addView(capBtn("✕", true, 46) { p.close() })
        card.addView(tb, LinearLayout.LayoutParams(MATCH, dp(40)))
        wv.setBackgroundColor(Color.BLACK)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        wv.settings.allowFileAccess = true
        wv.settings.allowContentAccess = true
        wv.addJavascriptInterface(TwBridge(wv), "TW")
        if (yt) pubWv = wv
        wv.webViewClient = android.webkit.WebViewClient()
        wv.settings.setSupportMultipleWindows(true)
        wv.addJavascriptInterface(TwDl(), "TWDL")
        wv.webChromeClient = object : android.webkit.WebChromeClient() {
            // the site's "add file" / upload button (e.g. attaching a file to an AI chat) opens the phone's file picker
            @Suppress("DEPRECATION")
            override fun onShowFileChooser(view: android.webkit.WebView, cb: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                val i = try { params.createIntent() } catch (e: Exception) { Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE) }
                try { startActivityForResult(i, 81) }
                catch (e: Exception) { fileCb = null; cb.onReceiveValue(null); toast(tr("No file picker found", "Nenhum seletor de arquivos encontrado")) }
                return true
            }
            // links with target=_blank open in this same window (so their downloads work too)
            override fun onCreateWindow(view: android.webkit.WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                val tmp = android.webkit.WebView(this@MainActivity)
                tmp.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageStarted(v: android.webkit.WebView, u: String, f: android.graphics.Bitmap?) { if (u != "about:blank") { view.loadUrl(u); v.stopLoading(); v.destroy() } }
                }
                (resultMsg.obj as android.webkit.WebView.WebViewTransport).webView = tmp
                resultMsg.sendToTarget()
                return true
            }
        }
        // any file link/button on any site downloads to the phone's Downloads folder (no outside browser)
        wv.setDownloadListener { dlUrl, ua, cd, mime, _ -> startWebDownload(wv, dlUrl, ua, cd, mime) }
        val holder = FrameLayout(this)
        holder.addView(wv, FrameLayout.LayoutParams(MATCH, MATCH))
        val ml = MouseLayer(wv); mouse = ml
        webMice.add(ml); topWeb = wv; applyMouse()
        holder.addView(ml, FrameLayout.LayoutParams(MATCH, MATCH))
        card.addView(holder, LinearLayout.LayoutParams(MATCH, 0, 1f))
        ov.addView(card, FrameLayout.LayoutParams((dm.widthPixels * 0.94).toInt(), (dm.heightPixels * 0.92).toInt(), Gravity.CENTER))
        root.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        p = Panel(ov, LinearLayout(this), LinearLayout(this))
        p.onClose = { webMice.remove(ml); if (topWeb === wv) topWeb = null; if (pubWv === wv) pubWv = null; if (voiceWv === wv) { stopVoice(); voiceWv = null }; try { wv.stopLoading(); wv.loadUrl("about:blank"); wv.destroy() } catch (e: Exception) { } }
        panels.add(p)
        wv.loadUrl(url)
    }

    // ---------- downloads from the in-app web windows ----------
    private fun downloadsDir() = File(dataDir(), "Downloaded").apply { mkdirs() }
    @Volatile private var blobOkUntil = 0L
    @Volatile private var blobName = ""

    /** Receives a blob: file that WE asked the page for (a page cannot write files on its own). */
    inner class TwDl {
        @android.webkit.JavascriptInterface fun save(dataUrl: String) {
            if (System.currentTimeMillis() > blobOkUntil) return
            blobOkUntil = 0L
            try {
                val b64 = dataUrl.substringAfter("base64,", "")
                if (b64.isNotEmpty()) saveBytes(blobName, android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            } catch (e: Exception) { ui.post { toast("download: ${e.message}") } }
        }
    }

    private fun dlTargetDir(): File {
        return downloadsDir() // every download goes to the "Downloaded" folder next to Files
    }

    private fun uniqueDlFile(name: String): File {
        val dir = dlTargetDir(); val safe = safeSeg(name)
        val base = safe.substringBeforeLast('.', safe); val ext = if (safe.contains('.')) "." + safe.substringAfterLast('.') else ""
        var f = File(dir, safe); var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    /** Copies a finished download into the phone's public Downloads folder (Download/Downloaded), where the Files app can see it.
     *  Returns the visible path, or null when it really could not be put there (nothing is ever deleted or overwritten). */
    private fun publishToDownloads(f: File): String? {
        val ext = f.extension.lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        // 1) direct copy: works on old Androids and when "all files access" is on; shows up in Files right away
        if (Build.VERSION.SDK_INT < 29 || storageOk()) {
            try {
                val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Downloaded").apply { mkdirs() }
                val dotExt = if (ext.isNotEmpty()) "." + f.extension else ""
                var out = File(d, f.name); var i = 1
                while (out.exists()) { out = File(d, f.nameWithoutExtension + " ($i)" + dotExt); i++ }
                f.copyTo(out, false)
                if (out.length() == f.length()) {
                    try { android.media.MediaScannerConnection.scanFile(this, arrayOf(out.path), arrayOf(mime), null) } catch (e: Exception) { }
                    return "Download/Downloaded/" + out.name
                }
                out.delete()
            } catch (e: Exception) { logError("download", "direct copy: $e") }
        }
        // 2) MediaStore (no permission needed on Android 10+); try Download/Downloaded, then plain Download
        if (Build.VERSION.SDK_INT >= 29) {
            for (rel in listOf("Download/Downloaded", "Download")) {
                var uri: Uri? = null
                try {
                    val cv = android.content.ContentValues()
                    cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                    cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                    cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, rel)
                    cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                    uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: continue
                    val ok = contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { i -> i.copyTo(o) }; true } ?: false
                    if (!ok) { contentResolver.delete(uri, null, null); continue }
                    val done = android.content.ContentValues()
                    done.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, done, null, null)
                    var shown = rel.trimEnd('/') + "/" + f.name
                    contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, android.provider.MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
                        if (c.moveToFirst()) shown = (c.getString(1) ?: rel).trimEnd('/') + "/" + c.getString(0)
                    }
                    return shown
                } catch (e: Exception) {
                    logError("download", "mediastore $rel: $e")
                    try { uri?.let { contentResolver.delete(it, null, null) } } catch (x: Exception) { }
                }
            }
        }
        return null
    }

    private fun dlDone(f: File) {
        val vis = publishToDownloads(f)
        ui.post {
            if (vis != null) {
                toast(tr("downloaded: ${f.name}", "baixado: ${f.name}"))
                notifyDone(tr("✔ Download finished", "✔ Download concluído"), f.name + " — " + vis, true)
            } else {
                toast(tr("could not copy to Downloads. Turn on \"all files access\" in Settings and download again.", "não consegui copiar para Downloads. Ligue \"acesso a todos os arquivos\" em Configurações e baixe de novo."))
                notifyDone(tr("⚠ Download not in Downloads", "⚠ Download fora da pasta Downloads"), f.name + " — " + tr("saved only inside the app (Files/Downloaded)", "salvo só dentro do app (Files/Downloaded)"), false)
            }
        }
    }

    private fun saveBytes(name: String, bytes: ByteArray) {
        val f = uniqueDlFile(name)
        f.writeBytes(bytes)
        dlDone(f)
    }

    private fun startWebDownload(wv: android.webkit.WebView, url: String, ua: String?, cd: String?, mime: String?) {
        val name = android.webkit.URLUtil.guessFileName(url, cd, mime)
        if (url.startsWith("data:")) {
            thread { try { saveBytes(name, android.util.Base64.decode(url.substringAfter("base64,"), android.util.Base64.DEFAULT)) } catch (e: Exception) { ui.post { toast("download: ${e.message}") } } }
            return
        }
        if (url.startsWith("blob:")) {
            blobName = name; blobOkUntil = System.currentTimeMillis() + 60_000
            toast(tr("downloading $name…", "baixando $name…"))
            val u = url.replace("'", "%27")
            wv.evaluateJavascript("(function(){fetch('$u').then(function(r){return r.blob()}).then(function(b){var f=new FileReader();f.onload=function(){TWDL.save(f.result)};f.readAsDataURL(b)})})()", null)
            return
        }
        val cookie = try { android.webkit.CookieManager.getInstance().getCookie(url) } catch (e: Exception) { null }
        toast(tr("downloading $name…", "baixando $name…"))
        fallbackDownload(url, ua, cookie, name) // always our own downloader, so the file lands in the "Downloaded" folder
    }

    /** Own downloader (follows redirects, sends the page's cookies) used when the system DownloadManager is not allowed to write. */
    private fun fallbackDownload(url: String, ua: String?, cookie: String?, name: String) {
        thread {
            try {
                var u = url; var cn: java.net.HttpURLConnection? = null
                for (hop in 0..8) {
                    val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                    c.instanceFollowRedirects = false; c.connectTimeout = 15000; c.readTimeout = 30000
                    if (!ua.isNullOrEmpty()) c.setRequestProperty("User-Agent", ua)
                    if (hop == 0 && !cookie.isNullOrEmpty()) c.setRequestProperty("Cookie", cookie)
                    val code = c.responseCode
                    val loc = c.getHeaderField("Location")
                    if (code in 301..308 && loc != null) { u = java.net.URL(java.net.URL(u), loc).toString(); c.disconnect(); continue }
                    cn = c; break
                }
                val c = cn ?: throw Exception("too many redirects")
                if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}")
                val cdh = c.getHeaderField("Content-Disposition")
                val nm = if (cdh != null) android.webkit.URLUtil.guessFileName(u, cdh, c.contentType) else name
                val f = uniqueDlFile(nm)
                c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                dlDone(f)
            } catch (e: Exception) { ui.post { toast(tr("download failed: ${e.message}", "falha no download: ${e.message}")) } }
        }
    }

    private val FILE_EXT = setOf("html", "htm", "css", "js", "json", "py", "txt", "md", "kt", "java", "xml", "png", "jpg", "jpeg", "gif", "svg", "gd", "lua", "cs", "cpp", "c", "h",
        "sh", "yml", "yaml", "toml", "ini", "csv", "zip", "mp3", "mp4", "pdf", "tscn", "tres", "gradle", "kts", "winv", "winser", "wintext")

    /** open//github.com/user/repo or open//https://site.com/page -> the address to open, or null when it is a project file (open//Proj//index.html). */
    private fun webUrlOf(t: TabData, raw: String): String? {
        val a = raw.trim().removeSurrounding("\"").removeSurrounding("'")
        if (a.isEmpty() || a.any { it.isWhitespace() }) return null
        val low = a.lowercase()
        if (low.startsWith("http://") || low.startsWith("https://")) return a
        if (low.startsWith("localhost") || Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$").matches(low)) return "http://$a"
        if (low.startsWith("www.")) return "https://$a"
        val host = low.substringBefore('/').substringBefore('?').substringBefore(':')
        val tld = host.substringAfterLast('.')
        if (Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$").matches(host) && tld.length in 2..24 && tld.all { it in 'a'..'z' } && tld !in FILE_EXT) {
            if (resolvePath(t, a.substringBefore('/'))?.exists() == true) return null
            return "https://$a"
        }
        return null
    }

    private fun openSiteWindow(t: TabData, url: String) {
        val host = try { Uri.parse(url).host ?: url } catch (e: Exception) { url }
        append(t, tr("opening $url in a window (you can download files here)\n", "abrindo $url numa janela (dá para baixar arquivos aqui)\n"))
        openWebWindow(host, url)
    }

    private fun tplHelp(label: String, name: String, s: Srv): String {
        val st = if (s.running) "ON" else "OFF"
        val en = listOf("help" to "this help", "play $name" to "turn the server on and open the page",
            "port 4089" to "set the port (then open localhost:4089)", "turn off" to "turn the server off",
            "status" to "show state and port", "creat//Proj//html//index.html" to "create a file in Files/Projects and edit it", "open//Proj//index.html" to "open a file in the editor", "copy Folder" to "copy a folder from the phone to Files/Copied", "cd path" to "change folder (any path)", "clear" to "clear the screen", "ct file.txt" to "create a file in Files/Files Created", "exit" to "close this tab")
        val br = listOf("help" to "esta ajuda", "play $name" to "liga o servidor e abre a página",
            "port 4089" to "define a porta (depois abra localhost:4089)", "turn off" to "desliga o servidor",
            "status" to "mostra estado e porta", "creat//Proj//html//index.html" to "cria um arquivo em Files/Projects e edita", "open//Proj//index.html" to "abre um arquivo no editor", "copy Pasta" to "copia uma pasta do celular para Files/Copied", "cd caminho" to "muda de pasta (qualquer caminho)", "clear" to "limpa a tela", "ct arquivo.txt" to "cria um arquivo em Files/Files Created", "exit" to "fecha esta aba")
        val rows = (if (pt) br else en).joinToString("") { "  " + it.first.padEnd(18) + it.second + "\n" }
        val yt = if (name != "youtube") "" else if (pt) "  publicar          escolhe um vídeo do aparelho e publica (também o botão ＋)\n  post seu texto    publica um post de texto no seu canal (ou ＋ → Post)\n  player//test      lista os vídeos como player1, player2…\n  player//test//player2//youtube   toca o vídeo 2 SEM nenhuma conta logada\n  (entre antes com o botão 👤 Perfil)\n" else "  publish           pick a video from your phone and publish it (or the ＋ button)\n  post your text    publish a text post on your channel (or ＋ → Post)\n  player//test      list videos as player1, player2…\n  player//test//player2//youtube   play video 2 with NO account signed in\n  (sign in first with the 👤 Profile button)\n"
        val ai = if (name != "ai") yt else if (pt) "  ia//pergunta//app   gera resposta ou código (app é opcional)\n  ia//faça um jogo//GDScript   exemplo (também: ai// e aí//)\n  ia key SUA_CHAVE   API do provedor (Gemini/Groq/Anthropic/OpenAI…; ia key limpar apaga)\n  ia model NOME      troca o modelo\n"
            else "  ai//question//app   answer or code (app is optional)\n  ai//make a game//GDScript   example (also: ia// and aí//)\n  ai key YOUR_KEY   provider API (Gemini/Groq/Anthropic/OpenAI…; ai key clear deletes it)\n  ai model NAME      change the model\n"
        return tr("$label server — $st, port ${s.port}\n", "Servidor $label — $st, porta ${s.port}\n") + rows + ai
    }


    // ---------- offline mini-AI (used when there is no provider key) ----------
    private val OFFLINE: Map<String, Map<String, String>> = mapOf(
        "hello" to mapOf(
            "py" to "print(\"Hello, world!\")",
            "gd" to "extends Node\n\nfunc _ready():\n    print(\"Hello, world!\")",
            "lua" to "print(\"Hello, world!\")",
            "js" to "console.log(\"Hello, world!\");"),
        "fib" to mapOf(
            "py" to "def fib(n):\n    a, b = 0, 1\n    for _ in range(n):\n        print(a)\n        a, b = b, a + b\n\nfib(10)",
            "gd" to "func fib(n):\n    var a = 0\n    var b = 1\n    for i in n:\n        print(a)\n        var t = a + b\n        a = b\n        b = t",
            "lua" to "local function fib(n)\n  local a, b = 0, 1\n  for i = 1, n do\n    print(a)\n    a, b = b, a + b\n  end\nend\nfib(10)",
            "js" to "function fib(n) {\n  let a = 0, b = 1;\n  for (let i = 0; i < n; i++) {\n    console.log(a);\n    [a, b] = [b, a + b];\n  }\n}\nfib(10);"),
        "sort" to mapOf(
            "py" to "def selection_sort(a):\n    for i in range(len(a)):\n        m = i\n        for j in range(i + 1, len(a)):\n            if a[j] < a[m]:\n                m = j\n        a[i], a[m] = a[m], a[i]\n    return a\n\nprint(selection_sort([5, 2, 9, 1, 7]))",
            "gd" to "func selection_sort(a: Array) -> Array:\n    for i in a.size():\n        var m = i\n        for j in range(i + 1, a.size()):\n            if a[j] < a[m]:\n                m = j\n        var t = a[i]\n        a[i] = a[m]\n        a[m] = t\n    return a",
            "lua" to "local function selectionSort(a)\n  for i = 1, #a do\n    local m = i\n    for j = i + 1, #a do\n      if a[j] < a[m] then m = j end\n    end\n    a[i], a[m] = a[m], a[i]\n  end\n  return a\nend",
            "js" to "function selectionSort(a) {\n  for (let i = 0; i < a.length; i++) {\n    let m = i;\n    for (let j = i + 1; j < a.length; j++) if (a[j] < a[m]) m = j;\n    [a[i], a[m]] = [a[m], a[i]];\n  }\n  return a;\n}"),
        "prime" to mapOf(
            "py" to "def is_prime(n):\n    if n < 2:\n        return False\n    for i in range(2, int(n ** 0.5) + 1):\n        if n % i == 0:\n            return False\n    return True",
            "gd" to "func is_prime(n: int) -> bool:\n    if n < 2:\n        return false\n    for i in range(2, int(sqrt(n)) + 1):\n        if n % i == 0:\n            return false\n    return true",
            "lua" to "local function isPrime(n)\n  if n < 2 then return false end\n  for i = 2, math.floor(math.sqrt(n)) do\n    if n % i == 0 then return false end\n  end\n  return true\nend",
            "js" to "function isPrime(n) {\n  if (n < 2) return false;\n  for (let i = 2; i * i <= n; i++) if (n % i === 0) return false;\n  return true;\n}"),
        "jump" to mapOf(
            "py" to "# pygame: gravity + jump\nvy += 0.6            # gravity every frame\ny += vy\nif y >= ground:\n    y = ground\n    vy = 0\n    on_ground = True\nif keys[pygame.K_SPACE] and on_ground:\n    vy = -12\n    on_ground = False",
            "gd" to "extends CharacterBody2D\n\nconst SPEED = 300.0\nconst JUMP = -450.0\nvar gravity = ProjectSettings.get_setting(\"physics/2d/default_gravity\")\n\nfunc _physics_process(delta):\n    if not is_on_floor():\n        velocity.y += gravity * delta\n    if Input.is_action_just_pressed(\"ui_accept\") and is_on_floor():\n        velocity.y = JUMP\n    velocity.x = Input.get_axis(\"ui_left\", \"ui_right\") * SPEED\n    move_and_slide()",
            "lua" to "local UIS = game:GetService(\"UserInputService\")\nlocal hum = game.Players.LocalPlayer.Character:WaitForChild(\"Humanoid\")\nUIS.JumpRequest:Connect(function()\n  hum:ChangeState(Enum.HumanoidStateType.Jumping)\nend)",
            "js" to "let y = 0, vy = 0;\naddEventListener('keydown', e => { if (e.code === 'Space' && y === 0) vy = 12; });\nsetInterval(() => { y = Math.max(0, y + vy); vy = y > 0 ? vy - 0.6 : 0; }, 16);"),
        "move" to mapOf(
            "py" to "# pygame: move with the arrow keys\nkeys = pygame.key.get_pressed()\nif keys[pygame.K_LEFT]:  x -= 5\nif keys[pygame.K_RIGHT]: x += 5\nif keys[pygame.K_UP]:    y -= 5\nif keys[pygame.K_DOWN]:  y += 5",
            "gd" to "extends CharacterBody2D\n\nconst SPEED = 300.0\n\nfunc _physics_process(delta):\n    var dir = Input.get_vector(\"ui_left\", \"ui_right\", \"ui_up\", \"ui_down\")\n    velocity = dir * SPEED\n    move_and_slide()",
            "lua" to "local part = script.Parent\nwhile true do\n  part.Position = part.Position + Vector3.new(0, 0, 0.2)\n  task.wait(0.03)\nend",
            "js" to "let x = 0, y = 0;\naddEventListener('keydown', e => {\n  if (e.key === 'ArrowLeft') x -= 5;\n  if (e.key === 'ArrowRight') x += 5;\n  if (e.key === 'ArrowUp') y -= 5;\n  if (e.key === 'ArrowDown') y += 5;\n});"),
        "count" to mapOf(
            "py" to "for i in range(1, 11):\n    print(i)",
            "gd" to "func _ready():\n    for i in range(1, 11):\n        print(i)",
            "lua" to "for i = 1, 10 do\n  print(i)\nend",
            "js" to "for (let i = 1; i <= 10; i++) console.log(i);"),
        "fact" to mapOf(
            "py" to "def fact(n):\n    r = 1\n    for i in range(2, n + 1):\n        r *= i\n    return r\n\nprint(fact(5))",
            "gd" to "func fact(n: int) -> int:\n    var r = 1\n    for i in range(2, n + 1):\n        r *= i\n    return r",
            "lua" to "local function fact(n)\n  local r = 1\n  for i = 2, n do r = r * i end\n  return r\nend\nprint(fact(5))",
            "js" to "function fact(n) {\n  let r = 1;\n  for (let i = 2; i <= n; i++) r *= i;\n  return r;\n}\nconsole.log(fact(5));"),
        "rev" to mapOf(
            "py" to "def reverse(t):\n    return t[::-1]\n\nprint(reverse(\"TermWin\"))",
            "gd" to "func reverse(t: String) -> String:\n    var r = \"\"\n    for i in range(t.length() - 1, -1, -1):\n        r += t[i]\n    return r",
            "lua" to "local function reverse(t)\n  return t:reverse()\nend\nprint(reverse(\"TermWin\"))",
            "js" to "const reverse = t => t.split(\"\").reverse().join(\"\");\nconsole.log(reverse(\"TermWin\"));"),
        "pal" to mapOf(
            "py" to "def is_palindrome(t):\n    t = \"\".join(c for c in t.lower() if c.isalnum())\n    return t == t[::-1]\n\nprint(is_palindrome(\"Arara\"))",
            "gd" to "func is_palindrome(t: String) -> bool:\n    t = t.to_lower()\n    for i in t.length() / 2:\n        if t[i] != t[t.length() - 1 - i]:\n            return false\n    return true",
            "lua" to "local function isPalindrome(t)\n  t = t:lower():gsub(\"%W\", \"\")\n  return t == t:reverse()\nend\nprint(isPalindrome(\"Arara\"))",
            "js" to "const isPalindrome = t => {\n  t = t.toLowerCase().replace(/[^a-z0-9]/g, \"\");\n  return t === [...t].reverse().join(\"\");\n};\nconsole.log(isPalindrome(\"Arara\"));"),
        "bsearch" to mapOf(
            "py" to "def binary_search(a, x):\n    lo, hi = 0, len(a) - 1\n    while lo <= hi:\n        mid = (lo + hi) // 2\n        if a[mid] == x:\n            return mid\n        if a[mid] < x:\n            lo = mid + 1\n        else:\n            hi = mid - 1\n    return -1",
            "gd" to "func binary_search(a: Array, x) -> int:\n    var lo = 0\n    var hi = a.size() - 1\n    while lo <= hi:\n        var mid = (lo + hi) / 2\n        if a[mid] == x:\n            return mid\n        if a[mid] < x:\n            lo = mid + 1\n        else:\n            hi = mid - 1\n    return -1",
            "lua" to "local function binarySearch(a, x)\n  local lo, hi = 1, #a\n  while lo <= hi do\n    local mid = (lo + hi) // 2\n    if a[mid] == x then return mid end\n    if a[mid] < x then lo = mid + 1 else hi = mid - 1 end\n  end\n  return -1\nend",
            "js" to "function binarySearch(a, x) {\n  let lo = 0, hi = a.length - 1;\n  while (lo <= hi) {\n    const mid = (lo + hi) >> 1;\n    if (a[mid] === x) return mid;\n    if (a[mid] < x) lo = mid + 1; else hi = mid - 1;\n  }\n  return -1;\n}"),
        "bubble" to mapOf(
            "py" to "def bubble_sort(a):\n    for i in range(len(a)):\n        for j in range(len(a) - i - 1):\n            if a[j] > a[j + 1]:\n                a[j], a[j + 1] = a[j + 1], a[j]\n    return a",
            "gd" to "func bubble_sort(a: Array) -> Array:\n    for i in a.size():\n        for j in range(a.size() - i - 1):\n            if a[j] > a[j + 1]:\n                var t = a[j]\n                a[j] = a[j + 1]\n                a[j + 1] = t\n    return a",
            "lua" to "local function bubbleSort(a)\n  for i = 1, #a do\n    for j = 1, #a - i do\n      if a[j] > a[j + 1] then a[j], a[j + 1] = a[j + 1], a[j] end\n    end\n  end\n  return a\nend",
            "js" to "function bubbleSort(a) {\n  for (let i = 0; i < a.length; i++)\n    for (let j = 0; j < a.length - i - 1; j++)\n      if (a[j] > a[j + 1]) [a[j], a[j + 1]] = [a[j + 1], a[j]];\n  return a;\n}"),
        "rand" to mapOf(
            "py" to "import random\n\nprint(random.randint(1, 100))",
            "gd" to "func _ready():\n    randomize()\n    print(randi_range(1, 100))",
            "lua" to "math.randomseed(os.time())\nprint(math.random(1, 100))",
            "js" to "const n = Math.floor(Math.random() * 100) + 1;\nconsole.log(n);"),
        "save" to mapOf(
            "py" to "import json\n\ndata = {\"nome\": \"Ana\", \"pontos\": 10}\nwith open(\"save.json\", \"w\") as f:\n    json.dump(data, f)\nwith open(\"save.json\") as f:\n    print(json.load(f))",
            "gd" to "func save_game(data: Dictionary):\n    var f = FileAccess.open(\"user://save.json\", FileAccess.WRITE)\n    f.store_string(JSON.stringify(data))\n\nfunc load_game() -> Dictionary:\n    if not FileAccess.file_exists(\"user://save.json\"):\n        return {}\n    return JSON.parse_string(FileAccess.get_file_as_string(\"user://save.json\"))",
            "lua" to "local HttpService = game:GetService(\"HttpService\")\nlocal DS = game:GetService(\"DataStoreService\"):GetDataStore(\"Save\")\nlocal function save(player, data)\n  DS:SetAsync(player.UserId, HttpService:JSONEncode(data))\nend",
            "js" to "localStorage.setItem(\"save\", JSON.stringify({ nome: \"Ana\", pontos: 10 }));\nconsole.log(JSON.parse(localStorage.getItem(\"save\")));"),
        "timer" to mapOf(
            "py" to "import time\n\nfor i in range(5, 0, -1):\n    print(i)\n    time.sleep(1)\nprint(\"Pronto!\")",
            "gd" to "func _ready():\n    var t = Timer.new()\n    t.wait_time = 2.0\n    t.one_shot = true\n    add_child(t)\n    t.timeout.connect(func(): print(\"Pronto!\"))\n    t.start()",
            "lua" to "for i = 5, 1, -1 do\n  print(i)\n  task.wait(1)\nend\nprint(\"Pronto!\")",
            "js" to "let i = 5;\nconst t = setInterval(() => {\n  console.log(i--);\n  if (i < 0) { clearInterval(t); console.log(\"Pronto!\"); }\n}, 1000);"),
        "health" to mapOf(
            "py" to "class Player:\n    def __init__(self):\n        self.hp = 100\n\n    def damage(self, n):\n        self.hp = max(0, self.hp - n)\n        if self.hp == 0:\n            print(\"Game over\")",
            "gd" to "var hp = 100\n\nfunc damage(n: int):\n    hp = max(0, hp - n)\n    if hp == 0:\n        print(\"Game over\")",
            "lua" to "local hp = 100\nlocal function damage(n)\n  hp = math.max(0, hp - n)\n  if hp == 0 then print(\"Game over\") end\nend",
            "js" to "let hp = 100;\nfunction damage(n) {\n  hp = Math.max(0, hp - n);\n  if (hp === 0) console.log(\"Game over\");\n}"),
        "click" to mapOf(
            "py" to "import tkinter as tk\n\nn = 0\ndef click():\n    global n\n    n += 1\n    btn.config(text=\"Cliques: %d\" % n)\n\nroot = tk.Tk()\nbtn = tk.Button(root, text=\"Clique\", command=click)\nbtn.pack(padx=40, pady=40)\nroot.mainloop()",
            "gd" to "extends Button\n\nvar n = 0\n\nfunc _pressed():\n    n += 1\n    text = \"Cliques: \" + str(n)",
            "lua" to "local button = script.Parent\nlocal n = 0\nbutton.MouseButton1Click:Connect(function()\n  n += 1\n  button.Text = \"Cliques: \" .. n\nend)",
            "js" to "let n = 0;\nconst btn = document.querySelector(\"button\");\nbtn.addEventListener(\"click\", () => { btn.textContent = \"Cliques: \" + (++n); });"),
        "sum" to mapOf(
            "py" to "nums = [3, 7, 1, 9]\nprint(sum(nums), max(nums), min(nums))",
            "gd" to "var nums = [3, 7, 1, 9]\nvar total = 0\nfor n in nums:\n    total += n\nprint(total, nums.max(), nums.min())",
            "lua" to "local nums = {3, 7, 1, 9}\nlocal total = 0\nfor _, n in ipairs(nums) do total = total + n end\nprint(total)",
            "js" to "const nums = [3, 7, 1, 9];\nconsole.log(nums.reduce((a, b) => a + b, 0), Math.max(...nums), Math.min(...nums));")
    )

    private fun offlineAi(q: String, spec: String): String {
        val s = (q + " " + spec).lowercase()
        fun has(r: String) = Regex(r).containsMatchIn(s)
        val lang = when {
            has("gdscript|\\bgd\\b|godot") -> "gd"
            has("lua|roblox") -> "lua"
            has("javascript|\\bjs\\b|html") -> "js"
            else -> "py"
        }
        val topic = when {
            has("fibonacci") -> "fib"
            has("fatorial|factorial") -> "fact"
            has("palindromo|palíndromo|palindrome") -> "pal"
            has("inverter (o )?(texto|string|palavra)|reverse") -> "rev"
            has("busca bin|binary search|bsearch") -> "bsearch"
            has("bubble|bolha") -> "bubble"
            has("ordenar|sort|selection") -> "sort"
            has("primo|prime") -> "prime"
            has("aleat|random|sorteio|sortear") -> "rand"
            has("salvar|save|carregar jogo|json") -> "save"
            has("timer|temporizador|cooldown|contagem regressiva|countdown") -> "timer"
            has("vida|dano|health|damage|\\bhp\\b") -> "health"
            has("bot[ãa]o|button|clique|click") -> "click"
            has("soma|somar|m[áa]ximo|maior valor|sum\\b|lista de n") -> "sum"
            has("pul(o|ar)|jump") -> "jump"
            has("mover|andar|move|player|jogador|personagem|walk") -> "move"
            has("contador|counter|contar|count") -> "count"
            has("hello|ol[áa]|oi mundo|world") -> "hello"
            else -> null
        }
        val head = tr("(app AI, basic mode — for full answers add a provider key with: ai key YOUR_KEY)\n", "(IA do app, modo básico — para respostas completas adicione uma chave de provedor: ia key SUA_CHAVE)\n")
        if (topic == null) return head + tr(
            "Offline I only know simple snippets: hello world, fibonacci, factorial, sorting, search, prime, palindrome, random, save/load, timer, health/damage, button click, jump, move, counter (Python, GDScript, Lua, JS). For any other question, add a provider API in 🔑 (Google Gemini has a free plan).",
            "Offline eu só sei trechos simples: hello world, fibonacci, fatorial, ordenar, busca, primo, palíndromo, aleatório, salvar/carregar, timer, vida/dano, clique de botão, pulo, mover, contador (Python, GDScript, Lua, JS). Para qualquer outra pergunta, coloque uma API de provedor em 🔑 (o Google Gemini tem plano grátis).")
        val name = mapOf("py" to "python", "gd" to "gdscript", "lua" to "lua", "js" to "javascript")[lang]
        return head + "```$name\n" + OFFLINE[topic]!![lang] + "\n```"
    }

    // ---------- AI template ----------
    private fun aiKey(): String = apiGet("_provider") ?: prefs.getString("aikey", "") ?: ""
    private val ownerToken = java.util.UUID.randomUUID().toString()

    // ---------- AI providers (Anthropic, Gemini, Groq, OpenRouter, OpenAI) ----------
    private fun providerOf(k: String) = when {
        k.startsWith("sk-ant-") -> "anthropic"
        k.startsWith("AIza") -> "gemini"
        k.startsWith("gsk_") -> "groq"
        k.startsWith("sk-or-") -> "openrouter"
        k.startsWith("sk-") -> "openai"
        else -> ""
    }
    private fun providerLabel(p: String) = mapOf("anthropic" to "Anthropic", "gemini" to "Google Gemini", "groq" to "Groq", "openrouter" to "OpenRouter", "openai" to "OpenAI")[p] ?: p
    private fun aiModel(p: String): String = prefs.getString("aimodel_$p", null) ?: mapOf("anthropic" to "claude-sonnet-5-5", "gemini" to "gemini-flash-latest",
        "groq" to "llama-3.3-70b-versatile", "openrouter" to "openrouter/auto", "openai" to "gpt-4o-mini")[p] ?: ""

    private fun httpPost(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 120000; c.doOutput = true
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.setRequestProperty("content-type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        return code to txt
    }

    /** Returns (HTTP code, answer text or error message). */
    private fun callAi(key: String, sys: String, msg: String, maxTok: Int): Pair<Int, String> {
        val p = providerOf(key)
        val model = aiModel(p)
        val (code, txt) = when (p) {
            "anthropic" -> httpPost("https://api.anthropic.com/v1/messages", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
                JSONObject().put("model", model).put("max_tokens", maxTok).put("system", sys)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", msg))).toString())
            "gemini" -> httpPost("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key", emptyMap(),
                JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", msg)))))
                    .put("generationConfig", JSONObject().put("maxOutputTokens", maxTok)).toString())
            else -> httpPost(when (p) { "groq" -> "https://api.groq.com/openai/v1/chat/completions"; "openrouter" -> "https://openrouter.ai/api/v1/chat/completions"; else -> "https://api.openai.com/v1/chat/completions" },
                mapOf("Authorization" to "Bearer $key"),
                JSONObject().put("model", model).put("max_tokens", maxTok).put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", msg))).toString())
        }
        val j = try { JSONObject(txt) } catch (e: Exception) { null }
        if (code !in 200..299) {
            val m = j?.optJSONObject("error")?.optString("message") ?: j?.optString("error")?.takeIf { it.isNotEmpty() } ?: txt.take(200)
            return code to m
        }
        return try {
            when (p) {
                "anthropic" -> { val a = j!!.getJSONArray("content"); code to (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("\n") { it.getString("text") } }
                "gemini" -> { val a = j!!.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts"); code to (0 until a.length()).joinToString("") { a.getJSONObject(it).optString("text") } }
                else -> code to j!!.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            }
        } catch (e: Exception) { 502 to tr("Unexpected answer from the provider", "Resposta inesperada do provedor") }
    }

    private fun askAi(q: String, spec: String): String {
        val key = aiKey()
        if (key.isEmpty()) return offlineAi(q, spec)
        val p = providerOf(key)
        if (p.isEmpty()) return tr("The saved key is not from a known provider. Open 🔑 API and paste a valid key.", "A chave salva não é de um provedor conhecido. Abra 🔑 API e cole uma chave válida.")
        return try {
            val sys = "You are the AI inside a mobile terminal app. Answer in the user's language. " +
                "If the user wants code, reply with working code in the requested language (Python, GDScript/GD, Kotlin, JS, Lua, C, etc.) in one fenced block and at most a few short lines of explanation. " +
                "If a target app/engine is given, write the code for exactly that app. Keep it compact; no long introductions."
            val msg = if (spec.isBlank()) q else "$q\n\nTarget app/language: $spec"
            val (code, ans) = callAi(key, sys, msg, 4000)
            if (code in 200..299) ans.trim()
            else {
                logError("ai", "${providerLabel(p)} HTTP $code: $ans")
                when (code) {
                    401, 403 -> tr("${providerLabel(p)} rejected the key ($code). Open 🔑 API and paste the provider's key again.", "${providerLabel(p)} recusou a chave ($code). Abra 🔑 API e cole de novo a chave do provedor.")
                    429 -> tr("${providerLabel(p)}: limit reached, try again in a minute. ($ans)", "${providerLabel(p)}: limite atingido, tente de novo em 1 minuto. ($ans)")
                    404 -> tr("Model not found. Type: ai model NAME", "Modelo não encontrado. Digite: ia model NOME") + " ($ans)"
                    else -> "[$code] $ans"
                }
            }
        } catch (e: Exception) {
            logError("ai", e.toString())
            tr("Could not reach the AI: ${e.message} (check your internet)", "Não consegui falar com a IA: ${e.message} (veja a internet)")
        }
    }

    /** Checks the key with the provider, then saves it. [done] runs on the UI thread with a message. */
    private fun saveProviderKey(raw: String, done: (Boolean, String) -> Unit) {
        val k = raw.trim().replace(Regex("\\s"), "")
        if (k.startsWith("twk_")) {
            if (apiVerify(k)) { prefs.edit().putBoolean("ai_twk", true).apply(); done(true, tr("✔ TermWin key accepted — the AI uses the app's own API", "✔ Chave do TermWin aceita — a IA usa a própria API do app")) }
            else done(false, tr("This twk_ key was not found. Create one in 🔑 API Keys and paste it here.", "Essa chave twk_ não foi encontrada. Crie uma em 🔑 Chaves de API e cole aqui."))
            return
        }
        val p = providerOf(k)
        if (p.isEmpty()) { done(false, tr("Unknown key format. Supported: sk-ant-… (Anthropic), AIza… (Google Gemini), gsk_… (Groq), sk-or-… (OpenRouter), sk-… (OpenAI).", "Formato desconhecido. Aceito: sk-ant-… (Anthropic), AIza… (Google Gemini), gsk_… (Groq), sk-or-… (OpenRouter), sk-… (OpenAI).")); return }
        thread {
            val (code, m) = try { callAi(k, "Reply with the single word OK.", "ping", 8) } catch (e: Exception) { 0 to (e.message ?: "") }
            ui.post {
                if (code == 401 || code == 403 || code == 400 && m.contains("API key", true)) done(false, tr("${providerLabel(p)} rejected this key: $m", "${providerLabel(p)} recusou essa chave: $m"))
                else {
                    apiPut("_provider", k); prefs.edit().remove("aikey").apply()
                    if (code in 200..299) done(true, tr("✔ Valid key — ${providerLabel(p)}", "✔ Chave válida — ${providerLabel(p)}"))
                    else done(true, tr("Saved (${providerLabel(p)}), but I could not test it now: $m", "Salva (${providerLabel(p)}), mas não consegui testar agora: $m"))
                }
            }
        }
    }

    /** player//test            -> lists the videos as player1, player2…
     *  player//test//player2//youtube -> plays video number 2 with NO account signed in (anonymous visitor test) */
    private fun ytPlayerTest(t: TabData, s: Srv, raw: String) {
        val parts = raw.split("//").map { it.trim() }
        if (parts.size < 2 || !parts[1].equals("test", true) && !parts[1].equals("teste", true)) {
            err(t, tr("usage: player//test//player2//youtube", "uso: player//test//player2//youtube")); return
        }
        val a = vidsRead(); val n = a.length()
        // number 1 = newest video (same order as the feed)
        fun at(num: Int): JSONObject = a.getJSONObject(n - num)
        val num = parts.drop(2).firstNotNullOfOrNull { Regex("^(?:player\\s*)?(\\d+)$", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() }
        if (num == null) {
            if (n == 0) { append(t, tr("no videos published yet. Use: publish\n", "nenhum vídeo publicado ainda. Use: publicar\n")); return }
            val sb = StringBuilder(tr("players:\n", "players:\n"))
            for (i in 1..minOf(n, 50)) { val e = at(i); sb.append("  player$i  ").append(e.optString("title")).append("  (").append(e.optString("channel")).append(")\n") }
            sb.append(tr("pick one: player//test//player2//youtube\n", "escolha um: player//test//player2//youtube\n"))
            append(t, sb.toString()); return
        }
        if (num < 1 || num > n) { err(t, tr("player$num does not exist ($n videos)", "player$num não existe ($n vídeos)")); return }
        val e = at(num); val id = e.optString("id")
        if (!s.running) startServer(s)
        val path = "/_anon/$id"
        append(t, tr("▶ player$num — ${e.optString("title")}\nrunning with NO account signed in (anonymous test)\n", "▶ player$num — ${e.optString("title")}\nexecutando SEM nenhuma conta logada (teste anônimo)\n"))
        lanIp()?.let { append(t, tr("test on ANOTHER device/account (same Wi-Fi): http://$it:${s.port}$path\n", "teste em OUTRO aparelho/conta (mesmo Wi-Fi): http://$it:${s.port}$path\n")) }
        ui.postDelayed({ openWebWindow("YouTube • player$num", "http://localhost:${s.port}$path") }, 500)
    }

    private fun tplExec(t: TabData, line: String) {
        val s = findSrv(t.srvName)
        val name = when (t.tpl) { "youtube" -> "youtube"; "ai" -> "ai"; else -> "windows 10" }
        val label = when (t.tpl) { "youtube" -> "YouTube"; "ai" -> "AI"; else -> "Windows 10" }
        val l = line.trim().lowercase().split(" ").filter { it.isNotEmpty() }.joinToString(" ")
        if (l.isEmpty()) return
        if (fileCmd(t, line)) return
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
                val cur2 = aiKey()
                if (v.isEmpty()) append(t, if (cur2.isEmpty()) tr("no key saved\n", "nenhuma chave salva\n") else tr("key saved: ${providerLabel(providerOf(cur2))} (…${cur2.takeLast(4)}), model ${aiModel(providerOf(cur2))}\n", "chave salva: ${providerLabel(providerOf(cur2))} (…${cur2.takeLast(4)}), modelo ${aiModel(providerOf(cur2))}\n"))
                else if (v == "clear" || v == "limpar") { prefs.edit().remove("aikey").apply(); apiRemove("_provider"); append(t, tr("key deleted\n", "chave apagada\n")) }
                else { append(t, tr("checking the key…\n", "testando a chave…\n")); saveProviderKey(v) { _, m -> append(t, m + "\n") } }
                return
            }
            val mm = Regex("^(ai|ia|aí)\\s+(model|modelo)\\s*(.*)$", RegexOption.IGNORE_CASE).find(raw)
            if (mm != null) {
                val v = mm.groupValues[3].trim(); val p0 = providerOf(aiKey())
                if (p0.isEmpty()) append(t, tr("save a key first\n", "salve uma chave primeiro\n"))
                else if (v.isEmpty()) append(t, aiModel(p0) + "\n")
                else { prefs.edit().putString("aimodel_$p0", v).apply(); append(t, tr("model set: $v\n", "modelo definido: $v\n")) }
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
                if (t.tpl == "youtube") lanIp()?.let { append(t, tr("other devices on the same Wi-Fi: http://$it:${s.port}\n", "outros aparelhos no mesmo Wi-Fi: http://$it:${s.port}\n")) }
                val full = if (t.tpl == "ai") "$u#t=$ownerToken" else u
                ui.postDelayed({ openWebWindow(label, full, t.tpl == "youtube", t.tpl == "ai") }, 500)
            }
            l.startsWith("player") && Regex("^player\\s*//", RegexOption.IGNORE_CASE).containsMatchIn(line.trim()) && t.tpl == "youtube" -> ytPlayerTest(t, s, line.trim())
            (l == "publish" || l == "publicar") && t.tpl == "youtube" -> startPublish()
            (l == "post" || l == "postar" || l.startsWith("post ") || l.startsWith("postar ")) && t.tpl == "youtube" -> {
                val txt = line.trim().substringAfter(" ", "").trim()
                if (txt.isEmpty()) {
                    if (pubWv != null) pubWv?.evaluateJavascript("if(window.pst)pst()", null)
                    else err(t, tr("usage: post your text (or open the page and use ＋ → Post)", "uso: post seu texto (ou abra a página e use ＋ → Post)"))
                } else if (addPost(txt)) { append(t, tr("post published ✔\n", "post publicado ✔\n")); pubWv?.evaluateJavascript("if(window.load)load()", null) }
                else err(t, tr("sign in first with the 👤 Profile button", "entre antes com o botão 👤 Perfil"))
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


    // ---------- API keys: files/API.winapi (AES-GCM, key held in the Android Keystore) ----------
    private var pendingAuth: (() -> Unit)? = null
    private fun apiFile() = File(dataDir(), "API.winapi")

    private fun apiAesKey(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("termwin_api", null) as? javax.crypto.SecretKey)?.let { return it }
        val g = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(android.security.keystore.KeyGenParameterSpec.Builder("termwin_api",
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    private fun apiRead(): JSONArray = try { JSONObject(apiFile().readText()).getJSONArray("keys") } catch (e: Exception) { JSONArray() }
    private fun apiWrite(a: JSONArray) {
        try { apiFile().writeText(JSONObject().put("format", "winapi").put("version", 1).put("keys", a).toString(2)) }
        catch (e: Exception) { logError("api", "write: ${e.message}") }
    }

    fun apiPut(name: String, key: String) {
        try {
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, apiAesKey())
            val enc = c.doFinal(key.toByteArray())
            val e = JSONObject().put("name", name)
                .put("iv", android.util.Base64.encodeToString(c.iv, android.util.Base64.NO_WRAP))
                .put("data", android.util.Base64.encodeToString(enc, android.util.Base64.NO_WRAP))
                .put("hint", "…" + key.takeLast(4))
            val old = apiRead(); val out = JSONArray()
            for (i in 0 until old.length()) if (!old.getJSONObject(i).optString("name").equals(name, true)) out.put(old.getJSONObject(i))
            out.put(e); apiWrite(out)
        } catch (e: Exception) { logError("api", "put: ${e.message}"); toast("API.winapi: ${e.message}") }
    }

    private fun apiGet(name: String): String? = try {
        val a = apiRead(); var r: String? = null
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i)
            if (e.optString("name").equals(name, true)) {
                val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                c.init(javax.crypto.Cipher.DECRYPT_MODE, apiAesKey(), javax.crypto.spec.GCMParameterSpec(128, android.util.Base64.decode(e.getString("iv"), 0)))
                r = String(c.doFinal(android.util.Base64.decode(e.getString("data"), 0)))
            }
        }
        r
    } catch (e: Exception) { null }

    private fun apiRemove(name: String) {
        val old = apiRead(); val out = JSONArray()
        for (i in 0 until old.length()) if (!old.getJSONObject(i).optString("name").equals(name, true)) out.put(old.getJSONObject(i))
        apiWrite(out)
    }

    /** Runs [ok] only after face / fingerprint / PIN / pattern / password. */
    @Suppress("DEPRECATION")
    private fun requireAuth(why: String, ok: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isDeviceSecure) { toast(tr("Set a screen lock (PIN, fingerprint or face) in Android first", "Configure um bloqueio de tela (PIN, digital ou rosto) no Android primeiro")); return }
        if (Build.VERSION.SDK_INT >= 30) {
            val bp = android.hardware.biometrics.BiometricPrompt.Builder(this)
                .setTitle("TermWin").setSubtitle(why)
                .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
            bp.authenticate(android.os.CancellationSignal(), mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) { ok() }
                override fun onAuthenticationError(code: Int, msg: CharSequence?) { toast(msg?.toString() ?: tr("Not authenticated", "Não autenticado")) }
            })
        } else {
            val i = km.createConfirmDeviceCredentialIntent("TermWin", why)
            if (i == null) { toast(tr("No screen lock", "Sem bloqueio de tela")); return }
            pendingAuth = ok
            startActivityForResult(i, 78)
        }
    }

    private fun copySecret(v: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("api", v)
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        cm.setPrimaryClip(clip)
        toast(tr("Copied — clipboard clears in 30s", "Copiado — a área de transferência limpa em 30s"))
        ui.postDelayed({ try { cm.setPrimaryClip(ClipData.newPlainText("", "")) } catch (e: Exception) { } }, 30000)
    }

    private fun apiVisible(): List<JSONObject> { val a = apiRead(); return (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.optString("name").startsWith("_") } }

    private fun genApiKey(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val r = java.security.SecureRandom()
        return "twk_" + (1..40).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    private fun apiVerify(k: String): Boolean = k.isNotEmpty() && apiVisible().any { apiGet(it.optString("name")) == k }

    private fun showApiKeys() { if (apiVisible().isEmpty()) showApiCreate(true) else showApiMain() }

    private fun showApiCreate(first: Boolean) {
        val p = panel(tr("New API key", "Nova chave de API"), 0.6f)
        p.body.addView(tv(tr("Just type a name — TermWin generates the key for you.", "Só digite um nome — o TermWin gera a chave para você."), 12f, 0xFF9AA5B1.toInt()))
        val nm = field(tr("Name (e.g. my-app)", "Nome (ex.: meu-app)"))
        p.body.addView(nm)
        p.button(tr("Cancel", "Cancelar")) { p.close(); if (!first) showApiMain() }
        p.button(tr("Create", "Criar"), true) {
            val n = nm.text.toString().trim()
            if (n.isEmpty() || n.startsWith("_")) { toast(tr("Type a name", "Digite um nome")); return@button }
            if (apiVisible().any { it.optString("name").equals(n, true) }) { toast(tr("That name already exists", "Esse nome já existe")); return@button }
            val k = genApiKey()
            apiPut(n, k)
            p.body.removeAllViews(); p.footer.removeAllViews()
            p.body.addView(tv("🔑  $n", 16f, Color.WHITE))
            p.body.addView(tv(tr("Copy it now. Later it will only be shown hidden, and copying will need your face / fingerprint / PIN.", "Copie agora. Depois ela só aparece escondida, e para copiar vai pedir seu rosto / digital / senha."), 12f, 0xFF9AA5B1.toInt()).apply { setPadding(0, dp(6), 0, dp(8)) })
            p.body.addView(tv(k, 14f, 0xFF3DDC84.toInt()).apply {
                typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
                background = rounded(0xFF2D2D2D.toInt(), dp(6), 0xFF454545.toInt()); setPadding(dp(12), dp(10), dp(12), dp(10))
            })
            p.button(tr("Copy", "Copiar")) { copySecret(k) }
            p.button(tr("Done", "Concluir"), true) { p.close(); showApiMain() }
        }
    }

    private fun showApiMain() {
        val p = panel("API Keys — API.winapi", 0.8f)
        val list = LinearLayout(this); list.orientation = LinearLayout.VERTICAL
        fun fill() {
            list.removeAllViews()
            val a = apiVisible()
            if (a.isEmpty()) list.addView(tv(tr("(no keys)", "(nenhuma chave)"), 13f, 0xFFAAAAAA.toInt()).apply { setPadding(0, dp(8), 0, dp(8)) })
            for (e in a) {
                val n = e.optString("name")
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
                row.background = rounded(0xFF2A2A2A.toInt(), dp(8)); row.setPadding(dp(12), dp(8), dp(8), dp(8))
                val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
                col.addView(tv("🔑  $n", 15f, Color.WHITE))
                col.addView(tv("twk_••••••••••••••••  " + e.optString("hint"), 12f, 0xFF9AA5B1.toInt()).apply { typeface = Typeface.MONOSPACE })
                row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(smallBtn(tr("Copy", "Copiar")) {
                    requireAuth(tr("Confirm it is you to copy \"$n\"", "Confirme que é você para copiar \"$n\"")) {
                        val v = apiGet(n); if (v == null) toast(tr("Could not decrypt", "Não foi possível descriptografar")) else copySecret(v)
                    }
                })
                row.addView(smallBtn(tr("Delete", "Apagar"), true) {
                    requireAuth(tr("Confirm to delete \"$n\"", "Confirme para apagar \"$n\"")) { apiRemove(n); fill() }
                })
                list.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
            }
        }
        p.body.addView(tv(tr("Keys are encrypted and hidden. Copying needs your face, fingerprint or screen lock. Use a key in other apps: http://localhost:PORT/ask?q=hello&key=twk_…", "As chaves ficam criptografadas e escondidas. Para copiar precisa do seu rosto, digital ou senha. Use em outros apps: http://localhost:PORTA/ask?q=oi&key=twk_…"), 12f, 0xFF9AA5B1.toInt()))
        p.body.addView(list)
        fill()
        p.button(tr("Close", "Fechar")) { p.close() }
        p.button(tr("+ New key", "+ Nova chave"), true) { p.close(); showApiCreate(false) }
    }

    // ---------- profile (local sign-in, saved in the app data file) ----------
    private fun profile(): JSONObject? = try {
        JSONObject(prefs.getString("profile", "") ?: "").takeIf { it.optString("email").isNotEmpty() }
    } catch (e: Exception) { null }


    // ---------- YouTube: one identity per visitor (Google sign-in, no password ever reaches TermWin) ----------
    private class YtCtx(val sid: String, val token: String, val host: Boolean)
    private val ytCtx = ThreadLocal<YtCtx?>()
    private fun ytUsers(): JSONObject = try { JSONObject(prefs.getString("yt_users", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    private fun ytSess(): JSONObject = try { JSONObject(prefs.getString("yt_sessions", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    /** Profile of whoever made THIS request: the phone owner (only from the phone itself) or the visitor's Google session. */
    private fun reqProfile(): JSONObject? {
        val c = ytCtx.get() ?: return profile()
        if (c.host) return profile()
        val email = ytSess().optString(c.sid)
        return if (email.isEmpty()) null else ytUsers().optJSONObject(email)
    }
    private fun httpGetJson(url: String, bearer: String?): JSONObject? = try {
        val cn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        cn.connectTimeout = 8000; cn.readTimeout = 8000
        if (bearer != null) cn.setRequestProperty("Authorization", "Bearer $bearer")
        if (cn.responseCode in 200..299) JSONObject(cn.inputStream.bufferedReader().readText()) else null
    } catch (e: Exception) { null }

    /** Checks the Google access token (must belong to OUR client id and a verified e-mail), reads the YouTube channel and saves the profile. The token itself is never stored. */
    private fun googleLogin(tok: String): JSONObject {
        val cid = prefs.getString("google_client_id", "") ?: ""
        if (cid.isEmpty()) return JSONObject().put("err", "no-client-id")
        if (tok.isEmpty()) return JSONObject().put("err", "no-token")
        val ti = httpGetJson("https://oauth2.googleapis.com/tokeninfo?access_token=" + Uri.encode(tok), null) ?: return JSONObject().put("err", "invalid-token")
        if (ti.optString("aud") != cid) return JSONObject().put("err", "wrong-client-id")
        if (ti.optString("email_verified") != "true" || ti.optString("email").isEmpty()) return JSONObject().put("err", "email-not-verified")
        val email = ti.optString("email").lowercase()
        val ui = httpGetJson("https://www.googleapis.com/oauth2/v3/userinfo", tok)
        val ch = httpGetJson("https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true", tok)
        val c0 = ch?.optJSONArray("items")?.optJSONObject(0)
        val sn = c0?.optJSONObject("snippet"); val th = sn?.optJSONObject("thumbnails")
        val pic = if (c0 == null) "" else (th?.optJSONObject("medium")?.optString("url") ?: th?.optJSONObject("default")?.optString("url") ?: "")
        var name = (sn?.optString("title") ?: "").ifEmpty { ui?.optString("name") ?: "" }.ifEmpty { email.substringBefore('@') }
        val users = ytUsers()
        val taken = (profile()?.optString("channel")?.equals(name, true) == true) ||
            users.keys().asSequence().any { k -> k != email && users.getJSONObject(k).optString("channel").equals(name, true) }
        if (taken) name = name + " (" + email.substringBefore('@') + ")"
        val prof = JSONObject().put("email", email).put("channel", name).put("name", name).put("pic", pic)
            .put("channelId", c0?.optString("id") ?: "").put("hasChannel", c0 != null)
            .put("created", users.optJSONObject(email)?.optLong("created", 0L)?.takeIf { it > 0 } ?: System.currentTimeMillis())
        users.put(email, prof); prefs.edit().putString("yt_users", users.toString()).apply()
        return prof
    }

    private fun googleCmd(t: TabData, rest: String) {
        val a = rest.trim(); val cur = prefs.getString("google_client_id", "") ?: ""
        when {
            a.isEmpty() -> append(t, tr(
                "Google sign-in for the YouTube template (status: ${if (cur.isEmpty()) "OFF" else "ON"})\n1) console.cloud.google.com → new project → enable 'YouTube Data API v3'\n2) OAuth consent screen: External, add scope youtube.readonly, then Publish app\n3) Credentials → OAuth client ID → Web application → 'Authorized JavaScript origins' = the public https address of your server\n4) run: google YOUR_ID.apps.googleusercontent.com\nFor people anywhere in the world the server needs a public address, e.g. in Termux: cloudflared tunnel --url http://localhost:PORT\n",
                "Login Google no modelo YouTube (situação: ${if (cur.isEmpty()) "DESLIGADO" else "LIGADO"})\n1) console.cloud.google.com → novo projeto → ative 'YouTube Data API v3'\n2) Tela de consentimento OAuth: Externo, adicione o escopo youtube.readonly e Publique o app\n3) Credenciais → ID do cliente OAuth → Aplicativo da Web → 'Origens JavaScript autorizadas' = endereço público https do seu servidor\n4) rode: google SEU_ID.apps.googleusercontent.com\nPara gente do mundo todo o servidor precisa de endereço público, ex. no Termux: cloudflared tunnel --url http://localhost:PORTA\n"))
            a == "clear" || a == "limpar" -> { prefs.edit().remove("google_client_id").apply(); append(t, tr("Google sign-in turned off\n", "Login Google desligado\n")) }
            a.endsWith(".apps.googleusercontent.com") -> { prefs.edit().putString("google_client_id", a).apply(); append(t, tr("Google sign-in saved. Restart the YouTube server.\n", "Login Google salvo. Reinicie o servidor do YouTube.\n")) }
            else -> err(t, tr("usage: google YOUR_ID.apps.googleusercontent.com | google clear", "uso: google SEU_ID.apps.googleusercontent.com | google limpar"))
        }
    }

    private fun showProfile() {
        val pr = profile()
        val p = panel(tr("Profile", "Perfil"), 0.55f)
        if (pr != null) {
            p.body.addView(tv(pr.optString("channel"), 20f, Color.WHITE).apply { setPadding(0, dp(8), 0, 0) })
            p.body.addView(tv(pr.optString("email"), 13f, 0xFF9AA5B1.toInt()))
            val mine = vidsRead().let { a -> (0 until a.length()).count { a.getJSONObject(it).optString("email") == pr.optString("email") } }
            p.body.addView(tv(tr("$mine video(s) published", "$mine vídeo(s) publicado(s)"), 13f, Color.WHITE).apply { setPadding(0, dp(10), 0, 0) })
            p.body.addView(tv(tr("Saved in dados.wintext. Your channel name is used on the videos you publish.", "Salvo no dados.wintext. O nome do seu canal aparece nos vídeos que você publicar."), 11f, 0xFF9AA5B1.toInt()).apply { setPadding(0, dp(10), 0, 0) })
            p.button(tr("Sign out", "Sair")) { prefs.edit().remove("profile").apply(); writeIndex(); p.close(); toast(tr("signed out", "você saiu")) }
            p.button(tr("Close", "Fechar"), true) { p.close() }
        } else {
            p.body.addView(tv(tr("Sign in with your e-mail. It stays on this device.", "Entre com seu e-mail. Ele fica salvo neste aparelho."), 12f, 0xFF9AA5B1.toInt()))
            val nm = field(tr("Name / channel (optional)", "Nome / canal (opcional)"))
            val em = field("e-mail")
            em.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            p.body.addView(nm); p.body.addView(em)
            p.button(tr("Cancel", "Cancelar")) { p.close() }
            p.button(tr("Sign in", "Entrar"), true) {
                val e = em.text.toString().trim()
                if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(e)) { toast(tr("Invalid e-mail", "E-mail inválido")); return@button }
                val n = nm.text.toString().trim().ifEmpty { e.substringBefore('@') }
                prefs.edit().putString("profile", JSONObject().put("email", e).put("name", n).put("channel", n).put("created", System.currentTimeMillis()).toString()).apply()
                writeIndex(); p.close(); toast(tr("signed in as $n", "logado como $n"))
            }
        }
    }

    // ---------- videos (real files published by the user) ----------
    private val vlock = Any()
    private fun vidDir() = File(dataDir(), "videos").apply { mkdirs() }
    private fun vidsRead(): JSONArray = synchronized(vlock) {
        try { JSONArray(File(vidDir(), "videos.json").readText()) } catch (e: Exception) { JSONArray() }
    }
    private fun vidsWrite(a: JSONArray) {
        synchronized(vlock) {
            try { File(vidDir(), "videos.json").writeText(a.toString(1)) } catch (e: Exception) { logError("video", e.message ?: "write") }
        }
        writeIndex()
    }

    /** Saves a frame as JPEG and returns the duration in ms. */
    private fun makeThumb(f: File, out: File): Long {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bmp = r.getFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.getFrameAtTime(0)
            if (bmp != null) out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
            dur
        } catch (e: Exception) { 0L } finally { try { r.release() } catch (e: Exception) { } }
    }

    private fun addVideo(id: String, title: String, file: String, thumb: String, dur: Long, channel: String, email: String, short: Boolean? = null) {
        synchronized(vlock) {
            val a = vidsRead()
            a.put(JSONObject().put("id", id).put("title", title).put("file", file).put("thumb", thumb).put("dur", dur)
                .put("channel", channel).put("email", email).put("ts", System.currentTimeMillis()).put("views", 0).also { if (short != null) it.put("short", short) })
            vidsWrite(a)
        }
    }

    /** First run: the app ships with one real sample video so the feed is never empty. */
    private fun seedVideos() {
        if (prefs.getBoolean("seeded", false)) return
        prefs.edit().putBoolean("seeded", true).apply()
        try {
            val f = File(vidDir(), "sample.mp4")
            assets.open("sample.mp4").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            val th = File(vidDir(), "sample.jpg")
            val d = makeThumb(f, th)
            addVideo("sample", "Bem-vindo ao TermWin", f.name, th.name, d, "TermWin", "")
        } catch (e: Exception) { logError("video", "seed: ${e.message}") }
    }

    private fun lanIp(): String? = try {
        java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { java.util.Collections.list(it.inetAddresses) }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (e: Exception) { null }

    // ---------- publish a video ----------
    inner class TwBridge(private val web: android.webkit.WebView? = null) {
        @android.webkit.JavascriptInterface fun listen() { runOnUiThread { startVoice(web) } }
        @android.webkit.JavascriptInterface fun stopListen() { runOnUiThread { stopVoice() } }
        @android.webkit.JavascriptInterface fun saveKey(k: String) { runOnUiThread { saveProviderKey(k) { ok, m -> web?.evaluateJavascript("if(window.onKey)onKey(" + ok + "," + JSONObject.quote(m) + ")", null) } } }
        @android.webkit.JavascriptInterface fun publish() { runOnUiThread { startPublish() } }
        @android.webkit.JavascriptInterface fun profile() { runOnUiThread { showProfile() } }
        @android.webkit.JavascriptInterface fun aiKey() { runOnUiThread { showProviderKey() } }
    }
    private var pubWv: android.webkit.WebView? = null

    // ---------- voice search (microphone) ----------
    private var recog: android.speech.SpeechRecognizer? = null
    private var voiceWv: android.webkit.WebView? = null
    private var voicePending = false

    private fun voiceJs(st: String, tx: String = "") {
        voiceWv?.evaluateJavascript("if(window.onVoice)onVoice(" + JSONObject.quote(st) + "," + JSONObject.quote(tx) + ")", null)
    }

    private fun stopVoice() {
        try { recog?.cancel(); recog?.destroy() } catch (e: Exception) { }
        recog = null
    }

    private fun startVoice(w: android.webkit.WebView?) {
        if (w != null) voiceWv = w
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voicePending = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
            return
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceJs("error", tr("Voice recognition is not available on this phone", "Reconhecimento de voz indisponível neste aparelho"))
            return
        }
        stopVoice()
        val r = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        recog = r
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { voiceJs("start") }
            override fun onBeginningOfSpeech() { }
            override fun onRmsChanged(v: Float) { }
            override fun onBufferReceived(b: ByteArray?) { }
            override fun onEndOfSpeech() { voiceJs("end") }
            override fun onEvent(t: Int, b: Bundle?) { }
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { voiceJs("partial", it) }
            }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (t.isBlank()) voiceJs("error", tr("Did not catch that", "Não entendi, fale de novo")) else voiceJs("final", t)
            }
            override fun onError(e: Int) {
                voiceJs("error", when (e) {
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH, android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> tr("Did not catch that", "Não entendi, fale de novo")
                    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> tr("Microphone permission denied", "Permissão do microfone negada")
                    android.speech.SpeechRecognizer.ERROR_NETWORK, android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> tr("No internet for voice recognition", "Sem internet para o reconhecimento de voz")
                    else -> tr("Voice error ($e)", "Erro de voz ($e)")
                })
            }
        })
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, if (pt) "pt-BR" else "en-US")
        r.startListening(i)
    }

    private fun startPublish() {
        if (profile() == null) { toast(tr("Sign in with your e-mail first (👤 Profile)", "Entre com seu e-mail antes (👤 Perfil)")); showProfile(); return }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*"), 79)
    }

    private fun showPublish(uri: Uri) {
        val pr = profile() ?: return
        val fname = try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && ix >= 0) c.getString(ix) else null
            }
        } catch (e: Exception) { null } ?: "video.mp4"
        val p = panel(tr("Publish video", "Publicar vídeo"), 0.6f)
        p.body.addView(tv("🎬  $fname", 14f, Color.WHITE).apply { setPadding(0, dp(8), 0, 0) })
        val title = field(tr("Title", "Título"), fname.substringBeforeLast('.'))
        p.body.addView(title)
        val cbShort = android.widget.CheckBox(this).apply { text = tr("Publish as a Short (vertical videos are detected automatically)", "Publicar como Short (vídeos verticais são detectados sozinhos)"); setTextColor(Color.WHITE); textSize = 12f }
        p.body.addView(cbShort)
        p.body.addView(tv(tr("Channel: ", "Canal: ") + pr.optString("channel"), 12f, 0xFF9AA5B1.toInt()).apply { setPadding(0, dp(10), 0, 0) })
        p.button(tr("Cancel", "Cancelar")) { p.close() }
        p.button(tr("Publish", "Publicar"), true) {
            val tt = title.text.toString().trim()
            if (tt.isEmpty()) { toast(tr("Type a title", "Digite o título")); return@button }
            val forceShort = cbShort.isChecked
            p.close()
            toast(tr("Publishing…", "Publicando…"))
            thread {
                try {
                    val id = System.currentTimeMillis().toString(36)
                    val ext = fname.substringAfterLast('.', "mp4").lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "mp4" }
                    val f = File(vidDir(), "$id.$ext")
                    contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    val th = File(vidDir(), "$id.jpg")
                    val d = makeThumb(f, th)
                    addVideo(id, tt, f.name, th.name, d, pr.optString("channel"), pr.optString("email"), if (forceShort) true else null)
                    ui.post { toast(tr("Published ✔", "Publicado ✔")); pubWv?.evaluateJavascript("if(window.load)load()", null) }
                } catch (e: Exception) {
                    logError("video", "publish: ${e.message}")
                    ui.post { toast(tr("Could not publish: ${e.message}", "Não consegui publicar: ${e.message}")) }
                }
            }
        }
    }

    private fun jaRead(n: String): JSONArray = synchronized(vlock) {
        try { JSONArray(File(vidDir(), n).readText()) } catch (e: Exception) { JSONArray() }
    }
    private fun jaWrite(n: String, a: JSONArray) {
        synchronized(vlock) { try { File(vidDir(), n).writeText(a.toString()) } catch (e: Exception) { logError("video", e.message ?: "write") } }
    }
    private fun addPost(text: String): Boolean {
        val me = reqProfile() ?: return false
        val t = text.trim().take(500)
        if (t.isEmpty()) return false
        synchronized(vlock) {
            val a = jaRead("posts.json")
            a.put(JSONObject().put("id", System.currentTimeMillis().toString(36) + a.length()).put("channel", me.optString("channel"))
                .put("email", me.optString("email")).put("text", t).put("ts", System.currentTimeMillis()).put("likes", 0).put("my", 0))
            jaWrite("posts.json", a)
        }
        return true
    }
    private fun addComment(vid: String, text: String): Boolean {
        val me = reqProfile() ?: return false
        val t = text.trim().take(300)
        if (t.isEmpty() || vid.isEmpty()) return false
        synchronized(vlock) {
            val a = jaRead("comments.json")
            a.put(JSONObject().put("vid", vid).put("channel", me.optString("channel")).put("text", t).put("ts", System.currentTimeMillis()))
            jaWrite("comments.json", a)
        }
        return true
    }

    private fun jsonOut(o: OutputStream, j: Any) = send(o, "application/json; charset=utf-8", j.toString().toByteArray())

    private fun subsRead(): JSONArray = synchronized(vlock) {
        try { JSONArray(File(vidDir(), "subs.json").readText()) } catch (e: Exception) { JSONArray() }
    }
    private fun chSubsRead(): JSONObject = synchronized(vlock) {
        try { JSONObject(File(vidDir(), "chsubs.json").readText()) } catch (e: Exception) { JSONObject() }
    }
    private fun subsToggle(name: String, on: Boolean) = synchronized(vlock) {
        val old = subsRead(); val out = JSONArray()
        val had = (0 until old.length()).any { old.getString(it).equals(name, true) }
        if (name.isNotBlank() && on != had) {
            val cs = chSubsRead(); val key = name.lowercase()
            cs.put(key, maxOf(0, cs.optInt(key) + (if (on) 1 else -1)))
            try { File(vidDir(), "chsubs.json").writeText(cs.toString()) } catch (e: Exception) { }
        }
        for (i in 0 until old.length()) if (!old.getString(i).equals(name, true)) out.put(old.getString(i))
        if (on && name.isNotBlank()) out.put(name)
        try { File(vidDir(), "subs.json").writeText(out.toString()) } catch (e: Exception) { logError("video", "subs: ${e.message}") }
    }

    /** Short = vertical/square video up to 3 min (or up to 60 s when the size is unknown). */
    private fun detectShort(f: File, dur: Long): Boolean {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            var w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val x = w; w = h; h = x }
            if (w > 0 && h > 0) (h >= w && dur <= 180_000L) else (dur in 1L..60_000L)
        } catch (e: Exception) { false } finally { try { r.release() } catch (e: Exception) { } }
    }

    private fun pubVid(e: JSONObject) = JSONObject().put("id", e.optString("id")).put("title", e.optString("title")).put("channel", e.optString("channel"))
        .put("dur", e.optLong("dur")).put("ts", e.optLong("ts")).put("views", e.optInt("views")).put("short", e.optBoolean("short", false))
        .put("likes", e.optInt("likes")).put("dislikes", e.optInt("dislikes")).put("my", e.optInt("my"))

    private fun ytRoute(o: OutputStream, path: String, range: String?, query: String) {
        fun qp(k: String) = query.split("&").firstOrNull { it.startsWith("$k=") }?.substringAfter("=")?.let { Uri.decode(it.replace("+", " ")) } ?: ""
        fun find(id: String): JSONObject? { val a = vidsRead(); for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") == id) return a.getJSONObject(i); return null }
        when {
            path == "/api/chsubs" -> jsonOut(o, chSubsRead())
            path == "/api/posts" -> {
                val a = jaRead("posts.json"); val me = reqProfile()?.optString("channel") ?: ""; val out = JSONArray()
                for (i in a.length() - 1 downTo 0) { val x = a.getJSONObject(i)
                    out.put(JSONObject().put("id", x.optString("id")).put("channel", x.optString("channel")).put("text", x.optString("text"))
                        .put("ts", x.optLong("ts")).put("likes", x.optInt("likes")).put("my", x.optInt("my"))
                        .put("own", me.isNotEmpty() && me.equals(x.optString("channel"), true))) }
                jsonOut(o, out)
            }
            path == "/api/post" -> { val ok = addPost(qp("text")); jsonOut(o, JSONObject().put("ok", ok)) }
            path == "/api/postlike" -> synchronized(vlock) {
                val a = jaRead("posts.json"); val id = qp("id"); val res = JSONObject()
                for (i in 0 until a.length()) { val x = a.getJSONObject(i); if (x.optString("id") == id) {
                    val my = 1 - x.optInt("my"); val l = maxOf(0, x.optInt("likes") + (if (my == 1) 1 else -1))
                    x.put("my", my).put("likes", l); res.put("my", my).put("likes", l) } }
                jaWrite("posts.json", a); jsonOut(o, res)
            }
            path == "/api/postdel" -> synchronized(vlock) {
                val a = jaRead("posts.json"); val id = qp("id"); val me = reqProfile()?.optString("channel") ?: ""; val out = JSONArray()
                for (i in 0 until a.length()) { val x = a.getJSONObject(i)
                    if (!(x.optString("id") == id && me.isNotEmpty() && me.equals(x.optString("channel"), true))) out.put(x) }
                jaWrite("posts.json", out); jsonOut(o, JSONObject().put("ok", true))
            }
            path == "/api/comments" -> {
                val a = jaRead("comments.json"); val id = qp("id"); val out = JSONArray()
                for (i in a.length() - 1 downTo 0) { val x = a.getJSONObject(i); if (x.optString("vid") == id)
                    out.put(JSONObject().put("channel", x.optString("channel")).put("text", x.optString("text")).put("ts", x.optLong("ts"))) }
                jsonOut(o, out)
            }
            path == "/api/comment" -> { val ok = addComment(qp("id"), qp("text")); jsonOut(o, JSONObject().put("ok", ok)) }
            path == "/api/react" -> synchronized(vlock) {
                val id = qp("id"); val r = (qp("r").toIntOrNull() ?: 0).coerceIn(-1, 1)
                val a = vidsRead(); val res = JSONObject()
                for (i in 0 until a.length()) { val x = a.getJSONObject(i); if (x.optString("id") == id) {
                    val old = x.optInt("my")
                    val l = maxOf(0, x.optInt("likes") - (if (old == 1) 1 else 0) + (if (r == 1) 1 else 0))
                    val d = maxOf(0, x.optInt("dislikes") - (if (old == -1) 1 else 0) + (if (r == -1) 1 else 0))
                    x.put("likes", l).put("dislikes", d).put("my", r)
                    res.put("likes", l).put("dislikes", d).put("my", r) } }
                vidsWrite(a); jsonOut(o, res)
            }
            path == "/api/subs" -> jsonOut(o, subsRead())
            path == "/api/sub" -> { subsToggle(qp("name"), qp("on") == "1"); jsonOut(o, JSONObject().put("ok", true)) }
            path == "/api/videos" -> {
                val a = vidsRead(); val out = JSONArray(); var chg = false
                for (i in 0 until a.length()) { val x = a.getJSONObject(i); if (!x.has("short")) { x.put("short", detectShort(File(vidDir(), x.optString("file")), x.optLong("dur"))); chg = true } }
                if (chg) vidsWrite(a)
                for (i in a.length() - 1 downTo 0) out.put(pubVid(a.getJSONObject(i)))
                jsonOut(o, out)
            }
            path == "/api/me" -> { val me = reqProfile()
                jsonOut(o, JSONObject().put("name", me?.optString("channel") ?: "").put("pic", me?.optString("pic") ?: "").put("clientId", prefs.getString("google_client_id", "") ?: "")) }
            path == "/api/avatars" -> { val u = ytUsers(); val out = JSONObject()
                u.keys().forEach { k -> val x = u.getJSONObject(k); if (x.optString("pic").isNotEmpty()) out.put(x.optString("channel").lowercase(), x.optString("pic")) }
                jsonOut(o, out) }
            path == "/api/glogin" -> {
                val prof = googleLogin(ytCtx.get()?.token ?: "")
                if (prof.has("err")) jsonOut(o, JSONObject().put("ok", false).put("err", prof.optString("err")))
                else {
                    val rnd = java.security.SecureRandom(); val sid = ByteArray(16).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
                    val ss = ytSess(); ss.put(sid, prof.optString("email")); prefs.edit().putString("yt_sessions", ss.toString()).apply()
                    send(o, "application/json; charset=utf-8", JSONObject().put("ok", true).put("name", prof.optString("channel")).put("hasChannel", prof.optBoolean("hasChannel")).toString().toByteArray(),
                        "200 OK", "Set-Cookie: tws=$sid; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax\r\n")
                }
            }
            path == "/api/logout" -> { val sid = ytCtx.get()?.sid ?: ""
                if (sid.isNotEmpty()) { val ss = ytSess(); ss.remove(sid); prefs.edit().putString("yt_sessions", ss.toString()).apply() }
                send(o, "application/json; charset=utf-8", "{\"ok\":true}".toByteArray(), "200 OK", "Set-Cookie: tws=; Path=/; Max-Age=0\r\n") }
            path == "/api/channel" -> {
                val name = qp("name")
                val a = vidsRead(); val list = JSONArray(); var views = 0L; var first = Long.MAX_VALUE
                for (i in a.length() - 1 downTo 0) { val e = a.getJSONObject(i)
                    if (e.optString("channel").equals(name, true)) { list.put(pubVid(e)); views += e.optInt("views"); first = minOf(first, e.optLong("ts")) } }
                val me = reqProfile()
                val own = me != null && me.optString("channel").equals(name, true)
                val since = if (own) me!!.optLong("created", if (first == Long.MAX_VALUE) System.currentTimeMillis() else first) else if (first == Long.MAX_VALUE) 0L else first
                jsonOut(o, JSONObject().put("exists", own || list.length() > 0).put("name", name).put("own", own)
                    .put("handle", "@" + name.lowercase().replace(Regex("[^a-z0-9]"), "")).put("videos", list).put("views", views).put("subs", chSubsRead().optInt(name.lowercase())).put("since", since))
            }
            path.startsWith("/api/view/") -> synchronized(vlock) {
                val id = path.removePrefix("/api/view/"); val a = vidsRead()
                for (i in 0 until a.length()) { val e = a.getJSONObject(i); if (e.optString("id") == id) e.put("views", e.optInt("views") + 1) }
                vidsWrite(a); jsonOut(o, JSONObject().put("ok", true))
            }
            path.startsWith("/_anon/v/") -> {
                val e = find(path.removePrefix("/_anon/v/")); val f = e?.let { File(vidDir(), it.optString("file")) }
                if (f != null && f.isFile) serveFile(o, f, range) else send(o, "text/plain; charset=utf-8", "404".toByteArray(), "404 Not Found")
            }
            path.startsWith("/_anon/") -> {
                val id = path.removePrefix("/_anon/"); val e = find(id)
                if (e == null) send(o, "text/plain; charset=utf-8", "404".toByteArray(), "404 Not Found")
                else {
                    fun esc(x: String) = x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                    val acct = if (reqProfile() == null) tr("NONE (anonymous test, nobody signed in)", "NENHUMA (teste anônimo, ninguém logado)") else tr("signed in", "logado")
                    val html = """<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<body style="margin:0;background:#0f0f0f;color:#fff;font:14px sans-serif">
<div style="padding:10px"><b>@TITLE@</b><div style="color:#aaa">@CHLABEL@: @CH@</div>
<div style="margin:6px 0;padding:6px;border-radius:6px;background:#222">👤 @ACCLABEL@: @ACCT@</div></div>
<video id=v src="/_anon/v/@ID@" controls playsinline autoplay style="width:100%;max-height:60vh;background:#000"></video>
<pre id=s style="padding:10px;color:#9f9;white-space:pre-wrap"></pre>
<script>
var v=document.getElementById('v'),s=document.getElementById('s');
function log(x){s.textContent+=x+"\n"}
['loadedmetadata','canplay','playing','pause','ended','waiting','stalled'].forEach(function(n){v.addEventListener(n,function(){log(n=='loadedmetadata'?n+' '+Math.round(v.duration)+'s':n)})});
v.addEventListener('error',function(){log('@ERR@ (code '+(v.error&&v.error.code)+')')});
fetch('/_anon/v/@ID@',{headers:{Range:'bytes=0-1'}}).then(function(r){log('HTTP '+r.status+(r.status<300?' ✔ @OK@':' ✖ @BAD@'))}).catch(function(){log('@BAD@')});
</script>"""
                        .replace("@TITLE@", esc(e.optString("title"))).replace("@CH@", esc(e.optString("channel"))).replace("@ID@", esc(id))
                        .replace("@CHLABEL@", tr("channel", "canal")).replace("@ACCLABEL@", tr("Account", "Conta")).replace("@ACCT@", acct)
                        .replace("@ERR@", tr("ERROR loading the video", "ERRO ao carregar o vídeo"))
                        .replace("@OK@", tr("plays with no account", "toca sem conta")).replace("@BAD@", tr("blocked without account", "bloqueado sem conta"))
                    send(o, "text/html; charset=utf-8", html.toByteArray())
                }
            }
            path.startsWith("/v/") -> {
                val e = find(path.removePrefix("/v/")); val f = e?.let { File(vidDir(), it.optString("file")) }
                if (f != null && f.isFile) serveFile(o, f, range) else send(o, "text/plain; charset=utf-8", "404".toByteArray(), "404 Not Found")
            }
            path.startsWith("/t/") -> {
                val e = find(path.removePrefix("/t/")); val f = e?.let { File(vidDir(), it.optString("thumb")) }
                if (f != null && f.isFile) serveFile(o, f, null) else send(o, "text/plain; charset=utf-8", "404".toByteArray(), "404 Not Found")
            }
            else -> sendAsset(o, "template_youtube.html")
        }
    }

    private fun showProviderKey() {
        val p = panel(tr("AI provider API", "API do provedor da IA"), 0.62f)
        p.body.addView(tv(tr("Paste the key from your AI provider. Accepted: Google Gemini (AIza…, has a free plan), Groq (gsk_…, free plan), Anthropic (sk-ant-…), OpenRouter (sk-or-…), OpenAI (sk-…). Free-plan limits may change. Get a free one at aistudio.google.com/apikey.",
            "Cole a chave do seu provedor de IA. Aceito: Google Gemini (AIza…, tem plano grátis), Groq (gsk_…, plano grátis), Anthropic (sk-ant-…), OpenRouter (sk-or-…), OpenAI (sk-…). Limites do plano grátis podem mudar. Pegue uma grátis em aistudio.google.com/apikey."), 12f, 0xFF9AA5B1.toInt()))
        val saved = aiKey()
        p.body.addView(tv(if (saved.isEmpty()) tr("Status: no key — AI works offline (simple code only)", "Status: sem chave — a IA funciona offline (só códigos simples)")
            else tr("Status: ${providerLabel(providerOf(saved))} key saved (…${saved.takeLast(4)})", "Status: chave ${providerLabel(providerOf(saved))} salva (…${saved.takeLast(4)})"),
            13f, if (saved.isEmpty()) 0xFFFFB454.toInt() else GREEN).apply { setPadding(0, dp(8), 0, 0) })
        val kv = field(tr("Provider API key", "Chave da API do provedor"))
        kv.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        p.body.addView(kv)
        val msg = tv("", 12f, 0xFFFFB454.toInt()).apply { setPadding(0, dp(8), 0, 0) }
        p.body.addView(msg)
        p.button(tr("Close", "Fechar")) { p.close() }
        if (saved.isNotEmpty()) p.button(tr("Remove", "Remover")) { apiRemove("_provider"); prefs.edit().remove("aikey").apply(); toast(tr("key removed", "chave removida")); p.close() }
        p.button(tr("Test & save", "Testar e salvar"), true) {
            if (kv.text.isNullOrBlank()) { msg.text = tr("Paste the key", "Cole a chave"); return@button }
            msg.setTextColor(0xFF9AA5B1.toInt()); msg.text = tr("testing…", "testando…")
            saveProviderKey(kv.text.toString()) { ok, m ->
                if (ok) { toast(m); p.close() } else { msg.setTextColor(0xFFFF6B6B.toInt()); msg.text = m }
            }
        }
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
    // ---------- shell chains (&&), $VAR, ~, git (JGit), unzip, ~/storage links ----------
    private fun ensureStorageLinks() {
        try {
            if (!storageOk()) return
            val ext = Environment.getExternalStorageDirectory()
            val dir = File(filesDir, "storage").apply { mkdirs() }
            val m = mapOf("shared" to ext, "downloads" to File(ext, "Download"), "documents" to File(ext, "Documents"),
                "dcim" to File(ext, "DCIM"), "pictures" to File(ext, "Pictures"), "music" to File(ext, "Music"), "movies" to File(ext, "Movies"))
            for ((n, tg) in m) {
                val l = File(dir, n)
                if (!java.nio.file.Files.isSymbolicLink(l.toPath()) && !l.exists()) android.system.Os.symlink(tg.path, l.path)
            }
        } catch (e: Exception) { }
    }

    private fun splitChain(line: String): List<String> {
        val out = mutableListOf<String>(); val sb = StringBuilder(); var q = '\u0000'; var i = 0
        while (i < line.length) {
            val c = line[i]
            if (q != '\u0000') { sb.append(c); if (c == q) q = '\u0000' }
            else if (c == '"' || c == '\'') { q = c; sb.append(c) }
            else if (c == '&' && i + 1 < line.length && line[i + 1] == '&') { out.add(sb.toString().trim()); sb.setLength(0); i++ }
            else sb.append(c)
            i++
        }
        out.add(sb.toString().trim())
        return out.filter { it.isNotEmpty() }
    }

    private fun expandVars(s: String, vars: Map<String, String>): String {
        var r = s
        if (r == "~" || r.startsWith("~/")) r = filesDir.path + r.drop(1)
        return Regex("\\$(\\w+|\\{\\w+\\})").replace(r) { m ->
            val n = m.groupValues[1].trim('{', '}')
            vars[n] ?: envVars[n] ?: when (n) { "HOME" -> filesDir.path; "TMPDIR" -> cacheDir.path; else -> System.getenv(n) ?: "" }
        }
    }

    private fun splitArgs(s: String, vars: Map<String, String>): List<String> {
        val out = mutableListOf<String>(); val sb = StringBuilder(); var q = '\u0000'; var has = false
        fun flush() { if (has) { out.add(expandVars(sb.toString(), vars)); sb.setLength(0); has = false } }
        for (c in s) {
            if (q != '\u0000') { if (c == q) q = '\u0000' else sb.append(c) }
            else if (c == '"' || c == '\'') { q = c; has = true }
            else if (c.isWhitespace()) flush()
            else { sb.append(c); has = true }
        }
        flush()
        return out
    }

    private val MAX_AND = 1780

    /** How many && operators the line has (outside quotes). */
    private fun chainOps(line: String): Int {
        var q = '\u0000'; var n = 0; var i = 0
        while (i < line.length) {
            val c = line[i]
            if (q != '\u0000') { if (c == q) q = '\u0000' }
            else if (c == '"' || c == '\'') q = c
            else if (c == '&' && i + 1 < line.length && line[i + 1] == '&') { n++; i++ }
            i++
        }
        return n
    }

    private val INTERNAL = setOf("help", "clear", "exit", "logout", "nano", "edit", "vi", "alias", "unalias", "export", "unset", "env", "wget", "curl",
        "server", "pkg", "termwin", "neofetch", "cowsay", "tree", "info", "memory", "storage", "ip", "battery", "notify", "open", "history", "data", "errors",
        "ct", "creat", "create", "copy", "copiar", "google", "projects", "projetos")
    private val TPL_WORDS = setOf("help", "ajuda", "play", "publish", "publicar", "post", "postar", "turn", "turnoff", "desligar", "port", "status", "clear", "limpar",
        "exit", "ai", "ia", "aí", "player", "ct", "creat", "create", "copy", "copiar", "google", "open", "abrir", "ls", "pwd", "projects", "projetos")

    /** True when TermWin itself runs this command (help, clear, ct…); false = the Android shell runs it. */
    private fun isInternal(t: TabData, seg: String): Boolean {
        val ln = expandAlias(seg.trim())
        if (ln.isEmpty()) return false
        val w0 = ln.split(Regex("\\s+"))[0].lowercase()
        if (t.tpl.isNotEmpty()) return w0 in TPL_WORDS || SLASH_RE.containsMatchIn(ln)
        if (SLASH_RE.containsMatchIn(ln)) return true
        val w = cmd(w0)
        return w in INTERNAL || w0 in INTERNAL || w0.startsWith("termwin-") || w0.startsWith("termux-")
    }

    private fun needsChain(line: String): Boolean {
        val f = line.trim().split(Regex("\\s+"))[0].lowercase()
        return f == "git" || f == "unzip" || splitChain(line).size > 1
    }

    private fun chain(t: TabData, line: String) {
        if (t.proc != null) { err(t, tr("A command is already running (use ^C)", "Já existe um comando rodando (use ^C)")); return }
        val segs = splitChain(line)
        ghTab = t
        val needsGh = segs.any { sg -> sg.trim().split(Regex("\\s+")).let { it.getOrNull(0) == "git" && it.getOrNull(1) in listOf("push", "pull", "clone") } }
        if (needsGh && gitCreds() == null) { askGithub(t); return }
        busy(1)
        thread {
            var ok = true
            var exited = false
            var lastClear = false
            var actionsUrl: String? = null
            var cwd = File(t.cwd)
            val vars = mutableMapOf<String, String>()
            fun out(s: String) { ui.post { append(t, s) } }
            for (seg in segs) {
                val a = splitArgs(seg, vars)
                if (a.isEmpty()) continue
                lastClear = false
                val good: Boolean = try {
                    when {
                        a.size == 1 && Regex("^[A-Za-z_][A-Za-z0-9_]*=").containsMatchIn(seg.trim()) -> {
                            vars[seg.trim().substringBefore('=')] = expandVars(seg.trim().substringAfter('=').removeSurrounding("\"").removeSurrounding("'"), vars); true
                        }
                        a[0] == "cd" -> {
                            val tg = if (a.size < 2) filesDir else if (a[1] == "-") File(t.prev.ifEmpty { cwd.path }) else resolveIn(cwd.path, a[1])
                            val tg2 = tg ?: a.getOrNull(1)?.let { x ->
                                val c = (if (x.startsWith("/")) File(x) else File(cwd, x)).canonicalFile
                                if (c.path.startsWith(filesDir.canonicalPath) && c.mkdirs()) c else null
                            }
                            if (tg2 != null && tg2.isDirectory) { cwd = tg2; true } else { out("cd: ${a.getOrNull(1) ?: ""}: " + tr("No such file or directory", "Arquivo ou diretório inexistente") + "\n"); false }
                        }
                        a[0] == "git" -> { val r = gitRun(a.drop(1), cwd, ::out); if (r && a.getOrNull(1) == "push") actionsUrl = ghActionsUrl(cwd); r }
                        a[0] == "unzip" -> unzipRun(a.drop(1), cwd, ::out)
                        isInternal(t, seg) -> {
                            val execLine = a.joinToString(" ") { x -> if (x.any { c -> c.isWhitespace() }) "\"" + x + "\"" else x }
                            val e0 = errCount
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var base = 0
                            ui.post {
                                base = busyN
                                try { exec(t, execLine, true) } catch (e: Exception) { err(t, "${a[0]}: ${e.message}") } finally { latch.countDown() }
                            }
                            latch.await()
                            // wait for async work started by the command (curl, wget, downloads…)
                            var spins = 0
                            while (spins < 36000) {
                                val l2 = java.util.concurrent.CountDownLatch(1); var still = false
                                ui.post { still = busyN > base; l2.countDown() }
                                l2.await()
                                if (!still) break
                                Thread.sleep(100); spins++
                            }
                            val w0 = a[0].lowercase()
                            val wc = cmd(w0)
                            lastClear = wc == "clear" || w0 == "limpar"
                            if (wc == "exit" || w0 == "logout") exited = true
                            errCount == e0
                        }
                        else -> {
                            val blocked = guardCheck(seg, cwd)
                            if (blocked != null) { out(blocked + "\n"); logError(t.name, "BLOCKED: $seg"); false } else {
                            val pb = safePb(seg, cwd)
                            pb.environment()["HOME"] = filesDir.path; pb.environment()["TMPDIR"] = cacheDir.path
                            envVars.forEach { (k, v) -> pb.environment()[k] = v }
                            vars.forEach { (k, v) -> pb.environment()[k] = v }
                            val pr = pb.start(); t.proc = pr
                            pr.inputStream.bufferedReader().use { r ->
                                val buf = CharArray(1024)
                                while (true) { val n = r.read(buf); if (n < 0) break; out(String(buf, 0, n)) }
                            }
                            val code = pr.waitFor(); t.proc = null
                            if (code != 0) out("↳ exit $code\n")
                            code == 0
                            }
                        }
                    }
                } catch (e: Exception) { out("${a[0]}: ${e.message}\n"); false }
                if (!good) { ok = false; out("✖ " + tr("stopped at: ", "parou em: ") + seg + "\n"); break }
                if (exited) break
            }
            ui.post {
                if (cwd.isDirectory) { t.prev = t.cwd; t.cwd = cwd.path }
                t.proc = null; busy(-1)
                if (ok && !lastClear) append(t, "✔\n")
                val au = actionsUrl
                if (ok && au != null) {
                    append(t, tr("opening GitHub Actions window: $au\n", "abrindo a janela do GitHub Actions: $au\n"))
                    ui.postDelayed({ openWebWindow("GitHub Actions", au) }, 400)
                }
                notifyDone(if (ok) tr("✔ Command finished", "✔ Comando concluído") else tr("✖ Command failed", "✖ Comando falhou"), line.take(80), ok)
            }
        }
    }

    /** Link of the Actions page of the GitHub repo that this folder pushes to (origin), or null. */
    private fun ghActionsUrl(cwd: File): String? {
        return try {
            val repo = org.eclipse.jgit.storage.file.FileRepositoryBuilder().findGitDir(cwd).build()
            val url = try { repo.config.getString("remote", "origin", "url") } finally { repo.close() }
            val m = Regex("github\\.com[/:]([^/\\s]+)/([^/\\s]+?)(?:\\.git)?/?$").find(url ?: "") ?: return null
            "https://github.com/" + m.groupValues[1] + "/" + m.groupValues[2] + "/actions"
        } catch (e: Exception) { null }
    }

    private fun unzipRun(args: List<String>, cwd: File, out: (String) -> Unit): Boolean {
        var zip: String? = null; var dest: String? = null; var i = 0; var quiet = false
        while (i < args.size) {
            val x = args[i]
            if (x == "-d") { dest = args.getOrNull(i + 1); i++ } else if (x == "-q" || x == "-qq") quiet = true
            else if (x.startsWith("-")) { } else if (zip == null) zip = x
            i++
        }
        if (zip == null) { out("usage: unzip file.zip [-d folder]\n"); return false }
        val zf = resolveIn(cwd.path, zip)?.takeIf { it.isFile } ?: run { out("unzip: $zip: " + tr("No such file or directory", "Arquivo inexistente") + "\n"); return false }
        val dd = if (dest == null) cwd else (if (dest.startsWith("/")) File(dest) else File(cwd, dest)).apply { mkdirs() }
        val base = dd.canonicalFile
        var n = 0; var skipped = 0
        java.util.zip.ZipInputStream(zf.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val f = File(base, e.name).canonicalFile
                if (!f.path.startsWith(base.path)) continue
                if (e.isDirectory) { if (inSafe(f) || !f.exists()) f.mkdirs() }
                else if (f.exists() && !inSafe(f)) { skipped++ }
                else { f.parentFile?.mkdirs(); f.outputStream().use { o -> zin.copyTo(o) }; n++ }
            }
        }
        if (skipped > 0) out(tr("protection: $skipped existing file(s) outside the app folder were NOT overwritten\n", "proteção: $skipped arquivo(s) que já existiam fora da pasta do app NÃO foram sobrescritos\n"))
        if (!quiet) out(tr("extracted $n file(s)\n", "extraídos $n arquivo(s)\n"))
        return true
    }

    private var ghTab: TabData? = null

    /** No GitHub token saved yet: opens GitHub inside the app (no Chrome) so the token can be created and saved with the 💾 button. */
    private fun askGithub(t: TabData?) {
        if (t != null) append(t, tr("GitHub login needed. Opened GitHub here: create the token (Generate token), copy it and tap 💾. Then run the command again.\n",
            "Precisa entrar no GitHub. Abri o GitHub aqui: crie o token (Generate token), copie e toque em 💾. Depois rode o comando de novo.\n"))
        openWebWindow("GitHub", "https://github.com/settings/tokens/new?scopes=repo&description=TermWin", false, false, true)
    }

    private fun saveGhTokenFromClipboard(): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val tk = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim() ?: ""
        if (!(tk.startsWith("ghp_") || tk.startsWith("github_pat_") || tk.startsWith("gho_"))) {
            toast(tr("Copy the token first (it starts with ghp_)", "Copie o token primeiro (começa com ghp_)")); return false
        }
        apiPut("_github", tk)
        toast(tr("Token saved ✔ — run the command again", "Token salvo ✔ — rode o comando de novo"))
        return true
    }

    private fun gitCreds() = apiGet("_github")?.takeIf { it.isNotBlank() }?.let { org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(it, "") }
    private fun gitIdent() = org.eclipse.jgit.lib.PersonIdent(
        (prefs.getString("git_name", "") ?: "").ifBlank { "TermWin" }, (prefs.getString("git_email", "") ?: "").ifBlank { "termwin@localhost" })

    /** Built-in git (JGit): init, clone, add, commit, push, pull, status, log, remote, branch, checkout, config, login. */
    private fun gitRun(args: List<String>, cwd: File, out: (String) -> Unit): Boolean {
        val sub = args.getOrNull(0)
        if (sub == null || sub == "help") { out("git: init | clone URL | add -A | commit -m \"msg\" | push | pull | status | log | remote add NAME URL | remote -v | branch | checkout [-b] NAME | config user.name X | login TOKEN\n"); return sub != null }
        System.setProperty("user.home", filesDir.path)
        try {
            when (sub) {
                "version", "--version" -> { out("git (JGit, TermWin)\n"); return true }
                "login", "token" -> {
                    val tk = args.getOrNull(1)
                    if (tk == null) { out(if (gitCreds() != null) tr("token saved\n", "token salvo\n") else tr("usage: git login YOUR_GITHUB_TOKEN\n", "uso: git login SEU_TOKEN_DO_GITHUB\n")); return true }
                    if (tk == "clear" || tk == "limpar") { apiRemove("_github"); out(tr("token deleted\n", "token apagado\n")) } else { apiPut("_github", tk); out(tr("token saved\n", "token salvo\n")) }
                    return true
                }
                "config" -> {
                    val k = args.drop(1).filter { !it.startsWith("--") }
                    if (k.size >= 2) { when (k[0]) { "user.name" -> prefs.edit().putString("git_name", k[1]).apply(); "user.email" -> prefs.edit().putString("git_email", k[1]).apply() } }
                    else out("user.name=" + (prefs.getString("git_name", "") ?: "") + "\nuser.email=" + (prefs.getString("git_email", "") ?: "") + "\n")
                    return true
                }
                "init" -> { org.eclipse.jgit.api.Git.init().setDirectory(cwd).setInitialBranch("main").call().close(); out(tr("repository initialized\n", "repositório iniciado\n")); return true }
                "clone" -> {
                    val url = args.getOrNull(1) ?: run { out("usage: git clone URL [folder]\n"); return false }
                    val name = args.getOrNull(2) ?: url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
                    val c = org.eclipse.jgit.api.Git.cloneRepository().setURI(url).setDirectory(File(cwd, name))
                    gitCreds()?.let { c.setCredentialsProvider(it) }
                    c.call().close(); out(tr("cloned into $name\n", "clonado em $name\n")); return true
                }
            }
            var gd = org.eclipse.jgit.storage.file.FileRepositoryBuilder().findGitDir(cwd).gitDir
            if (gd == null && sub == "add") {
                org.eclipse.jgit.api.Git.init().setDirectory(cwd).setInitialBranch("main").call().close()
                out(tr("repository initialized\n", "repositório iniciado\n"))
                gd = org.eclipse.jgit.storage.file.FileRepositoryBuilder().findGitDir(cwd).gitDir
            }
            if (gd == null) { out("fatal: " + tr("not a git repository (use git init or git clone)", "não é um repositório git (use git init ou git clone)") + "\n"); return false }
            val root = gd.parentFile ?: cwd
            val git = org.eclipse.jgit.api.Git.open(root)
            try {
                fun pat(p: String): String {
                    if (p == ".") { val r = cwd.canonicalFile.relativeTo(root.canonicalFile).path; return if (r.isEmpty()) "." else r }
                    val f = (if (p.startsWith("/")) File(p) else File(cwd, p)).canonicalFile
                    val r = f.relativeTo(root.canonicalFile).path
                    return if (r.isEmpty()) "." else r
                }
                when (sub) {
                    "add" -> {
                        val ps = args.drop(1).filter { !it.startsWith("-") }.ifEmpty { if (args.any { it == "-A" || it == "--all" || it == "-u" }) listOf(".") else emptyList() }
                        if (ps.isEmpty()) { out("Nothing specified, nothing added.\n"); return false }
                        for (p in ps) { val x = pat(p); git.add().addFilepattern(x).call(); git.add().setUpdate(true).addFilepattern(x).call() }
                        return true
                    }
                    "commit" -> {
                        var msg: String? = null; var all = false; var i = 1
                        while (i < args.size) {
                            val x = args[i]
                            if (x == "-m" || x == "--message") { msg = args.getOrNull(i + 1); i++ }
                            else if (x == "-am") { all = true; msg = args.getOrNull(i + 1); i++ }
                            else if (x == "-a" || x == "--all") all = true
                            i++
                        }
                        if (msg.isNullOrBlank()) { out("usage: git commit -m \"message\"\n"); return false }
                        if (all) git.add().setUpdate(true).addFilepattern(".").call()
                        val st = git.status().call()
                        val n = st.added.size + st.changed.size + st.removed.size
                        if (n == 0) { out("nothing to commit, working tree clean\n"); return false }
                        val id = git.commit().setAuthor(gitIdent()).setCommitter(gitIdent()).setMessage(msg).call()
                        out("[${git.repository.branch} ${id.name.take(7)}] $msg\n  $n " + tr("file(s) changed\n", "arquivo(s) alterado(s)\n"))
                        return true
                    }
                    "push" -> {
                        val pos = args.drop(1).filter { !it.startsWith("-") }
                        val remote = pos.getOrNull(0) ?: "origin"; val br = pos.getOrNull(1) ?: git.repository.branch
                        val force = args.any { it == "-f" || it == "--force" }
                        val c = git.push().setRemote(remote).setRefSpecs(org.eclipse.jgit.transport.RefSpec("refs/heads/$br:refs/heads/$br")).setForce(force)
                        gitCreds()?.let { c.setCredentialsProvider(it) }
                        var ok = true
                        for (r in c.call()) for (u in r.remoteUpdates) {
                            val st = u.status
                            if (st == org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK) out(tr("pushed $br → $remote\n", "enviado $br → $remote\n"))
                            else if (st == org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE) out("Everything up-to-date\n")
                            else { ok = false; out("✖ push: $st ${u.message ?: ""}\n") }
                        }
                        return ok
                    }
                    "pull" -> {
                        val c = git.pull(); gitCreds()?.let { c.setCredentialsProvider(it) }
                        val r = c.call(); out(if (r.isSuccessful) "ok\n" else "✖ pull: ${r.mergeResult?.mergeStatus ?: "failed"}\n"); return r.isSuccessful
                    }
                    "status" -> {
                        val st = git.status().call()
                        val sb = StringBuilder("branch ${git.repository.branch}\n")
                        st.added.forEach { sb.append("  A  $it\n") }; st.changed.forEach { sb.append("  M  $it\n") }; st.removed.forEach { sb.append("  D  $it\n") }
                        st.modified.forEach { sb.append("  m  $it\n") }; st.missing.forEach { sb.append("  d  $it\n") }; st.untracked.forEach { sb.append("  ?  $it\n") }
                        if (st.isClean) sb.append("nothing to commit, working tree clean\n")
                        out(sb.toString()); return true
                    }
                    "log" -> {
                        val n = args.drop(1).firstNotNullOfOrNull { it.removePrefix("-n").removePrefix("-").toIntOrNull() } ?: 10
                        val sb = StringBuilder(); try { for (c in git.log().setMaxCount(n).call()) sb.append(c.name.take(7)).append(' ').append(c.shortMessage).append('\n') } catch (e: Exception) { }
                        out(if (sb.isEmpty()) tr("no commits yet\n", "nenhum commit ainda\n") else sb.toString()); return true
                    }
                    "remote" -> {
                        val cfg = git.repository.config
                        if (args.getOrNull(1) == "add" || args.getOrNull(1) == "set-url") {
                            val n = args.getOrNull(2); val u = args.getOrNull(3)
                            if (n == null || u == null) { out("usage: git remote add origin URL\n"); return false }
                            cfg.setString("remote", n, "url", u); cfg.setString("remote", n, "fetch", "+refs/heads/*:refs/remotes/$n/*"); cfg.save(); return true
                        }
                        cfg.getSubsections("remote").forEach { out("$it\t${cfg.getString("remote", it, "url")}\n") }; return true
                    }
                    "branch" -> { val cur = git.repository.branch; git.branchList().call().forEach { val n = it.name.removePrefix("refs/heads/"); out((if (n == cur) "* " else "  ") + n + "\n") }; return true }
                    "checkout" -> {
                        val nb = args.getOrNull(1) == "-b"; val n = if (nb) args.getOrNull(2) else args.getOrNull(1)
                        if (n == null) { out("usage: git checkout [-b] name\n"); return false }
                        git.checkout().setName(n).setCreateBranch(nb).call(); out("branch $n\n"); return true
                    }
                    else -> { out("git: '$sub' " + tr("not supported here. Try: git help\n", "não suportado aqui. Tente: git help\n")); return false }
                }
            } finally { git.close() }
        } catch (e: Exception) {
            val m = e.message ?: e.toString()
            out("git: $m\n")
            if (m.contains("remote", true) && (m.contains("specified", true) || m.contains("origin", true) || m.contains("not found", true)))
                out(tr("tip: git remote add origin https://github.com/user/repo\n", "dica: git remote add origin https://github.com/usuario/repo\n"))
            if (m.contains("auth", true) || m.contains("401") || m.contains("403")) ui.post { askGithub(ghTab) }
            if (m.contains("auth", true) || m.contains("401") || m.contains("403")) out(tr("tip: git login YOUR_GITHUB_TOKEN\n", "dica: git login SEU_TOKEN_DO_GITHUB\n"))
            return false
        }
    }

    // ---------- Files / Projects / Copied, creat, open//, copy, smart cd ----------
    private fun filesRoot() = File(dataDir(), "Files").apply { mkdirs() }
    private fun projectsDir() = File(filesRoot(), "Projects").apply { mkdirs() }
    private fun createdDir() = File(filesRoot(), "Files Created").apply { mkdirs() }
    private fun copiedDir() = File(filesRoot(), "Copied").apply { mkdirs() }

    private fun walkCI(base: File, segs: List<String>): File? {
        var cur = base
        for (sg in segs) {
            if (sg == "." || sg.isEmpty()) continue
            if (sg == "..") { cur = cur.parentFile ?: cur; continue }
            val ex = File(cur, sg)
            if (ex.exists()) { cur = ex; continue }
            cur = cur.listFiles()?.firstOrNull { it.name.equals(sg, true) } ?: return null
        }
        return cur
    }

    /** Resolves any path: absolute, relative to the current folder, ~, sdcard, storage/emulated/0/..., ignoring upper/lower case. */
    private fun resolvePath(t: TabData, raw: String): File? = resolveIn(t.cwd, raw)
    private fun resolveIn(cwdPath: String, raw: String): File? {
        val s0 = raw.trim().removeSurrounding("\"").removeSurrounding("'")
        if (s0.isEmpty() || s0 == "~") return filesDir
        val ext = Environment.getExternalStorageDirectory()
        val bases = mutableListOf<Pair<File, String>>()
        val low = s0.lowercase()
        if (low == "copied") return copiedDir()
        if (low.startsWith("copied/")) File(copiedDir(), s0.drop(7)).takeIf { it.exists() }?.let { return it }
        when {
            s0.startsWith("~/") -> bases.add(filesDir to s0.drop(2))
            s0.startsWith("/") -> bases.add(File("/") to s0.drop(1))
            else -> {
                if (low == "storage" || low == "sdcard") return ext
                if (low.startsWith("sdcard/")) bases.add(ext to s0.drop(7))
                if (low.startsWith("storage/shared/")) bases.add(ext to s0.drop(15))
                if (low.startsWith("storage/emulated/")) bases.add(File("/") to s0)
                bases.add(File(cwdPath) to s0); bases.add(ext to s0); bases.add(filesDir to s0); bases.add(File("/") to s0)
            }
        }
        for ((b, r) in bases) {
            val f = walkCI(b, r.split("/")) ?: continue
            if (f.exists()) return try { f.canonicalFile } catch (e: Exception) { f }
        }
        return null
    }

    private val STARTER = mapOf(
        "html" to "<!DOCTYPE html>\n<html lang=\"pt-BR\">\n<head>\n  <meta charset=\"utf-8\">\n  <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n  <title>Meu site</title>\n</head>\n<body>\n  <h1>Olá, mundo!</h1>\n</body>\n</html>\n",
        "css" to "body {\n  margin: 0;\n  font-family: sans-serif;\n}\n",
        "js" to "console.log(\"Olá, mundo!\");\n",
        "py" to "print(\"Olá, mundo!\")\n",
        "json" to "{\n  \"nome\": \"exemplo\"\n}\n",
        "md" to "# Título\n\nTexto aqui.\n",
        "lua" to "print(\"Olá, mundo!\")\n",
        "gd" to "extends Node\n\nfunc _ready():\n    print(\"Olá, mundo!\")\n",
        "sh" to "#!/bin/sh\necho \"Olá, mundo!\"\n",
        "c" to "#include <stdio.h>\n\nint main() {\n    printf(\"Ola, mundo!\\n\");\n    return 0;\n}\n",
        "cpp" to "#include <iostream>\n\nint main() {\n    std::cout << \"Ola, mundo!\" << std::endl;\n    return 0;\n}\n",
        "java" to "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Ola, mundo!\");\n    }\n}\n",
        "kt" to "fun main() {\n    println(\"Ola, mundo!\")\n}\n",
        "php" to "<?php\necho \"Ola, mundo!\";\n",
        "xml" to "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<root>\n</root>\n",
        "hxc" to "", "txt" to "", "yml" to "", "yaml" to "", "toml" to "", "ini" to "", "bat" to "@echo off\necho Ola, mundo!\n",
        "ts" to "console.log(\"Olá, mundo!\");\n", "cs" to "", "rb" to "puts \"Ola, mundo!\"\n", "go" to "package main\n\nimport \"fmt\"\n\nfunc main() {\n    fmt.Println(\"Ola, mundo!\")\n}\n",
        "rs" to "fn main() {\n    println!(\"Ola, mundo!\");\n}\n", "tscn" to "", "gdshader" to "")

    private fun safeSeg(n: String) = n.replace(Regex("[\\\\:*?\"<>|]"), "_").trim().trim('/').ifEmpty { "file" }

    private fun openEditor(t: TabData, f: File) {
        if (f.isDirectory) { err(t, f.name + ": " + tr("is a folder", "é uma pasta")); return }
        if (f.length() > 600_000) { err(t, f.name + ": " + tr("file too big", "arquivo muito grande")); return }
        val init = try { if (f.exists()) f.readText() else "" } catch (e: Exception) { err(t, "${f.name}: ${e.message}"); return }
        val p = panel("✎ ${f.name}", 0.9f)
        p.body.addView(tv(short(f.path), 11f, 0xFF9AA5B1.toInt()))
        val et = field(tr("empty file", "arquivo vazio"), init, true)
        et.typeface = Typeface.MONOSPACE
        et.minLines = 12
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        p.body.addView(et)
        fun save(): Boolean = try {
            f.parentFile?.mkdirs(); f.writeText(et.text.toString())
            append(t, tr("saved ", "salvo ") + short(f.path) + "\n"); true
        } catch (e: Exception) { err(t, "${f.name}: ${e.message}"); false }
        p.button(tr("Close", "Fechar")) { p.close() }
        p.button(tr("Save", "Salvar")) { if (save()) toast(tr("Saved ✔", "Salvo ✔")) }
        p.button(tr("Save & close", "Salvar e fechar"), true) { if (save()) p.close() }
    }

    private fun creatUsage(t: TabData) = err(t, tr(
        "usage: creat//Project//html//index.html   (also: creat//Project//py//main.py — saved in Files/Projects/Project)",
        "uso: creat//Projeto//html//index.html   (também: creat//Projeto//py//main.py — salva em Files/Projects/Projeto)"))

    /** ct file.ext [other.ext…] — creates the file(s) in Files/Files Created and opens the editor on the last one. Without a name it asks. */
    private fun ctCmd(t: TabData, rest: String) {
        val names = splitArgs(rest, emptyMap()).filter { it.isNotBlank() }
        if (names.isEmpty()) {
            promptText(tr("File name (e.g. notes.txt)", "Nome do arquivo (ex.: notas.txt)"), "") { n -> if (n.isNotBlank()) ctMake(t, listOf(n.trim())) }
            return
        }
        ctMake(t, names)
    }

    private fun ctMake(t: TabData, names: List<String>) {
        try {
            val dir = createdDir()
            var last: File? = null
            for (nm in names) {
                var fn = safeSeg(nm.substringAfterLast('/'))
                if (!fn.contains('.')) fn += ".txt"
                val f = File(dir, fn)
                if (!f.exists()) { f.writeText(STARTER[fn.substringAfterLast('.').lowercase()] ?: ""); append(t, tr("created ", "criado ") + short(f.path) + "\n") }
                else append(t, tr("already exists: ", "já existe: ") + short(f.path) + "\n")
                last = f
            }
            last?.let { openEditor(t, it) }
        } catch (e: Exception) { err(t, "ct: ${e.message}") }
    }

    private fun creatCmd(t: TabData, parts: List<String>) {
        if (parts.isEmpty()) { creatUsage(t); return }
        if (parts.size == 1) {
            promptText(tr("Project folder name", "Nome da pasta do projeto"), "") { proj -> creatFinish(t, proj, "", parts[0]) }
            return
        }
        if (parts.size == 2) creatFinish(t, parts[0], "", parts[1]) else creatFinish(t, parts[0], parts[1], parts[2])
    }

    private fun creatFinish(t: TabData, project: String, type0: String, file0: String) {
        var type = type0.trim().trimStart('.').lowercase()
        var fn = file0.trim()
        if (fn.startsWith(".") && fn.indexOf('.', 1) > 0) fn = fn.drop(1)          // ".ex.html" -> "ex.html"
        else if (fn.startsWith(".")) { if (type.isEmpty()) type = fn.drop(1).lowercase(); fn = "index$fn" }
        if (fn.contains('/')) fn = fn.substringAfterLast('/')
        if (type.isNotEmpty() && !fn.contains('.')) fn = "$fn.$type"
        if (type.isEmpty()) type = fn.substringAfterLast('.', "").lowercase()
        fn = safeSeg(fn)
        val dir: File = when {
            project == "." -> File(t.cwd)
            project.startsWith("/") -> File(project)
            project.startsWith("~/") -> File(filesDir, project.drop(2))
            else -> project.split("/").filter { it.isNotBlank() && it != "." && it != ".." }.fold(projectsDir()) { a, sg -> File(a, safeSeg(sg)) }
        }
        try {
            dir.mkdirs()
            val f = File(dir, fn)
            if (!f.exists()) { f.writeText(STARTER[type] ?: ""); append(t, tr("created ", "criado ") + short(f.path) + "\n") }
            else append(t, tr("already exists, opening: ", "já existe, abrindo: ") + short(f.path) + "\n")
            openEditor(t, f)
        } catch (e: Exception) { err(t, "creat: ${e.message}") }
    }

    private fun openFileCmd(t: TabData, parts: List<String>) {
        if (parts.isEmpty()) { err(t, tr("usage: open//Project//index.html", "uso: open//Projeto//index.html")); return }
        fun dirOf(n: String): File? {
            val a = projectsDir().listFiles()?.firstOrNull { it.isDirectory && it.name.equals(n, true) }
            if (a != null) return a
            val b = copiedDir().listFiles()?.firstOrNull { it.isDirectory && it.name.equals(n, true) }
            if (b != null) return b
            return resolvePath(t, n)?.takeIf { it.isDirectory }
        }
        if (parts.size == 1) {
            val one = parts[0]
            val direct = resolvePath(t, one)
            if (direct != null && direct.isFile) { openEditor(t, direct); return }
            val d = dirOf(one)
            if (d != null) { append(t, short(d.path) + "\n" + (d.listFiles()?.sortedBy { it.name }?.joinToString("") { "  " + it.name + (if (it.isDirectory) "/" else "") + "\n" } ?: "")); return }
            val hit = projectsDir().listFiles()?.filter { it.isDirectory }?.mapNotNull { walkCI(it, listOf(one))?.takeIf { f -> f.isFile } }?.firstOrNull()
            if (hit != null) openEditor(t, hit) else err(t, "open: $one: " + tr("not found", "não encontrado"))
            return
        }
        val d = dirOf(parts[0])
        if (d == null) { err(t, "open: ${parts[0]}: " + tr("folder not found in Projects/Copied", "pasta não encontrada em Projects/Copied")); return }
        val f = walkCI(d, parts.drop(1).joinToString("/").split("/"))
        if (f == null || !f.exists()) {
            err(t, "open: ${parts.drop(1).joinToString("/")}: " + tr("not found. Files: ", "não encontrado. Arquivos: ") + (d.listFiles()?.joinToString(", ") { it.name } ?: ""))
            return
        }
        if (f.isDirectory) append(t, short(f.path) + "\n" + (f.listFiles()?.sortedBy { it.name }?.joinToString("") { "  " + it.name + (if (it.isDirectory) "/" else "") + "\n" } ?: "")) else openEditor(t, f)
    }

    // ---------- Roblox//file.rbxl : find the file, publish it as a Roblox model, open the store link in a window ----------
    private fun robloxKey(): String? = apiGet("_roblox")?.takeIf { it.isNotBlank() }
    private fun robloxOwner(): String = prefs.getString("roblox_owner", "") ?: ""

    private fun robloxCmd(t: TabData, rest: String) {
        val arg = rest.split("//").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("//").trim().removeSurrounding("\"").removeSurrounding("'")
        if (arg.isEmpty()) { err(t, tr("usage: Roblox//file.rbxl   (first time: Roblox//key)", "uso: Roblox//arquivo.rbxl   (primeira vez: Roblox//chave)")); return }
        if (arg.lowercase() in listOf("key", "chave", "setup", "config", "login")) { showRobloxKey(); return }
        if (robloxKey() == null || robloxOwner().isEmpty()) {
            append(t, tr("Roblox needs your API key and your ID first. Fill them in the window, then run the command again.\n", "O Roblox precisa da sua chave de API e do seu ID antes. Preencha na janela e rode o comando de novo.\n"))
            showRobloxKey(); return
        }
        var name = arg
        if (name.substringAfterLast('/').substringAfterLast('.', "").isEmpty()) name += ".rbxl"
        val ext = name.substringAfterLast('.').lowercase()
        if (ext !in listOf("rbxl", "rbxm")) {
            err(t, tr("Only .rbxl and .rbxm files can be published (not .$ext).", "Só dá para publicar arquivos .rbxl e .rbxm (não .$ext).")); return
        }
        append(t, tr("searching $name …\n", "procurando $name …\n"))
        busy(1)
        thread {
            try {
                val f = findRobloxFile(name, t)
                if (f == null) { ui.post { err(t, tr("file not found: $name", "arquivo não encontrado: $name")) }; return@thread }
                if (f.length() > 30L * 1024 * 1024) { ui.post { err(t, tr("file is bigger than 30 MB (Roblox limit)", "arquivo maior que 30 MB (limite do Roblox)")) }; return@thread }
                ui.post { append(t, tr("found: ${short(f.path)}  (${f.length() / 1024} KB)\nuploading to Roblox …\n", "achei: ${short(f.path)}  (${f.length() / 1024} KB)\nenviando para o Roblox …\n")) }
                robloxPublish(t, f)
            } catch (e: Exception) {
                ui.post { err(t, "Roblox: ${e.message}") }
            } finally { ui.post { busy(-1) } }
        }
    }

    private fun findRobloxFile(name: String, t: TabData): File? {
        if (name.contains('/')) resolvePath(t, name)?.let { if (it.isFile) return it }
        val base = name.substringAfterLast('/')
        val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val roots = listOf(File(t.cwd), downloadsDir(), filesRoot(), dataDir(), File(pub, "Downloaded"), pub, docs).filter { it.isDirectory }
        for (r in roots) r.walkTopDown().maxDepth(8).firstOrNull { it.isFile && it.name.equals(base, true) }?.let { return it }
        return findAnywhere(base, t)?.takeIf { it.isFile }
    }

    /** Open Cloud Assets API: POST /assets/v1/assets (multipart) -> operation -> poll until it has the assetId. */
    private fun robloxPublish(t: TabData, f: File) {
        val key = robloxKey() ?: return
        val owner = robloxOwner()
        val creator = if (owner.startsWith("g", true)) JSONObject().put("groupId", owner.drop(1)) else JSONObject().put("userId", owner)
        val title = f.nameWithoutExtension.take(50).ifBlank { "TermWin model" }
        val reqJson = JSONObject().put("assetType", "Model").put("displayName", title)
            .put("description", "Uploaded from TermWin").put("creationContext", JSONObject().put("creator", creator)).toString()
        val boundary = "----TermWin" + System.currentTimeMillis()
        val head = ("--$boundary\r\nContent-Disposition: form-data; name=\"request\"\r\nContent-Type: application/json\r\n\r\n$reqJson\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"fileContent\"; filename=\"${title.replace("\"", "")}.rbxm\"\r\nContent-Type: model/x-rbxm\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val c = java.net.URL("https://apis.roblox.com/assets/v1/assets").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20000; c.readTimeout = 120000
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setFixedLengthStreamingMode(head.size.toLong() + f.length() + tail.size)
        c.outputStream.use { o -> o.write(head); f.inputStream().use { it.copyTo(o) }; o.write(tail) }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) { robloxFail(t, code, body); return }
        var op = try { JSONObject(body) } catch (e: Exception) { null }
        val opId = op?.optString("operationId")?.ifEmpty { op?.optString("path")?.substringAfterLast('/') } ?: ""
        var tries = 0
        while (op != null && !op.optBoolean("done", false) && opId.isNotEmpty() && tries++ < 60) {
            Thread.sleep(2000)
            val g = java.net.URL("https://apis.roblox.com/assets/v1/operations/$opId").openConnection() as java.net.HttpURLConnection
            g.setRequestProperty("x-api-key", key); g.connectTimeout = 20000; g.readTimeout = 30000
            val gc = g.responseCode
            val gb = (if (gc in 200..299) g.inputStream else g.errorStream)?.bufferedReader()?.readText() ?: ""
            if (gc !in 200..299) { robloxFail(t, gc, gb); return }
            op = try { JSONObject(gb) } catch (e: Exception) { null }
        }
        if (op == null || !op.optBoolean("done", false)) { ui.post { err(t, tr("Roblox is still processing. Wait a bit and run the command again.", "O Roblox ainda está processando. Espere um pouco e rode o comando de novo.")) }; return }
        val err0 = op.optJSONObject("error")
        if (err0 != null) { robloxFail(t, err0.optInt("code", 0), err0.optString("message")); return }
        val id = op.optJSONObject("response")?.optString("assetId").orEmpty()
        if (id.isEmpty()) { ui.post { err(t, "Roblox: " + tr("no asset id came back", "não veio o ID do modelo")) }; return }
        val link = "https://create.roblox.com/store/asset/$id"
        ui.post {
            append(t, tr("published ✔  model ID: $id\n$link\nopening the model page in a window …\n", "publicado ✔  ID do modelo: $id\n$link\nabrindo a página do modelo numa janela …\n"))
            openWebWindow("Roblox", link)
        }
    }

    private fun robloxFail(t: TabData, code: Int, body: String) {
        val msg = (try { JSONObject(body).let { it.optString("message").ifEmpty { it.optString("error") } } } catch (e: Exception) { "" }).ifEmpty { body.take(200) }
        val hint = when (code) {
            401, 403 -> tr(" — check the key: permission Assets (read+write), and the IP list must allow 0.0.0.0/0. Fix it with Roblox//key", " — confira a chave: permissão Assets (ler+escrever) e a lista de IP precisa aceitar 0.0.0.0/0. Corrija com Roblox//chave")
            400 -> tr(" — Roblox may not accept a place (.rbxl) as a model. Save the content as .rbxm and try again", " — o Roblox pode não aceitar um lugar (.rbxl) como modelo. Salve o conteúdo como .rbxm e tente de novo")
            429 -> tr(" — upload limit reached, try later", " — limite de envios atingido, tente mais tarde")
            else -> ""
        }
        ui.post { err(t, "Roblox [$code]: $msg$hint") }
    }

    private fun showRobloxKey() {
        val p = panel(tr("Roblox publish", "Publicar no Roblox"), 0.7f)
        p.body.addView(tv(tr("1) Open the Creator Hub keys page, create an API key with the Assets API (read + write) permission and IP 0.0.0.0/0. 2) Paste the key and your Roblox user ID (for a group, write g + the group number, e.g. g1234). Your account must be ID-verified to upload.",
            "1) Abra a página de chaves do Creator Hub, crie uma chave de API com a permissão da Assets API (ler + escrever) e IP 0.0.0.0/0. 2) Cole a chave e o seu ID de usuário do Roblox (para grupo, escreva g + o número do grupo, ex.: g1234). Sua conta precisa ter o ID verificado para enviar."), 12f, 0xFF9AA5B1.toInt()))
        val saved = robloxKey()
        p.body.addView(tv(if (saved == null) tr("Status: no key saved", "Status: sem chave salva") else tr("Status: key saved (…${saved.takeLast(4)}), ID ${robloxOwner()}", "Status: chave salva (…${saved.takeLast(4)}), ID ${robloxOwner()}"),
            13f, if (saved == null) 0xFFFFB454.toInt() else GREEN).apply { setPadding(0, dp(8), 0, 0) })
        val kv = field(tr("Roblox API key", "Chave de API do Roblox"))
        kv.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        p.body.addView(kv)
        val idv = field(tr("Your Roblox user ID (numbers)", "Seu ID de usuário do Roblox (números)"), robloxOwner())
        p.body.addView(idv)
        val msg = tv("", 12f, 0xFFFFB454.toInt()).apply { setPadding(0, dp(8), 0, 0) }
        p.body.addView(msg)
        p.button(tr("Get key", "Pegar chave")) { openWebWindow("Roblox", "https://create.roblox.com/dashboard/credentials") }
        p.button(tr("Close", "Fechar")) { p.close() }
        p.button(tr("Save", "Salvar"), true) {
            val k = kv.text.toString().trim().ifEmpty { saved ?: "" }
            val id = idv.text.toString().trim().replace(" ", "")
            if (k.isEmpty()) { msg.text = tr("Paste the API key", "Cole a chave de API"); return@button }
            if (!Regex("^[gG]?\\d{1,20}$").matches(id)) { msg.text = tr("The ID must be only numbers (group: g + number)", "O ID deve ser só números (grupo: g + número)"); return@button }
            apiPut("_roblox", k); prefs.edit().putString("roblox_owner", id).apply()
            toast(tr("Roblox saved ✔ — now run: Roblox//file.rbxl", "Roblox salvo ✔ — agora rode: Roblox//arquivo.rbxl")); p.close()
        }
    }

    // ---------- apk//open//name.apk : find the APK, open the app in a window, keep its folder in Files/App data ----------
    private val RE_APK = Regex("^(?:apk|akp)\\s*//\\s*(?:open|onpen|abrir)\\s*//\\s*(.+)$", RegexOption.IGNORE_CASE)
    private fun appDataDir() = File(filesRoot(), "App data").apply { mkdirs() }

    private fun apkCmd(t: TabData, line: String): Boolean {
        if (Regex("^(janela|window)\\s+off$", RegexOption.IGNORE_CASE).matches(line.trim())) {
            thread { su("settings delete global overlay_display_devices"); ui.post { append(t, tr("virtual window closed\n", "janela virtual fechada\n")) } }
            return true
        }
        if (Regex("^(janela|window)\\s+(diag|info)$", RegexOption.IGNORE_CASE).matches(line.trim())) {
            thread {
                val id = su("id"); val feat = su("pm has-feature android.software.freeform_window_management").second
                val sets = su("settings get global enable_freeform_support; settings get global force_resizable_activities; settings get global overlay_display_devices").second.replace("\n", " | ")
                ui.post { append(t, "shizuku: running=${shzUp()} allowed=${shzGranted()} uid=${shzUid()}\nsu/shizuku: ${id.first} ${id.second.take(80)}\nandroid ${Build.VERSION.RELEASE} api ${Build.VERSION.SDK_INT} • ${Build.MANUFACTURER} ${Build.MODEL}\nfreeform feature: $feat\nsettings: $sets\ndisplays: ${displayIds()}\n") }
            }
            return true
        }
        if (Regex("^shizuku(\\s+(status|perm|allow|permissao|permissão))?$", RegexOption.IGNORE_CASE).matches(line.trim())) {
            thread {
                val inst = shzInstalled(); val up = shzUp(); val ok = shzGranted()
                if (up && !ok) ui.post { shzAsk() }
                val who = if (up && ok) su("id").second.take(70) else ""
                ui.post {
                    append(t, tr("Shizuku app: ${if (inst) "installed" else "NOT installed (download it from GitHub: RikkaApps/Shizuku)"}\n", "app Shizuku: ${if (inst) "instalado" else "NÃO instalado (baixe no GitHub: RikkaApps/Shizuku)"}\n"))
                    append(t, tr("service: ${if (up) "running" else "not running — open Shizuku and tap Start"}\n", "serviço: ${if (up) "rodando" else "parado — abra o Shizuku e toque em Iniciar"}\n"))
                    if (up) append(t, tr("TermWin permission: ${if (ok) "allowed ✔" else "asked — tap Allow in the Shizuku dialog, then run: shizuku"}\n", "permissão do TermWin: ${if (ok) "liberada ✔" else "pedida — toque em Permitir na janela do Shizuku e rode de novo: shizuku"}\n"))
                    if (up && ok) append(t, tr("running as: $who (uid ${shzUid()})\n", "rodando como: $who (uid ${shzUid()})\n"))
                }
            }
            return true
        }
        val m = RE_APK.matchEntire(line.trim()) ?: return false
        var name = m.groupValues[1].trim().trim('"')
        if (listOf(".apk", ".apkm", ".xapk", ".apks").none { name.endsWith(it, true) }) name += ".apk"
        append(t, tr("searching $name …\n", "procurando $name …\n"))
        thread {
            val f = try { findApk(name, t) } catch (e: Exception) { null }
            ui.post { if (f == null) err(t, tr("apk not found: $name", "apk não encontrado: $name")) else openApk(t, f) }
        }
        return true
    }

    private fun findApk(name: String, t: TabData): File? {
        if (name.contains('/')) resolvePath(t, name)?.let { if (it.isFile) return it }
        val base = name.substringAfterLast('/')
        val roots = listOf(downloadsDir(), filesRoot(), dataDir(), File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Downloaded"), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)).filter { it.isDirectory }
        for (r in roots) r.walkTopDown().maxDepth(8).firstOrNull { it.isFile && it.name.equals(base, true) }?.let { return it }
        val part = base.substringBeforeLast('.', base)
        val exts = setOf("apk", "apkm", "xapk", "apks")
        for (r in roots) r.walkTopDown().maxDepth(8).firstOrNull { it.isFile && it.extension.lowercase() in exts && it.name.contains(part, true) }?.let { return it }
        return findAnywhere(base, t)?.takeIf { it.isFile }
    }

    private fun unzipApk(f: File, dir: File): Int {
        var n = 0
        val base = dir.canonicalPath + File.separator
        java.util.zip.ZipFile(f).use { z ->
            for (e in z.entries().asSequence()) {
                val out = File(dir, e.name)
                if (!out.canonicalPath.startsWith(base)) continue
                if (e.isDirectory) out.mkdirs()
                else { out.parentFile?.mkdirs(); z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }; n++ }
            }
        }
        return n
    }

    @Suppress("DEPRECATION")
    private fun openApk(t: TabData, f: File) {
        val pm = packageManager
        val info = try { pm.getPackageArchiveInfo(f.path, 0)?.also { it.applicationInfo?.sourceDir = f.path; it.applicationInfo?.publicSourceDir = f.path } } catch (e: Exception) { null }
        if (info == null) {
            if (isBundle(f)) openBundle(t, f) else err(t, tr("not a valid apk: ${f.name}", "apk inválido: ${f.name}"))
            return
        }
        val pkg = info.packageName
        val label = try { info.applicationInfo?.loadLabel(pm)?.toString() } catch (e: Exception) { null } ?: f.nameWithoutExtension
        append(t, "$label  ($pkg)\n${short(f.path)}\n")
        // the APK's folder goes to Files/App data/<name>
        val dir = File(appDataDir(), safeSeg(f.nameWithoutExtension))
        if (dir.isDirectory && (dir.list()?.any { !it.equals(f.name, true) } == true)) append(t, tr("app data: ${short(dir.path)}\n", "app data: ${short(dir.path)}\n"))
        else thread {
            try {
                dir.mkdirs()
                val n = unzipApk(f, dir)
                ui.post { append(t, tr("app data: $n files → ${short(dir.path)}\n", "app data: $n arquivos → ${short(dir.path)}\n")) }
            } catch (e: Exception) { ui.post { err(t, "app data: ${e.message}") } }
        }
        showAppPanel(t, label, pkg, info, f, null)
    }

    /** Installs a single (non-split) APK through the system installer. */
    private fun installSingle(t: TabData, label: String, f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            append(t, tr("Allow TermWin to install apps, then tap Install again.\n", "Permita o TermWin instalar apps e toque em Instalar de novo.\n"))
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (e: Exception) { }
            return
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fp", f)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            append(t, tr("installer opened for $label\n", "instalador aberto para $label\n"))
        } catch (e: Exception) { err(t, "apk install: ${e.message}") }
    }

    // ---------- root: floating (freeform) windows ----------
    // ---------- Shizuku: runs the same commands as root, without needing su ----------
    private var shzListener = false
    private var shzAskedAt = 0L
    private fun shzUp(): Boolean = try { rikka.shizuku.Shizuku.pingBinder() } catch (e: Throwable) { false }
    private fun shzGranted(): Boolean = try { !rikka.shizuku.Shizuku.isPreV11() && rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED } catch (e: Throwable) { false }
    private fun shzUid(): Int = try { rikka.shizuku.Shizuku.getUid() } catch (e: Throwable) { -1 }
    private fun shzInstalled(): Boolean = try { packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true } catch (e: Exception) { false }
    /** Shows Shizuku's "Allow TermWin?" dialog (must run on the UI thread). */
    private fun shzAsk() {
        try {
            if (!shzListener) {
                shzListener = true
                rikka.shizuku.Shizuku.addRequestPermissionResultListener { _, r ->
                    ui.post { toast(if (r == android.content.pm.PackageManager.PERMISSION_GRANTED) tr("Shizuku allowed ✔", "Shizuku liberado ✔") else tr("Shizuku denied", "Shizuku negado")) }
                }
            }
            rikka.shizuku.Shizuku.requestPermission(4242)
        } catch (e: Throwable) { }
    }
    /** Runs a shell command through Shizuku (root or adb/shell, depending on how Shizuku was started). null = Shizuku not usable. */
    private fun shzRun(cmd: String): Pair<Int, String>? {
        if (!shzUp() || !shzGranted()) return null
        return try {
            val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            val pr = m.invoke(null, arrayOf("sh", "-c", "( $cmd ) 2>&1"), null, null) as Process
            val out = pr.inputStream.bufferedReader().readText().trim()
            val code = if (pr.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) pr.exitValue() else { pr.destroy(); -1 }
            code to out
        } catch (e: Throwable) { null }
    }

    private var suBin: String? = null
    private fun su(cmd: String): Pair<Int, String> {
        shzRun(cmd)?.let { return it }
        if (shzUp() && !shzGranted() && System.currentTimeMillis() - shzAskedAt > 60_000) { shzAskedAt = System.currentTimeMillis(); ui.post { shzAsk() } }
        val cands = suBin?.let { listOf(it) } ?: listOf("su", "/system/bin/su", "/system/xbin/su", "/sbin/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/debug_ramdisk/su", "/product/bin/su")
        var last = "su não encontrado"
        for (bin in cands) {
            try {
                val pr = ProcessBuilder(bin, "-c", cmd).redirectErrorStream(true).start()
                val out = pr.inputStream.bufferedReader().readText().trim()
                val code = if (pr.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) pr.exitValue() else { pr.destroy(); -1 }
                suBin = bin
                return code to out
            } catch (e: Exception) { last = e.message ?: last }
        }
        return -1 to last
    }

    /** null = no root; true = floating windows already on; false = just turned on (needs one reboot). */
    private fun ensureFreeform(): Boolean? {
        val id = su("id")
        if (id.first != 0 || !(id.second.contains("uid=0") || id.second.contains("uid=2000"))) return null
        val a = su("settings get global enable_freeform_support").second
        val b = su("settings get global force_resizable_activities").second
        if (a == "1" && b == "1") return true
        su("settings put global development_settings_enabled 1; settings put global enable_freeform_support 1; settings put global force_resizable_activities 1")
        return false
    }

    /** Opens an installed app as a floating window (root). Tries freeform mode, then a virtual overlay screen. */
    private fun openAsWindow(t: TabData, pkg: String, label: String) {
        append(t, tr("opening $label…\n", "abrindo $label…\n"))
        thread {
            val ff = ensureFreeform()
            val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
            ui.post { append(t, "TermWin v$ver\n") }
            if (ff == null) {
                val d = su("id")
                ui.post { append(t, tr("TermWin has no root and no Shizuku (${d.first} ${d.second.take(120)}). Start Shizuku, then run: shizuku\nOpening as a normal app.\n", "O TermWin está sem root e sem Shizuku (${d.first} ${d.second.take(120)}). Inicie o Shizuku e rode: shizuku\nAbrindo como app normal.\n")); launchPkg(t, pkg, label) }
                return@thread
            }
            val r = try { rootWindowLaunch(pkg) { m -> ui.post { append(t, m + "\n") } } } catch (e: Exception) { e.message ?: "erro" }
            ui.post {
                if (r != "freeform" && r != "overlay") { err(t, tr("could not open in a window: $r", "não consegui abrir em janela: $r")); launchPkg(t, pkg, label) }
            }
        }
    }

    /** Task mode (freeform / fullscreen / ...) and id of the app's task, read from dumpsys. */
    private fun taskInfo(pkg: String): Pair<String, Int>? {
        val ln = su("dumpsys activity activities").second.lines().firstOrNull { it.contains("Task{") && it.contains(pkg) } ?: return null
        val mode = Regex("mode=([a-z\\-]+)").find(ln)?.groupValues?.get(1) ?: "?"
        val id = Regex("#(\\d+)").find(ln)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        return mode to id
    }

    /** Which display the app's task is on, from `am stack list`. */
    private fun taskDisplay(pkg: String): Int? {
        for (cmd in listOf("cmd activity stack list", "am stack list")) {
            val out = su(cmd).second
            var disp: Int? = null
            for (ln in out.lines()) {
                val m = Regex("displayId=(\\d+)").find(ln)
                if (m != null && !ln.contains("taskId=")) disp = m.groupValues[1].toInt()
                if (ln.contains("taskId=") && ln.contains("$pkg/")) return m?.groupValues?.get(1)?.toInt() ?: disp
            }
        }
        return null
    }

    private fun displayIds(): Set<Int> {
        val out = su("dumpsys display").second
        val a = Regex("(?m)^\\s*Display (\\d+):").findAll(out).map { it.groupValues[1].toInt() }
        val b = Regex("mDisplayId=(\\d+)").findAll(out).map { it.groupValues[1].toInt() }
        return (a + b).toSet()
    }

    /** Root: 1) real freeform window (needs the system feature) 2) virtual overlay screen (a movable window on top of the phone screen). Returns "freeform", "overlay" or an error text. */
    private fun rootWindowLaunch(pkg: String, say: (String) -> Unit): String {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return "app sem atividade de launcher"
        val cn = launch.component?.flattenToShortString() ?: return "app sem componente"
        val feat = su("pm has-feature android.software.freeform_window_management").second.contains("true")
        say("android ${Build.VERSION.RELEASE} (api ${Build.VERSION.SDK_INT}) • freeform: $feat")
        su("am force-stop $pkg")
        if (feat) {
            val dm = resources.displayMetrics
            val sw = maxOf(dm.widthPixels, dm.heightPixels); val sh = minOf(dm.widthPixels, dm.heightPixels)
            val w = (sw * 0.78).toInt(); val h = (sh * 0.80).toInt(); val l = (sw - w) / 2; val tp = (sh - h) / 2
            val st = su("am start --user 0 --windowingMode 5 -n $cn")
            Thread.sleep(1500)
            val info = taskInfo(pkg)
            if (info != null && info.first == "freeform") {
                if (info.second >= 0) for (c in listOf("am task resize ${info.second} $l $tp ${l + w} ${tp + h}", "cmd activity task resize ${info.second} $l $tp ${l + w} ${tp + h}")) { if (su(c).first == 0) break }
                say("janela flutuante ativa")
                return "freeform"
            }
            say("freeform: o Android abriu em modo '${info?.first}' ${st.second.take(100)} — tentando tela virtual…")
            su("am force-stop $pkg")
        } else say("sem janelas flutuantes no sistema — usando tela virtual (não precisa reiniciar)…")

        // virtual overlay screen: shows a second screen as a window you can drag/pinch over the phone screen
        su("settings put global force_resizable_activities 1")
        val before = displayIds()
        if (before.none { it > 0 }) { su("settings put global overlay_display_devices 1280x720/240"); Thread.sleep(2500) }
        val now = displayIds().filter { it > 0 }
        val id = (now.toSet() - before).maxOrNull() ?: now.maxOrNull() ?: return "a tela virtual não foi criada (a ROM bloqueia overlay_display_devices)"
        fun startOn(): String = su("am start --user 0 --display $id -n $cn").second
        var out = startOn()
        if (out.contains("Error", true) || out.contains("Exception", true)) return out.take(200)
        Thread.sleep(1500)
        var d = taskDisplay(pkg)
        say("tela virtual: $id • o app ficou na tela: ${d ?: "?"}")
        if (d != null && d != id) {
            su("settings put global enable_non_resizable_multi_window 1; settings put global force_resizable_activities 1")
            su("am force-stop $pkg"); Thread.sleep(600)
            out = startOn(); Thread.sleep(1500)
            d = taskDisplay(pkg)
            say("2ª tentativa • o app ficou na tela: ${d ?: "?"}")
            if (d != null && d != id) return "o Android jogou o app de volta para a tela principal (ele não aceita multi-tela). Reinicie o celular uma vez (force_resizable foi ligado) e tente de novo. Saída: ${out.take(100)}"
        }
        say("tela virtual $id criada: o jogo está na janela que apareceu na tela (arraste para mover, pince para ajustar). Para fechar: janela off")
        return "overlay"
    }

    /** apk//open// only finds the file and shows this window. Nothing is opened or installed until you tap a button. */
    private fun showAppPanel(t: TabData, label: String, pkg: String, info: android.content.pm.PackageInfo, src: File, splits: List<File>?) {
        val installed = try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
        val p = panel("📦 $label", 0.6f)
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        try {
            val ic = ImageView(this)
            ic.setImageDrawable(info.applicationInfo?.loadIcon(packageManager))
            head.addView(ic, LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(12) })
        } catch (e: Exception) { }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv(label, 17f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD })
        col.addView(tv(pkg, 12f, 0xFF9AA5B1.toInt()))
        col.addView(tv(tr("version ", "versão ") + (info.versionName ?: "?") + (if (splits != null) "  •  ${splits.size} apk" else ""), 12f, 0xFF9AA5B1.toInt()))
        head.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        p.body.addView(head)
        p.body.addView(tv(tr("file: ", "arquivo: ") + short(src.path), 11f, 0xFF6B7785.toInt()).apply { setPadding(0, dp(8), 0, 0) })
        p.body.addView(tv(tr("app data: ", "app data: ") + short(File(appDataDir(), safeSeg(src.nameWithoutExtension)).path), 11f, 0xFF6B7785.toInt()))
        p.body.addView(tv(if (installed) tr("Installed on this phone.", "Instalado neste celular.") else tr("Not installed.", "Não instalado."), 13f, if (installed) GREEN else 0xFFFFB454.toInt()).apply { setPadding(0, dp(10), 0, dp(4)) })
        p.body.addView(tv(tr("With root or Shizuku, TermWin turns on Android floating windows (one reboot the first time) and opens the app as a movable window you can play in.", "Com root ou Shizuku, o TermWin liga as janelas flutuantes do Android (um reinício na primeira vez) e abre o app como uma janela movível em que você pode jogar."), 11f, 0xFF9AA5B1.toInt()))
        p.button(tr("Close", "Fechar")) { p.close() }
        p.button(if (installed) tr("Open app", "Abrir app") else tr("Install", "Instalar"), true) {
            p.close()
            if (installed) openAsWindow(t, pkg, label)
            else if (splits != null) installSplits(t, pkg, label, splits)
            else installSingle(t, label, src)
        }
    }

    /** Opens an installed app in a window-sized launch (real floating window only where the phone supports it). */
    private fun launchPkg(t: TabData, pkg: String, label: String): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        try {
            val dm = resources.displayMetrics
            val w = (dm.widthPixels * 0.88).toInt(); val h = (dm.heightPixels * 0.86).toInt()
            val l = (dm.widthPixels - w) / 2; val tp = (dm.heightPixels - h) / 2
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opts = ActivityOptions.makeBasic().setLaunchBounds(android.graphics.Rect(l, tp, l + w, tp + h))
            startActivity(launch, opts.toBundle())
            append(t, tr("opening $label in a window…\n", "abrindo $label em uma janela…\n"))
        } catch (e: Exception) { err(t, "apk: ${e.message}") }
        return true
    }

    // ---------- APKMirror bundles (.apkm renamed to .apk): base.apk + split_config.*.apk ----------
    private fun isBundle(f: File): Boolean = try {
        java.util.zip.ZipFile(f).use { z -> z.entries().asSequence().any { !it.isDirectory && it.name.endsWith(".apk", true) } }
    } catch (e: Exception) { false }

    /** base.apk + the splits that fit this phone (its CPU, screen density and language). */
    private fun pickSplits(dir: File): List<File> {
        val apks = dir.walkTopDown().filter { it.isFile && it.extension.equals("apk", true) }.toList()
        val abis = Build.SUPPORTED_ABIS.map { it.replace('-', '_').lowercase() }
        val abiAll = setOf("arm64_v8a", "armeabi_v7a", "armeabi", "x86", "x86_64", "mips", "mips64")
        val dpiAll = listOf("ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi")
        val d = resources.displayMetrics.densityDpi
        val myDpi = when { d <= 120 -> "ldpi"; d <= 160 -> "mdpi"; d <= 213 -> "tvdpi"; d <= 240 -> "hdpi"; d <= 320 -> "xhdpi"; d <= 480 -> "xxhdpi"; else -> "xxxhdpi" }
        val lang = java.util.Locale.getDefault().language.lowercase()
        val rLang = Regex("^[a-z]{2,3}(_[a-z0-9]+)?$")
        val abi = abis.firstOrNull { a -> apks.any { it.name.lowercase().contains("." + a + ".") || it.name.lowercase().endsWith("." + a + ".apk") } }
        val out = mutableListOf<File>()
        for (a in apks) {
            val n = a.name.lowercase().removeSuffix(".apk")
            val tok = Regex("^split_config\\.(.+)$").matchEntire(n)?.groupValues?.get(1)
            when {
                n == "base" || tok == null -> out.add(a)
                tok in abiAll -> if (tok == abi) out.add(a)
                tok in dpiAll -> if (tok == myDpi) out.add(a)
                rLang.matches(tok) -> if (tok.substringBefore('_') == lang || tok == "en") out.add(a)
                else -> out.add(a)
            }
        }
        val base = out.firstOrNull { it.name.equals("base.apk", true) } ?: apks.firstOrNull { it.name.equals("base.apk", true) }
        return if (base != null && base !in out) listOf(base) + out else out
    }

    private fun openBundle(t: TabData, f: File) {
        append(t, tr("bundle (split APKs): extracting…\n", "pacote (APKs divididos): extraindo…\n"))
        thread {
            try {
                val dir = File(appDataDir(), safeSeg(f.nameWithoutExtension)).apply { mkdirs() }
                if (dir.walkTopDown().none { it.isFile && it.extension.equals("apk", true) && !it.equals(f) }) unzipApk(f, dir)
                val files = pickSplits(dir).filter { it.canonicalPath != f.canonicalPath }
                val base = files.firstOrNull { it.name.equals("base.apk", true) } ?: files.firstOrNull()
                @Suppress("DEPRECATION")
                val info = base?.let { b -> packageManager.getPackageArchiveInfo(b.path, 0)?.also { it.applicationInfo?.sourceDir = b.path; it.applicationInfo?.publicSourceDir = b.path } }
                if (base == null || info == null) { ui.post { err(t, tr("could not read base.apk inside ${f.name}", "não consegui ler o base.apk dentro de ${f.name}")) }; return@thread }
                val label = try { info.applicationInfo?.loadLabel(packageManager)?.toString() } catch (e: Exception) { null } ?: f.nameWithoutExtension
                ui.post {
                    append(t, "$label  (${info.packageName})\napp data: ${short(dir.path)}  (${files.size} apk)\n")
                    showAppPanel(t, label, info.packageName, info, f, files)
                }
            } catch (e: Exception) { ui.post { err(t, "apk: ${e.message}") } }
        }
    }

    private var instRx: android.content.BroadcastReceiver? = null
    private var instTab: TabData? = null
    private var instPkg = ""
    private var instLabel = ""

    private fun ensureInstallReceiver() {
        if (instRx != null) return
        val rx = object : android.content.BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(c: Context, i: Intent) {
                val st = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1)
                val t = instTab ?: tabs.getOrNull(cur) ?: return
                when (st) {
                    android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val ni = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        try { ni?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); if (ni != null) startActivity(ni) } catch (e: Exception) { err(t, "install: ${e.message}") }
                    }
                    android.content.pm.PackageInstaller.STATUS_SUCCESS -> {
                        append(t, tr("installed ✔\n", "instalado ✔\n"))
                        if (!launchPkg(t, instPkg, instLabel)) append(t, tr("run the command again to open it\n", "rode o comando de novo para abrir\n"))
                    }
                    else -> {
                        val m = i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $st"
                        err(t, tr("install failed: $m", "falha na instalação: $m"))
                    }
                }
            }
        }
        val flt = IntentFilter("com.termwin.INSTALL_RESULT")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, flt, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(rx, flt)
        instRx = rx
    }

    /** Installs base.apk + splits in one PackageInstaller session. */
    private fun installSplits(t: TabData, pkg: String, label: String, files: List<File>) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            append(t, tr("Allow TermWin to install apps, then run the command again.\n", "Permita o TermWin instalar apps e rode o comando de novo.\n"))
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (e: Exception) { }
            return
        }
        append(t, tr("$label is not installed: installing ${files.size} part(s)…\n", "$label não está instalado: instalando ${files.size} parte(s)…\n"))
        instTab = t; instPkg = pkg; instLabel = label
        thread {
            try {
                ensureInstallReceiverOnUi()
                val pi = packageManager.packageInstaller
                val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(pkg)
                val sid = pi.createSession(params)
                pi.openSession(sid).use { sess ->
                    files.forEach { f ->
                        f.inputStream().use { i -> sess.openWrite(f.name, 0, f.length()).use { o -> i.copyTo(o); sess.fsync(o) } }
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pend = PendingIntent.getBroadcast(this, sid, Intent("com.termwin.INSTALL_RESULT").setPackage(packageName), flags)
                    sess.commit(pend.intentSender)
                }
            } catch (e: Exception) { ui.post { err(t, "install: ${e.message}") } }
        }
    }

    private fun ensureInstallReceiverOnUi() {
        val l = java.util.concurrent.CountDownLatch(1)
        ui.post { try { ensureInstallReceiver() } finally { l.countDown() } }
        l.await()
    }

    private fun findAnywhere(name: String, t: TabData): File? {
        if (name.contains('/')) resolvePath(t, name)?.let { return it }
        val roots = listOf(Environment.getExternalStorageDirectory(), File("/data/data/com.termux/files/home"), filesDir).filter { it.exists() }
        val skip = filesRoot().path
        var exactFile: File? = null; var contains: File? = null; var seen = 0
        val q = java.util.ArrayDeque<Pair<File, Int>>()
        roots.forEach { q.add(it to 0) }
        while (q.isNotEmpty() && seen < 400_000) {
            val (d, depth) = q.poll()
            val kids = d.listFiles() ?: continue
            val nxt = mutableListOf<File>()
            for (k in kids) {
                seen++
                val n = k.name
                if (k.isDirectory) {
                    if (k.path.startsWith(skip) || n == ".thumbnails" || (n == "Android" && d == Environment.getExternalStorageDirectory())) continue
                    if (n.equals(name, true)) return k
                    if (contains == null && n.contains(name, true)) contains = k
                    if (depth < 9 && !java.nio.file.Files.isSymbolicLink(k.toPath())) nxt.add(k)
                } else if (exactFile == null && n.equals(name, true)) exactFile = k
            }
            nxt.forEach { q.add(it to depth + 1) }
        }
        return exactFile ?: contains
    }

    private fun copyRec(src: File, dst: File, cnt: IntArray) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { if (!java.nio.file.Files.isSymbolicLink(it.toPath())) copyRec(it, File(dst, it.name), cnt) }
        } else { try { src.copyTo(dst, true); cnt[0]++ } catch (e: Exception) { cnt[1]++ } }
    }


    // ---------- copy from other apps (Termux...) through Android's folder picker ----------
    private var copyTab: TabData? = null

    private fun pickCopy(t: TabData) {
        copyTab = t
        append(t, tr("Opening Android's folder picker… tap ☰ (top left), choose \"Termux\" (or another app), open the folder you want (e.g. TermWin) and tap \"Use this folder\".\n",
            "Abrindo o seletor de pastas do Android… toque em ☰ (canto de cima), escolha \"Termux\" (ou outro app), entre na pasta que quer (ex.: TermWin) e toque em \"Usar esta pasta\".\n"))
        try { @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 80) } catch (e: Exception) { err(t, "copy: " + (e.message ?: "")) }
    }

    private fun copyTree(tree: Uri, docId: String, dst: File, cnt: IntArray, depth: Int) {
        if (depth > 40) return
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val rows = mutableListOf<Triple<String, String, String>>()
        contentResolver.query(kids, cols, null, null, null)?.use { c -> while (c.moveToNext()) rows.add(Triple(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "")) }
        for ((id, nm, mime) in rows) {
            val clean = safe(nm)
            if (id.isEmpty() || nm == "." || nm == "..") continue
            val out = File(dst, clean)
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { out.mkdirs(); copyTree(tree, id, out, cnt, depth + 1) }
            else try {
                contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                cnt[0]++
            } catch (e: Exception) { cnt[1]++ }
        }
    }

    private fun copyFromTree(tree: Uri) {
        val t = copyTab ?: tabs.firstOrNull() ?: return
        busy(1)
        thread {
            try {
                        val rootId = DocumentsContract.getTreeDocumentId(tree)
                val nm = contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: rootId.substringAfterLast('/')
                val base = safe(nm.ifEmpty { "Copied" })
                var dst = File(copiedDir(), base); var i = 2
                while (dst.exists()) { dst = File(copiedDir(), base + "_" + i); i++ }
                dst.mkdirs()
                val cnt = intArrayOf(0, 0)
                copyTree(tree, rootId, dst, cnt, 0)
                ui.post {
                    append(t, tr("copied ${cnt[0]} file(s) to ", "copiados ${cnt[0]} arquivo(s) para ") + short(dst.path) + (if (cnt[1] > 0) tr("  (${cnt[1]} failed)", "  (${cnt[1]} falharam)") else "") + "\n" +
                        tr("go there with: cd copied/${dst.name}\n", "entre nela com: cd copied/${dst.name}\n"))
                }
            } catch (e: Exception) { ui.post { err(t, "copy: ${e.message}") } }
            ui.post { busy(-1) }
        }
    }

    private fun copyCmd(t: TabData, nameRaw: String) {
        val name = nameRaw.trim().removeSurrounding("\"").removeSurrounding("'")
        if (name.isEmpty() || name.lowercase() in listOf("pick", "-p", "escolher", "app", "apps")) { pickCopy(t); return }
        append(t, tr("searching \"$name\" on the phone…\n", "procurando \"$name\" no celular…\n"))
        busy(1)
        thread {
            try {
                val src = findAnywhere(name, t)
                if (src == null) {
                    ui.post {
                        append(t, tr("\"$name\" is not in shared storage — Android hides other apps' files (like Termux). Use the picker:\n", "\"$name\" não está no armazenamento — o Android esconde os arquivos de outros apps (como o Termux). Use o seletor:\n") +
                            (if (!storageOk()) tr("(tip: turn on storage in ⚙ Settings)\n", "(dica: ligue o armazenamento em ⚙ Configurações)\n") else ""))
                        pickCopy(t)
                    }
                } else {
                    var dst = File(copiedDir(), src.name)
                    var i = 2
                    while (dst.exists()) { dst = File(copiedDir(), src.name + "_" + i); i++ }
                    val cnt = intArrayOf(0, 0)
                    copyRec(src, dst, cnt)
                    ui.post {
                        append(t, tr("found: ", "encontrado: ") + src.path + "\n" + tr("copied ${cnt[0]} file(s) to ", "copiados ${cnt[0]} arquivo(s) para ") + short(dst.path) + (if (cnt[1] > 0) tr("  (${cnt[1]} failed)", "  (${cnt[1]} falharam)") else "") + "\n")
                    }
                }
            } catch (e: Exception) { ui.post { err(t, "copy: ${e.message}") } }
            ui.post { busy(-1) }
        }
    }

    /** ls storage: everything in /storage/emulated/0 with folder icons (ls storage -r = also inside folders, 3 levels). */
    private fun lsStorage(t: TabData, deep: Boolean) {
        val root = Environment.getExternalStorageDirectory()
        if (!storageOk()) { err(t, "ls: " + tr("turn on storage in ⚙ Settings", "ligue o armazenamento em ⚙ Configurações")); return }
        val sb = StringBuilder("📁 ${root.path}\n")
        var n = 0
        fun walk(d: File, pre: String, depth: Int) {
            val l = d.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
            for ((i, f) in l.withIndex()) {
                if (n++ > 3000) return
                val last = i == l.size - 1
                sb.append(pre).append(if (deep) (if (last) "└─ " else "├─ ") else "  ").append(if (f.isDirectory) "📁 " else "📄 ").append(f.name).append("\n")
                if (deep && f.isDirectory && depth < 3 && f.name != "Android") walk(f, pre + (if (last) "   " else "│  "), depth + 1)
            }
        }
        walk(root, "", 1)
        if (n > 3000) sb.append(tr("… (list cut at 3000 items)\n", "… (lista cortada em 3000 itens)\n"))
        append(t, sb.toString())
    }

    private val SLASH_RE = Regex("^(creat|create|criar|open|abrir|roblox)\\s*//(.*)$", RegexOption.IGNORE_CASE)

    /** Commands shared by the terminal and every server template. Returns true when handled. */
    private fun fileCmd(t: TabData, line: String): Boolean {
        val ln = line.trim()
        val m = SLASH_RE.find(ln)
        if (m != null) {
            val verb = m.groupValues[1].lowercase()
            if (verb == "roblox") { robloxCmd(t, m.groupValues[2].trim()); return true }
            if (!(verb.startsWith("cre") || verb == "criar")) {
                val site = webUrlOf(t, m.groupValues[2])
                if (site != null) { openSiteWindow(t, site); return true }
            }
            val parts = m.groupValues[2].split("//").map { it.trim() }.filter { it.isNotEmpty() }
            if (verb.startsWith("cre") || verb == "criar") creatCmd(t, parts) else openFileCmd(t, parts)
            return true
        }
        val sp = ln.split(Regex("\\s+"), 2)
        val c = sp[0].lowercase(); val rest = sp.getOrNull(1)?.trim() ?: ""
        when (c) {
            "creat", "create" -> creatCmd(t, if (rest.isEmpty()) emptyList() else rest.split("//").map { it.trim() }.filter { it.isNotEmpty() })
            "copy", "copiar" -> copyCmd(t, rest)
            "google" -> googleCmd(t, rest)
            "cd" -> {
                val tg = when { rest.isEmpty() -> filesDir; rest == "-" -> File(t.prev.ifEmpty { t.cwd }); else -> resolvePath(t, rest) }
                if (tg != null && tg.isDirectory) { t.prev = t.cwd; t.cwd = tg.path }
                else err(t, "cd: $rest: " + tr("No such file or directory", "Arquivo ou diretório inexistente") +
                    if (!storageOk() && rest.lowercase().let { it.startsWith("/storage") || it.startsWith("storage") || it.startsWith("sdcard") }) tr(" (turn on storage in ⚙ Settings)", " (ligue o armazenamento em ⚙ Configurações)") else "")
            }
            "pwd" -> if (t.tpl.isNotEmpty()) append(t, t.cwd + "\n") else return false
            "ls" -> if (Regex("^(storage|sdcard)(\\s+(-r|-R|tree|arvore))?$", RegexOption.IGNORE_CASE).matches(rest)) {
                lsStorage(t, rest.lowercase().let { it.endsWith("-r") || it.endsWith("tree") || it.endsWith("arvore") })
            } else if (t.tpl.isNotEmpty()) {
                val d = if (rest.isEmpty() || rest.startsWith("-")) File(t.cwd) else resolvePath(t, rest)
                val l = d?.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                if (l == null) err(t, "ls: $rest: " + tr("No such file or directory", "Arquivo ou diretório inexistente"))
                else append(t, l.joinToString("") { it.name + (if (it.isDirectory) "/" else "") + "\n" })
            } else return false
            "nano", "edit", "vi" -> editFile(t, rest.ifEmpty { null })
            "ct" -> ctCmd(t, rest)
            "projects", "projetos" -> {
                val l = projectsDir().listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
                append(t, short(projectsDir().path) + "\n" + (if (l.isEmpty()) tr("  (no projects yet — use creat//Project//html//index.html)\n", "  (nenhum projeto ainda — use creat//Projeto//html//index.html)\n")
                    else l.joinToString("") { "  " + it.name + "/  (" + (it.walkTopDown().count { f -> f.isFile }) + ")\n" }))
            }
            else -> return false
        }
        return true
    }

    private fun editFile(t: TabData, name: String?) {
        if (name == null) { err(t, tr("usage: nano file.txt", "uso: nano arquivo.txt")); return }
        val f = resolvePath(t, name)?.takeIf { it.exists() } ?: (if (name.startsWith("/")) File(name) else File(t.cwd, name))
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


    // ================= SAFETY: nothing outside the app's own folders can be deleted / moved / overwritten =================
    // Layer 1 (guardCheck): refuses dangerous command lines before they start (wrong folder, wrappers that dodge the checks).
    // Layer 2 (SAFE_PRE): rm/rmdir/mv/unlink/shred/truncate/dd/find are replaced inside the shell by versions that check
    //                     the REAL path of every target (after variables, globs and symlinks) and refuse anything outside.
    private fun safeRoots(): List<String> {
        val l = mutableListOf<String>()
        for (f in listOf(filesDir, cacheDir, dataDir())) {
            try { l.add(f.path); l.add(f.canonicalPath) } catch (e: Exception) { }
        }
        return l.distinct()
    }
    private fun inSafe(f: File): Boolean {
        val c = try { f.canonicalPath } catch (e: Exception) { return false }
        return safeRoots().any { c == it || c.startsWith("$it/") }
    }

    private val RE_DESTR = Regex("""(^|[\s;&|(`{!])(rm|rmdir|unlink|shred|truncate|mv|dd)(\s|$|;|&|\|)""")
    private val RE_FIND_DEL = Regex("""\s-(delete|exec|execdir|ok|okdir)(\s|$)""")
    private val RE_BYPASS = Regex("""(^|[\s;&|(`{!])(xargs|eval|exec|env|busybox|toybox|su|sudo|nohup|setsid|nsenter|chroot)(\s|$)|\\(rm|rmdir|unlink|shred|truncate|mv|dd)(\s|$)|\S/(rm|rmdir|unlink|shred|truncate|mv|dd)(\s|$)""")
    private val RE_NESTED = Regex("""(^|[\s;&|(`{!])(sh|bash|dash|ash|mksh|zsh)\s""")
    private val RE_ESCAPE = Regex("""(^|[\s='"(:])(/sdcard|/storage|/mnt|/data/media|/system|/vendor|~/storage|storage/(shared|downloads|documents|dcim|pictures|music|movies))""", RegexOption.IGNORE_CASE)

    private fun looksDestructive(s: String) = RE_DESTR.containsMatchIn(s) || RE_FIND_DEL.containsMatchIn(s)

    /** Returns a message when the line must NOT run, or null when it may run. */
    private fun guardCheck(line: String, cwd: File): String? {
        // scripts: look inside before running them (a nested shell would not have the runtime checks)
        val tk = line.trim().split(Regex("\\s+"))
        val scriptName = when {
            tk.isEmpty() -> null
            tk[0] in listOf("sh", "bash", "dash", "ash", "mksh", "source", ".") -> tk.getOrNull(1)?.takeIf { !it.startsWith("-") }
            tk[0].endsWith(".sh") || tk[0].startsWith("./") -> tk[0]
            else -> null
        }
        if (scriptName != null) {
            val sf = (if (scriptName.startsWith("/")) File(scriptName) else File(cwd, scriptName))
            val body = try { if (sf.isFile && sf.length() < 300_000) sf.readText() else "" } catch (e: Exception) { "" }
            if (body.isNotEmpty() && (looksDestructive(body) || RE_BYPASS.containsMatchIn(body)))
                return tr("TermWin protection: $scriptName contains delete/move commands (rm, mv, find -delete…) and scripts are not checked. Run those commands one by one instead.",
                    "Proteção do TermWin: $scriptName tem comandos de apagar/mover (rm, mv, find -delete…) e scripts não são verificados. Rode esses comandos um por um.")
        }
        if (!looksDestructive(line)) return null
        val why = tr("TermWin protection: deleting, moving or overwriting is only allowed inside the app's own folder (~). For anything else use your phone's Files app.",
            "Proteção do TermWin: apagar, mover ou sobrescrever só é permitido dentro da pasta do próprio app (~). Para o resto use o app Arquivos do celular.")
        if (RE_BYPASS.containsMatchIn(line)) return why + tr("\n(blocked: this form dodges the safety checks)", "\n(bloqueado: essa forma foge das verificações de segurança)")
        if (!inSafe(cwd)) return why + tr("\n(blocked: you are in ${cwd.path}. Run 'cd ~' first)", "\n(bloqueado: você está em ${cwd.path}. Rode 'cd ~' antes)")
        if (RE_NESTED.containsMatchIn(line)) return why + tr("\n(blocked: sh/bash -c hides the command from the checks)", "\n(bloqueado: sh/bash -c esconde o comando das verificações)")
        if (RE_ESCAPE.containsMatchIn(line)) return why
        return null
    }

    private val SAFE_PRE = """
__tw_ok() {
  __p="${'$'}1"; [ -z "${'$'}__p" ] && return 1
  if [ -d "${'$'}__p" ]; then __r=${'$'}(cd -P -- "${'$'}__p" 2>/dev/null && pwd -P) || return 1
  else
    __d="${'$'}{__p%/*}"; [ "${'$'}__d" = "${'$'}__p" ] && __d=.; [ -z "${'$'}__d" ] && __d=/
    __b="${'$'}{__p##*/}"
    case "${'$'}__b" in ..|.) return 1;; esac
    __r=${'$'}(cd -P -- "${'$'}__d" 2>/dev/null && pwd -P) || return 0
    __r="${'$'}__r/${'$'}__b"
  fi
  for __s in ${'$'}TW_SAFE; do case "${'$'}__r" in "${'$'}__s"|"${'$'}__s"/*) return 0;; esac; done
  return 1
}
__tw_chk() {
  __n="${'$'}1"; shift; __e=0
  for __a in "${'$'}@"; do
    if [ "${'$'}__e" = 0 ]; then case "${'$'}__a" in --) __e=1; continue;; of=*) __a="${'$'}{__a#of=}";; -*) continue;; esac; fi
    __tw_ok "${'$'}__a" || { echo "TermWin: ${'$'}__n blocked - '${'$'}__a' is outside the app folder / fica fora da pasta do app" >&2; return 1; }
  done
  return 0
}
rm() { __tw_chk rm "${'$'}@" && command rm "${'$'}@"; }
rmdir() { __tw_chk rmdir "${'$'}@" && command rmdir "${'$'}@"; }
unlink() { __tw_chk unlink "${'$'}@" && command unlink "${'$'}@"; }
shred() { __tw_chk shred "${'$'}@" && command shred "${'$'}@"; }
truncate() { __tw_chk truncate "${'$'}@" && command truncate "${'$'}@"; }
mv() { __tw_chk mv "${'$'}@" && command mv "${'$'}@"; }
dd() { __tw_chk dd "${'$'}@" && command dd "${'$'}@"; }
find() {
  __c=0
  for __a in "${'$'}@"; do case "${'$'}__a" in -delete|-exec|-execdir|-ok|-okdir) __c=1;; esac; done
  if [ "${'$'}__c" = 1 ]; then
    __any=0
    for __a in "${'$'}@"; do
      case "${'$'}__a" in -L|-H) echo "TermWin: find ${'$'}__a with delete/exec blocked / bloqueado" >&2; return 1;; -P|-D*|-O*) continue;; -*|"("|")"|"!"|",") break;; esac
      __any=1
      __tw_ok "${'$'}__a" || { echo "TermWin: find blocked - '${'$'}__a' is outside the app folder / fica fora da pasta do app" >&2; return 1; }
    done
    if [ "${'$'}__any" = 0 ]; then __tw_ok . || { echo "TermWin: find blocked - current folder is outside the app folder / pasta atual fora da pasta do app" >&2; return 1; }; fi
  fi
  command find "${'$'}@"
}
"""

    /** Builds the ProcessBuilder for every shell command (terminal, chains and command-servers). */
    private fun safePb(line: String, dir: File): ProcessBuilder {
        val pb = ProcessBuilder("sh", "-c", SAFE_PRE + "\n" + line).directory(dir).redirectErrorStream(true)
        pb.environment()["TW_SAFE"] = safeRoots().joinToString(" ")
        return pb
    }

    private fun shell(t: TabData, line: String) {
        if (t.proc != null) { err(t, tr("A command is already running (use ^C)", "Já existe um comando rodando (use ^C)")); return }
        guardCheck(line, File(t.cwd))?.let { err(t, it); logError(t.name, "BLOCKED: $line"); return }
        busy(1)
        thread {
            var ok = false
            var info = ""
            val tail = StringBuilder()
            try {
                val pb = safePb(line, File(t.cwd))
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
                    val blockedSrv = guardCheck(s.cmd, filesDir)
                    if (blockedSrv != null) throw Exception(blockedSrv)
                    val pr = safePb(s.cmd, filesDir).start()
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
                    val ss = ServerSocket(s.port, 50, InetAddress.getByName(if (s.type == "tpl-youtube") "0.0.0.0" else "127.0.0.1"))
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

    private fun send(o: OutputStream, ctype: String, data: ByteArray, status: String = "200 OK", extra: String = "") {
        o.write("HTTP/1.1 $status\r\nContent-Type: $ctype\r\nContent-Length: ${data.size}\r\nAccess-Control-Allow-Origin: *\r\n${extra}Connection: close\r\n\r\n".toByteArray())
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
            var range: String? = null
            var cookie = ""; var gtok = ""; var fwd = false; var hostH = ""
            var l = r.readLine()
            while (l != null && l.isNotEmpty()) {
                if (l.startsWith("Range:", true)) range = l.substringAfter(":").trim()
                else if (l.startsWith("Cookie:", true)) cookie = l.substringAfter(":").trim()
                else if (l.startsWith("X-G-Token:", true)) gtok = l.substringAfter(":").trim()
                else if (l.startsWith("Host:", true)) hostH = l.substringAfter(":").trim().substringBefore(":").lowercase()
                else if (l.startsWith("X-Forwarded", true) || l.startsWith("Forwarded:", true) || l.startsWith("CF-Connecting-IP", true) || l.startsWith("X-Real-IP", true) || l.startsWith("True-Client-IP", true)) fwd = true
                l = r.readLine()
            }
            // "host" = the phone owner, only when the request comes from the phone itself and NOT through a tunnel/proxy
            val isHost = c.inetAddress.isLoopbackAddress && !fwd && (hostH == "localhost" || hostH == "127.0.0.1" || hostH.isEmpty())
            val path = Uri.decode((first.split(" ").getOrNull(1) ?: "/").substringBefore("?"))
            // /_anon/... = player test: the request is always treated as a visitor with NO account (never the phone owner)
            val anon = path.startsWith("/_anon/")
            if (s.type == "tpl-youtube") ytCtx.set(if (anon) YtCtx("", "", false) else YtCtx(Regex("(?:^|;\\s*)tws=([0-9a-f]{32})").find(cookie)?.groupValues?.get(1) ?: "", gtok, isHost))
            val o = c.getOutputStream()
            when (s.type) {
                "files" -> serveFiles(o, s, path)
                "json" -> send(o, "application/json; charset=utf-8", s.cmd.ifBlank { "{\"ok\":true}" }.toByteArray())
                "tpl-youtube" -> ytRoute(o, path, range, (first.split(" ").getOrNull(1) ?: "").substringAfter("?", ""))
                "tpl-windows10" -> sendAsset(o, "template_windows10.html")
                "tpl-ai" -> if (path == "/ask") {
                    val qs = first.split(" ").getOrNull(1)?.substringAfter("?", "") ?: ""
                    fun qp(k: String) = qs.split("&").firstOrNull { it.startsWith("$k=") }?.substringAfter("=")?.let { Uri.decode(it.replace("+", " ")) } ?: ""
                    val k = qp("key")
                    val ok = k == ownerToken || apiVisible().isEmpty() || apiVerify(k)
                    val ans = if (ok) askAi(qp("q"), qp("spec")) else tr("401: invalid or missing key. Paste a twk_ key created in 🔑 API Keys.", "401: chave inválida ou ausente. Cole uma chave twk_ criada em 🔑 Chaves de API.")
                    send(o, "application/json; charset=utf-8", JSONObject().put("answer", ans).toString().toByteArray())
                } else sendAsset(o, "template_ai.html")
                "custom" -> send(o, "text/html; charset=utf-8", s.cmd.ifBlank { "<h1>${s.name.replace("<", "&lt;")}</h1>" }.toByteArray())
                else -> send(o, "text/html; charset=utf-8", page(s).toByteArray())
            }
            o.flush()
        } catch (e: Exception) {
        } finally {
            ytCtx.remove()
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

    private fun serveFile(o: OutputStream, f: File, range: String? = null) {
        var mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
        if (mime.startsWith("text/")) mime += "; charset=utf-8"
        val len = f.length()
        var start = 0L; var end = len - 1; var partial = false
        val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        if (m != null) {
            val a = m.groupValues[1]; val b = m.groupValues[2]
            if (a.isEmpty() && b.isNotEmpty()) start = (len - b.toLong()).coerceAtLeast(0)
            else if (a.isNotEmpty()) { start = a.toLong(); if (b.isNotEmpty()) end = minOf(b.toLong(), len - 1) }
            if (start > end || start >= len) {
                o.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$len\r\nConnection: close\r\n\r\n".toByteArray()); return
            }
            partial = true
        }
        val n = end - start + 1
        o.write(((if (partial) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/$len\r\n" else "HTTP/1.1 200 OK\r\n") +
            "Content-Type: $mime\r\nContent-Length: $n\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n").toByteArray())
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(65536); var left = n
            while (left > 0) { val r = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r <= 0) break; o.write(buf, 0, r); left -= r }
        }
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
