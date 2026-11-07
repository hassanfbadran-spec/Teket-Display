package com.hulul.callscreen

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Collections
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * شاشة استدعاء مستقلة: تبحث تلقائياً عن جهاز "تذاكر العيادة" على نفس الشبكة
 * وتعرض الرقم الحالي وآخر 3 أرقام وعدد المنتظرين، مع رنين ونداء صوتي.
 */
class MainActivity : Activity(), TextToSpeech.OnInitListener {

    companion object {
        const val PORT = 8080
        const val BLUE = 0xFF0D47A1.toInt()
        const val GREEN = 0xFF2E7D32.toInt()
        const val URGENT = 0xFFD84315.toInt()
    }

    private lateinit var clinicView: TextView
    private lateinit var numView: TextView
    private lateinit var typeView: TextView
    private lateinit var recentView: TextView
    private lateinit var waitView: TextView
    private lateinit var statusView: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tone: ToneGenerator? = null
    private val ui = Handler(Looper.getMainLooper())

    @Volatile
    private var polling = false
    @Volatile
    private var generation = 0
    @Volatile
    private var fails = 0
    @Volatile
    private var scanning = false
    @Volatile
    private var lastScan = 0L
    private val keys = HashMap<Long, String>()
    private lateinit var body: LinearLayout
    private lateinit var multiHolder: LinearLayout

    private fun prefs() = getSharedPreferences("display", MODE_PRIVATE)
    private fun addr(): String = prefs().getString("addr", "") ?: ""
    private fun setAddr(a: String) {
        prefs().edit().putString("addr", a).apply()
    }

    private fun tv(px: Float, bold: Boolean, color: Int): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()
        tts = TextToSpeech(this, this)
        try {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (e: Exception) {
        }

        val m = resources.displayMetrics
        val base = minOf(m.widthPixels, m.heightPixels).toFloat()
        val landscape = m.widthPixels > m.heightPixels
        val pad = (base * 0.03f).toInt()
        val gap = (base * 0.02f).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(BLUE)
        root.setPadding(pad, pad, pad, pad)

        clinicView = tv(base * 0.06f, true, Color.WHITE)
        root.addView(clinicView, LinearLayout.LayoutParams(-1, -2))

        body = LinearLayout(this)
        body.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        body.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER
        card.background = rounded(Color.WHITE, (base * 0.04f).toInt())
        card.addView(tv(base * 0.05f, false, 0xFF5B6B7A.toInt()).apply { text = "الرقم الحالي" })
        numView = tv(base * (if (landscape) 0.5f else 0.42f), true, BLUE)
        numView.text = "-"
        card.addView(numView)
        typeView = tv(base * 0.075f, true, GREEN)
        card.addView(typeView)

        val side = LinearLayout(this)
        side.orientation = LinearLayout.VERTICAL
        side.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val recentBox = LinearLayout(this)
        recentBox.orientation = LinearLayout.VERTICAL
        recentBox.gravity = Gravity.CENTER
        recentBox.background = rounded(0x24FFFFFF, (base * 0.03f).toInt())
        recentBox.addView(tv(base * 0.045f, false, 0xE6FFFFFF.toInt()).apply { text = "آخر الأرقام" })
        recentView = tv(base * 0.06f, true, Color.WHITE)
        recentBox.addView(recentView)

        val waitBox = LinearLayout(this)
        waitBox.orientation = LinearLayout.VERTICAL
        waitBox.gravity = Gravity.CENTER
        waitBox.background = rounded(0x24FFFFFF, (base * 0.03f).toInt())
        waitView = tv(base * 0.065f, true, Color.WHITE)
        waitBox.addView(waitView)

        side.addView(recentBox, LinearLayout.LayoutParams(-1, 0, 2f).apply { bottomMargin = gap })
        side.addView(waitBox, LinearLayout.LayoutParams(-1, 0, 1f))

        if (landscape) {
            body.addView(card, LinearLayout.LayoutParams(0, -1, 2f).apply { marginStart = gap })
            body.addView(side, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            body.addView(card, LinearLayout.LayoutParams(-1, 0, 2f).apply { bottomMargin = gap })
            body.addView(side, LinearLayout.LayoutParams(-1, 0, 1.4f))
        }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = gap })

        // عرض الأقسام المتعددة: بطاقة لكل قسم (يظهر بدل البطاقة الواحدة)
        multiHolder = LinearLayout(this)
        multiHolder.orientation = LinearLayout.VERTICAL
        multiHolder.layoutDirection = View.LAYOUT_DIRECTION_RTL
        multiHolder.visibility = View.GONE
        root.addView(multiHolder, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = gap })

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.layoutDirection = View.LAYOUT_DIRECTION_RTL
        bottom.gravity = Gravity.CENTER_VERTICAL
        statusView = tv(base * 0.03f, false, 0xCCFFFFFF.toInt())
        statusView.gravity = Gravity.START
        bottom.addView(statusView, LinearLayout.LayoutParams(0, -2, 1f))
        val gear = tv(base * 0.032f, false, Color.WHITE)
        gear.text = "ضبط الاتصال  (OK / قائمة)"
        gear.setPadding(pad, pad / 2, pad, pad / 2)
        gear.background = rounded(0x33FFFFFF, (base * 0.02f).toInt())
        gear.setOnClickListener { configDialog() }
        bottom.addView(gear, LinearLayout.LayoutParams(-2, -2))
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })

        setContentView(root)
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts?.setLanguage(Locale("ar"))
            ttsReady = r != null && r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER
        ) {
            configDialog()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        immersive()
        startPolling()
    }

    override fun onPause() {
        polling = false
        super.onPause()
    }

    override fun onDestroy() {
        polling = false
        tts?.shutdown()
        try {
            tone?.release()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    // ---------------- الاتصال والبحث التلقائي ----------------

    private fun fetch(address: String): JSONObject? {
        return try {
            val conn = URL("http://" + address + "/state").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val j = JSONObject(text)
            if (j.has("waiting")) j else null
        } catch (e: Exception) {
            null
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        generation += 1
        val mine = generation
        Thread {
            while (polling && mine == generation) {
                val a = addr()
                val result: JSONObject? = if (a.isNotBlank()) fetch(a) else null
                if (result == null) fails += 1 else fails = 0
                val needScan = (a.isBlank() || fails >= 3) && !scanning &&
                    System.currentTimeMillis() - lastScan > 8000
                if (needScan) startScan()
                ui.post { render(result, a) }
                try {
                    Thread.sleep(1500)
                } catch (e: InterruptedException) {
                }
            }
        }.start()
    }

    private fun localIp(): String? {
        var fallback: String? = null
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in Collections.list(ni.inetAddresses)) {
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                        if (ni.name.startsWith("wlan") || ni.name.startsWith("eth")) return a.hostAddress
                        if (fallback == null) fallback = a.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
        }
        return fallback
    }

    private fun probe(host: String): String? {
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, PORT), 300)
            s.close()
        } catch (e: Exception) {
            return null
        }
        val a = host + ":" + PORT
        return if (fetch(a) != null) a else null
    }

    private fun scanNetwork(): String? {
        val ip = localIp() ?: return null
        val prefix = ip.substringBeforeLast(".") + "."
        val pool = Executors.newFixedThreadPool(64)
        try {
            val tasks = (1..254).map { i ->
                Callable<String?> {
                    val host = prefix + i
                    if (host == ip) null else probe(host)
                }
            }
            return pool.invokeAll(tasks)
                .mapNotNull { f -> try { f.get() } catch (e: Exception) { null } }
                .firstOrNull()
        } finally {
            pool.shutdown()
        }
    }

    private fun startScan() {
        scanning = true
        lastScan = System.currentTimeMillis()
        Thread {
            val found = scanNetwork()
            if (found != null) {
                setAddr(found)
                keys.clear()
            }
            fails = 0
            scanning = false
        }.start()
    }

    // ---------------- العرض ----------------

    private fun render(j: JSONObject?, a: String) {
        if (j == null) {
            statusView.text = when {
                scanning -> "جارٍ البحث عن الجهاز الرئيسي..."
                a.isBlank() -> "لم يُعثر على الجهاز الرئيسي بعد. تأكد أنه على نفس الشبكة وأن البث مفعّل. سيُعاد البحث تلقائياً."
                else -> "انقطع الاتصال ($a). سيُعاد البحث تلقائياً."
            }
            return
        }
        statusView.text = "متصل: $a"
        clinicView.text = j.optString("clinic", "")

        // أقسام (جهاز رئيسي 1.5 فأحدث) أو الحقول القديمة لقسم واحد
        val list = mutableListOf<JSONObject>()
        val arr = j.optJSONArray("depts")
        if (arr != null && arr.length() > 0) {
            for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        } else {
            list.add(j)
        }
        val multi = list.size > 1
        body.visibility = if (multi) View.GONE else View.VISIBLE
        multiHolder.visibility = if (multi) View.VISIBLE else View.GONE
        for (d in list) checkAnnounce(d, multi)
        if (multi) renderMulti(list) else renderSingle(list[0])
    }

    private fun typeColor(cur: JSONObject): Int {
        val c = cur.optInt("color", 0)
        return if (c != 0) c else if (cur.optBoolean("urgent")) URGENT else GREEN
    }

    private fun recentText(rec: org.json.JSONArray?): String {
        if (rec == null || rec.length() == 0) return ""
        val sb = StringBuilder()
        for (i in 0 until rec.length()) {
            if (i > 0) sb.append(" ، ")
            sb.append(rec.getJSONObject(i).optInt("number"))
        }
        return sb.toString()
    }

    private fun renderSingle(d: JSONObject) {
        val cur = d.optJSONObject("current")
        if (cur == null) {
            numView.text = "-"
            typeView.text = ""
        } else {
            numView.text = cur.optInt("number").toString()
            typeView.text = cur.optString("type")
            typeView.setTextColor(typeColor(cur))
        }
        val rec = d.optJSONArray("recent")
        val sb = StringBuilder()
        if (rec != null) {
            for (i in 0 until rec.length()) {
                val o = rec.getJSONObject(i)
                sb.append(o.optInt("number")).append("    ").append(o.optString("type")).append("\n")
            }
        }
        recentView.text = if (sb.isEmpty()) "-" else sb.toString().trimEnd()
        waitView.text = "عدد المنتظرين: " + d.optInt("waiting")
    }

    private fun renderMulti(list: List<JSONObject>) {
        multiHolder.removeAllViews()
        val m = resources.displayMetrics
        val landscape = m.widthPixels > m.heightPixels
        val n = list.size
        val cols = if (landscape) {
            if (n <= 3) n else Math.ceil(Math.sqrt(n.toDouble())).toInt()
        } else {
            if (n <= 2) 1 else 2
        }
        val rows = (n + cols - 1) / cols
        val unit = minOf(m.widthPixels * 0.94f / cols, m.heightPixels * 0.72f / rows)
        val gap = (unit * 0.04f).toInt()
        for (r in 0 until rows) {
            val rowL = LinearLayout(this)
            rowL.orientation = LinearLayout.HORIZONTAL
            rowL.layoutDirection = View.LAYOUT_DIRECTION_RTL
            for (c in 0 until cols) {
                val idx = r * cols + c
                val lp = LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(gap, gap, gap, gap) }
                if (idx < n) rowL.addView(deptCard(list[idx], unit), lp) else rowL.addView(View(this), lp)
            }
            multiHolder.addView(rowL, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun deptCard(d: JSONObject, unit: Float): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.background = rounded(Color.WHITE, (unit * 0.06f).toInt())
        val name = tv(unit * 0.085f, true, Color.WHITE)
        name.text = d.optString("name")
        val dc = d.optInt("color", 0)
        name.background = rounded(if (dc != 0) dc else BLUE, (unit * 0.04f).toInt())
        name.setPadding(0, (unit * 0.02f).toInt(), 0, (unit * 0.02f).toInt())
        card.addView(name, LinearLayout.LayoutParams(-1, -2))
        val cur = d.optJSONObject("current")
        val num = tv(unit * 0.42f, true, BLUE)
        num.text = if (cur == null) "-" else cur.optInt("number").toString()
        card.addView(num)
        val ty = tv(unit * 0.075f, true, if (cur == null) GREEN else typeColor(cur))
        ty.text = cur?.optString("type") ?: ""
        card.addView(ty)
        val w = tv(unit * 0.06f, false, 0xFF5B6B7A.toInt())
        w.text = "المنتظرون: " + d.optInt("waiting")
        card.addView(w)
        val rt = recentText(d.optJSONArray("recent"))
        if (rt.isNotEmpty()) {
            val r = tv(unit * 0.055f, false, 0xFF5B6B7A.toInt())
            r.text = "آخر الأرقام: " + rt
            card.addView(r)
        }
        return card
    }

    /** يتحقق من تغيّر الرقم الحالي لقسم فيناديه (وأول قراءة لا تنادي) */
    private fun checkAnnounce(d: JSONObject, multi: Boolean) {
        val cur = d.optJSONObject("current")
        val id = d.optLong("id", 0L)
        val key = if (cur == null) "" else cur.optLong("id").toString() + ":" + cur.optLong("calledAt") + ":" + d.optLong("recall")
        val old = keys[id]
        keys[id] = key
        if (old != null && old != key && cur != null) {
            announce(cur.optInt("number"), cur.optString("type"), if (multi) d.optString("name") else "")
        }
    }

    private fun announce(number: Int, type: String, dept: String) {
        try {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 400)
        } catch (e: Exception) {
        }
        val extra = if (dept.isNotEmpty()) "، " + dept else ""
        ui.postDelayed({
            if (ttsReady) tts?.speak("على رقم ${number}، ${type}" + extra, TextToSpeech.QUEUE_FLUSH, null, "call")
        }, 500)
    }

    // ---------------- ضبط الاتصال اليدوي ----------------

    private fun configDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(40, 20, 40, 10)
        val input = EditText(this).apply {
            hint = "192.168.1.20:8080"
            setText(addr())
            textSize = 20f
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        box.addView(input)
        val dlg = AlertDialog.Builder(this)
            .setTitle("ضبط الاتصال")
            .setMessage("البحث التلقائي شغّال دائماً. استخدم هذه الخيارات عند الحاجة فقط.")
            .setView(box)
            .setNegativeButton("إغلاق", null)
            .create()

        fun btn(text: String, onClick: () -> Unit): Button = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }
        box.addView(btn("حفظ العنوان يدوياً") {
            var a = input.text.toString().trim().removePrefix("http://").trimEnd('/')
            if (a.isNotEmpty() && !a.contains(":")) a += ":" + PORT
            setAddr(a)
            fails = 0
            keys.clear()
            Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
            dlg.dismiss()
        })
        box.addView(btn("بحث تلقائي الآن") {
            setAddr("")
            fails = 0
            lastScan = 0L
            dlg.dismiss()
        })
        box.addView(btn("اختبار الصوت") { announce(7, "اختبار الصوت", "") })
        dlg.show()
        immersive()
    }
}
