package com.abkkb.termwin

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class TabData(var name: String, var cwd: String, val log: StringBuilder = StringBuilder()) {
    @Volatile var proc: Process? = null
}

class Srv(var name: String, var type: String, var port: Int, var cmd: String) {
    @Volatile var running = false
    var sock: ServerSocket? = null
    var proc: Process? = null
}

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val tabs = mutableListOf<TabData>()
    private val servers = mutableListOf<Srv>()
    private var cur = 0
    private var maximized = false

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

    private val HELP = """Comandos do TermWin:
  help                          esta ajuda
  clear                         limpa a tela
  cd <pasta>                    muda de pasta
  servidor listar
  servidor criar <nome> [youtube|web] [porta]
  servidor iniciar|parar|remover <nome>
  servidor renomear <nome> <novo>
Qualquer outro comando roda no shell do Android (ls, pwd, cat, ping...).
Toque e segure numa aba para renomear.
"""

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tv(s: String, sz: Float, c: Int) = TextView(this).apply { text = s; textSize = sz; setTextColor(c) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dlg() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
    private fun rounded(c: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(c); cornerRadius = r.toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }
    private fun pressBg(p: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(p))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }
    private fun short(p: String) = p.replace(filesDir.path, "~")

    // ---------- ciclo de vida ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        load()
        root = FrameLayout(this)
        root.setBackgroundColor(0xFF0B0F14.toInt())

        val home = LinearLayout(this)
        home.orientation = LinearLayout.VERTICAL
        home.gravity = Gravity.CENTER
        home.addView(tv("TermWin", 34f, Color.WHITE).apply { typeface = Typeface.MONOSPACE })
        home.addView(tv("terminal + servidores + janela Windows 11", 14f, 0xFF8899AA.toInt()).apply { setPadding(0, dp(4), 0, dp(20)) })
        val open = tv("⊞  Abrir janela", 18f, Color.BLACK)
        open.setPadding(dp(32), dp(14), dp(32), dp(14))
        open.background = rounded(ACCENT, dp(8))
        open.setOnClickListener { openWindow() }
        home.addView(open)
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

    override fun onPause() { super.onPause(); save() }

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
            cur = o.optInt("cur", 0)
        } catch (e: Exception) { }
        if (tabs.isEmpty()) tabs.add(newTabData("Terminal 1"))
        cur = cur.coerceIn(0, tabs.size - 1)
    }

    private fun newTabData(name: String) =
        TabData(name, filesDir.path, StringBuilder("TermWin — digite 'help' para ver os comandos.\n"))

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
        row.addView(capBtn("＋", false, 40) { addTab() }.apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(38)) })
        row.addView(capBtn("Servidores", false, 96) { showServers() }.apply { textSize = 12f; layoutParams = LinearLayout.LayoutParams(dp(96), dp(38)) })
        row.addView(capBtn("＋ Servidor", false, 96) { createServerDialog() }.apply { textSize = 12f; setTextColor(ACCENT); layoutParams = LinearLayout.LayoutParams(dp(96), dp(38)) })
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
        out.text = t.log.toString()
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
            if (trimmed) outView?.text = t.log.toString() else outView?.append(s)
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
        exec(t, c)
    }

    private fun exec(t: TabData, line: String) {
        if (line.isEmpty()) return
        val p = line.split(" ").filter { it.isNotEmpty() }
        when (p[0]) {
            "help" -> append(t, HELP)
            "clear" -> { t.log.setLength(0); outView?.text = "" }
            "cd" -> {
                val target = p.getOrNull(1) ?: filesDir.path
                val f = try { (if (target.startsWith("/")) File(target) else File(t.cwd, target)).canonicalFile } catch (e: Exception) { null }
                if (f != null && f.isDirectory) t.cwd = f.path else append(t, "cd: pasta não encontrada\n")
            }
            "servidor", "server" -> serverCmd(t, p)
            else -> shell(t, line)
        }
        save()
    }

    private fun shell(t: TabData, line: String) {
        if (t.proc != null) { append(t, "Já existe um comando rodando (use ^C)\n"); return }
        thread {
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
                ui.post { append(t, "[saiu: $code]\n") }
            } catch (e: Exception) {
                ui.post { append(t, "erro: ${e.message}\n") }
            }
            t.proc = null
        }
    }

    // ---------- servidores ----------
    private fun freePort(): Int = (8080 until 9000).first { p -> servers.none { it.port == p } }
    private fun findSrv(n: String?) = servers.firstOrNull { it.name.equals(n, true) }

    private fun serverCmd(t: TabData, p: List<String>) {
        when (p.getOrNull(1)) {
            "criar" -> {
                val n = p.getOrNull(2)
                if (n == null) { append(t, "uso: servidor criar <nome> [youtube|web] [porta]\n"); return }
                if (findSrv(n) != null) { append(t, "já existe um servidor com esse nome\n"); return }
                val ty = p.getOrNull(3) ?: "web"
                if (ty != "youtube" && ty != "web") { append(t, "tipo inválido (youtube ou web)\n"); return }
                val po = p.getOrNull(4)?.toIntOrNull() ?: freePort()
                servers.add(Srv(n, ty, po, ""))
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
        if (s.type == "cmd") {
            thread {
                try {
                    val pr = ProcessBuilder("sh", "-c", s.cmd).directory(filesDir).redirectErrorStream(true).start()
                    s.proc = pr
                    ui.post { sayCur("[${s.name}] iniciado\n") }
                    pr.inputStream.bufferedReader().forEachLine { l -> ui.post { sayCur("[${s.name}] $l\n") } }
                    pr.waitFor()
                } catch (e: Exception) { ui.post { sayCur("erro: ${e.message}\n") } }
                s.running = false
                ui.post { sayCur("[${s.name}] encerrado\n") }
            }
        } else {
            thread {
                try {
                    val ss = ServerSocket(s.port)
                    s.sock = ss
                    ui.post { sayCur("Servidor '${s.name}' no ar: http://localhost:${s.port}\n") }
                    while (s.running) {
                        val c = ss.accept()
                        thread { serve(c, s) }
                    }
                } catch (e: Exception) {
                    if (s.running) ui.post { sayCur("erro no servidor '${s.name}': ${e.message}\n") }
                }
                s.running = false
            }
        }
    }

    private fun stopServer(s: Srv) {
        s.running = false
        try { s.sock?.close() } catch (e: Exception) { }
        s.proc?.destroy()
        sayCur("'${s.name}' parado\n")
    }

    private fun serve(c: Socket, s: Srv) {
        try {
            c.soTimeout = 3000
            val r = c.getInputStream().bufferedReader()
            var l = r.readLine()
            while (l != null && l.isNotEmpty()) l = r.readLine()
            val b = page(s).toByteArray()
            val o = c.getOutputStream()
            o.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
            o.write(b)
            o.flush()
        } catch (e: Exception) {
        } finally {
            try { c.close() } catch (e: Exception) { }
        }
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

    private fun showServers() {
        if (servers.isEmpty()) { toast("Nenhum servidor ainda. Toque em ＋ Servidor"); return }
        val names = servers.map { (if (it.running) "● " else "○ ") + it.name + "   [" + it.type + (if (it.type != "cmd") " :" + it.port else "") + "]" }.toTypedArray()
        dlg().setTitle("Servidores").setItems(names) { _, i -> serverActions(servers[i]) }.setNegativeButton("Fechar", null).show()
    }

    private fun serverActions(s: Srv) {
        val ops = arrayOf(if (s.running) "Parar" else "Iniciar", "Renomear", "Remover")
        dlg().setTitle(s.name).setItems(ops) { _, k ->
            when (k) {
                0 -> if (s.running) stopServer(s) else startServer(s)
                1 -> promptText("Novo nome", s.name) { n -> s.name = n; save() }
                else -> { stopServer(s); servers.remove(s); save() }
            }
        }.show()
    }

    private fun createServerDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val name = EditText(this).apply { hint = "Nome (ex.: YouTube)"; setSingleLine(true) }
        val type = Spinner(this)
        type.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("YouTube", "Web simples", "Comando"))
        val port = EditText(this).apply { hint = "Porta"; setText(freePort().toString()); inputType = InputType.TYPE_CLASS_NUMBER }
        val cmd = EditText(this).apply { hint = "Comando (só para tipo Comando)"; setSingleLine(true) }
        box.addView(name); box.addView(type); box.addView(port); box.addView(cmd)
        dlg().setTitle("Criar servidor").setView(box)
            .setPositiveButton("Criar") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty() || findSrv(n) != null) { toast("Nome vazio ou já existe"); return@setPositiveButton }
                val ty = arrayOf("youtube", "web", "cmd")[type.selectedItemPosition]
                val po = port.text.toString().toIntOrNull() ?: freePort()
                servers.add(Srv(n, ty, po, cmd.text.toString()))
                save()
                sayCur("servidor '$n' criado ($ty). Inicie em Servidores.\n")
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun promptText(title: String, init: String, ok: (String) -> Unit) {
        val et = EditText(this)
        et.setText(init)
        et.setSingleLine(true)
        dlg().setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ -> val s = et.text.toString().trim(); if (s.isNotEmpty()) ok(s) }
            .setNegativeButton("Cancelar", null).show()
    }
}
