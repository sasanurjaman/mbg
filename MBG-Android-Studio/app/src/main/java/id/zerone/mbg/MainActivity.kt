package id.zerone.mbg

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Base64
import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream

class MainActivity : androidx.fragment.app.FragmentActivity() {
    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var unlocked = false
    private var backgroundAt = 0L
    private val prefs by lazy { getSharedPreferences("mbg_prefs", Context.MODE_PRIVATE) }
    private var pendingBackup: ByteArray? = null
    private var pendingBackupName: String = "MBG-backup.json"

    companion object {
        private const val REQUEST_OPEN_FILE = 2001
        private const val REQUEST_SAVE_FILE = 2002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.setSupportZoom(false)
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.allowUniversalAccessFromFileURLs = true
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(
                    "if (window.AndroidBridge) { window.print = function(){ AndroidBridge.printReceipt(); }; }",
                    null
                )
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (url.scheme == "https" || url.scheme == "http" || url.scheme == "mailto" || url.scheme == "tel") {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                }
                return try {
                    startActivityForResult(intent, REQUEST_OPEN_FILE)
                    true
                } catch (_: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
        lockIfNeeded()
    }

    private fun canBiometric(): Boolean =
        BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS

    // Kunci sidik jari: WebView disembunyikan sampai verifikasi lolos.
    // Batal oleh user = aplikasi ditutup; error lain (termasuk lockout) = prompt ditampilkan lagi,
    // dan dari prompt yang sama user bisa memilih PIN/pola perangkat sebagai fallback.
    private fun lockIfNeeded() {
        if (unlocked) return
        if (!prefs.getBoolean("biometric_enabled", false)) { unlocked = true; return }
        if (!canBiometric()) { unlocked = true; return }
        webView.visibility = View.INVISIBLE
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                    webView.visibility = View.VISIBLE
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        finish()
                    } else if (!isFinishing) {
                        lockIfNeeded()
                    }
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("MBG terkunci")
            .setSubtitle("Gunakan sidik jari atau PIN perangkat")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }

    override fun onStop() {
        super.onStop()
        if (unlocked) backgroundAt = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        // Kunci lagi bila aplikasi ditinggal lebih dari 60 detik.
        if (unlocked && backgroundAt > 0 && System.currentTimeMillis() - backgroundAt > 60_000) {
            unlocked = false
            lockIfNeeded()
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun saveBackup(json: String, fileName: String) {
            runOnUiThread {
                pendingBackup = json.toByteArray(Charsets.UTF_8)
                pendingBackupName = fileName.ifBlank { "MBG-backup.json" }
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, pendingBackupName)
                }
                startActivityForResult(intent, REQUEST_SAVE_FILE)
            }
        }

        @JavascriptInterface
        fun setBiometricEnabled(enabled: Boolean) {
            prefs.edit().putBoolean("biometric_enabled", enabled).apply()
        }

        @JavascriptInterface
        fun biometricAvailable(): Boolean = canBiometric()

        @JavascriptInterface
        fun appVersion(): String {
            return try {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            } catch (e: Exception) { "" }
        }

        @JavascriptInterface
        fun shareImage(base64: String, fileName: String) {
            runOnUiThread {
                try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val dir = File(cacheDir, "shared").apply { mkdirs() }
                    val file = File(dir, fileName.ifBlank { "nota.png" })
                    file.writeBytes(bytes)
                    val uri = FileProvider.getUriForFile(this@MainActivity, "id.zerone.mbg.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Bagikan nota"))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Gagal membagikan gambar: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun printReceipt() {
            runOnUiThread {
                val printManager = getSystemService(PRINT_SERVICE) as PrintManager
                val adapter = webView.createPrintDocumentAdapter("MBG")
                printManager.print("MBG", adapter, null)
            }
        }

        @JavascriptInterface
        fun sharePdfData(json: String, fileName: String) {
            runOnUiThread {
                try {
                    val root = org.json.JSONObject(json)
                    val biz = root.optJSONObject("biz") ?: org.json.JSONObject()
                    val arr = root.optJSONArray("lines")
                    val dark = android.graphics.Color.rgb(17, 24, 39)
                    val gray = android.graphics.Color.rgb(107, 114, 128)
                    val lineGray = android.graphics.Color.rgb(229, 231, 235)
                    val lightTxt = android.graphics.Color.rgb(229, 231, 235)
                    fun tp(size: Float, bold: Boolean, color: Int, align: android.graphics.Paint.Align): android.graphics.Paint {
                        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        p.textSize = size
                        p.color = color
                        p.textAlign = align
                        p.typeface = if (bold) android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD) else android.graphics.Typeface.DEFAULT
                        return p
                    }
                    fun wrap(text: String, p: android.graphics.Paint, maxW: Float, maxLines: Int): List<String> {
                        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
                        if (words.isEmpty()) return listOf("")
                        val out = mutableListOf<String>()
                        var cur = ""
                        var cut = false
                        for (w in words) {
                            val t = if (cur.isEmpty()) w else cur + " " + w
                            if (p.measureText(t) <= maxW) { cur = t } else {
                                if (cur.isNotEmpty()) out.add(cur)
                                if (out.size >= maxLines) { cut = true; break }
                                cur = w
                            }
                        }
                        if (!cut && cur.isNotEmpty()) out.add(cur)
                        if (cut && out.isNotEmpty()) {
                            var last = out[out.size - 1]
                            while (last.isNotEmpty() && p.measureText(last + "...") > maxW) last = last.dropLast(1)
                            out[out.size - 1] = last.trimEnd() + "..."
                        }
                        return if (out.isEmpty()) listOf("") else out
                    }
                    fun bmp(name: String, targetH: Float): android.graphics.Bitmap? {
                        return try {
                            val raw = assets.open(name).use { android.graphics.BitmapFactory.decodeStream(it) } ?: return null
                            val w = (targetH * raw.width / raw.height).toInt().coerceAtLeast(1)
                            android.graphics.Bitmap.createScaledBitmap(raw, w, targetH.toInt(), true)
                        } catch (e: Exception) { null }
                    }
                    data class Row(val nameLines: List<String>, val qty: String, val price: String, val amount: String, val h: Float)
                    val pName = tp(9.5f, false, dark, android.graphics.Paint.Align.LEFT)
                    val rows = mutableListOf<Row>()
                    if (arr != null) {
                        for (idx in 0 until arr.length()) {
                            val o = arr.optJSONObject(idx) ?: continue
                            val nl = wrap(o.optString("name"), pName, 300f, 2)
                            rows.add(Row(nl, o.optString("qty"), o.optString("price"), o.optString("amount"), maxOf(26f, 12f + nl.size * 12f)))
                        }
                    }
                    val notes = root.optString("notes")
                    val notesLines = if (notes.isNotEmpty()) wrap(notes, tp(9f, false, dark, android.graphics.Paint.Align.LEFT), 262f, 5) else emptyList()
                    val tableTop = 178f
                    var rowsH = 0f
                    for (r in rows) rowsH += r.h
                    val totalsTop = tableTop + 24f + rowsH + 12f
                    val totalsH = 4 * 16f + 24f
                    val footTop = totalsTop + maxOf(totalsH, 12f + notesLines.size * 12f) + 34f
                    val pageH = (footTop + 78f + 36f).toInt().coerceIn(420, 2400)
                    val doc = android.graphics.pdf.PdfDocument()
                    val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, pageH, 1).create())
                    val c = page.canvas
                    val M = 36f
                    val R = 559f
                    fun fill(color: Int, l: Float, t: Float, r: Float, b: Float) {
                        val p = android.graphics.Paint()
                        p.color = color
                        c.drawRect(l, t, r, b, p)
                    }
                    fun txt(s2: String, x: Float, y: Float, p: android.graphics.Paint) { c.drawText(s2, x, y, p) }
                    fill(android.graphics.Color.rgb(55, 65, 81), 0f, 0f, 595f, 88f)
                    val logo = bmp("logo-warna.png", 52f)
                    var nameX = M
                    if (logo != null) { c.drawBitmap(logo, M, 18f, null); nameX = M + logo.width + 10f }
                    txt(biz.optString("name").uppercase(), nameX, 40f, tp(15f, true, android.graphics.Color.WHITE, android.graphics.Paint.Align.LEFT))
                    txt(biz.optString("address"), nameX, 57f, tp(9f, false, lightTxt, android.graphics.Paint.Align.LEFT))
                    val contact = biz.optString("phone") + if (biz.optString("email").isNotEmpty()) "  -  " + biz.optString("email") else ""
                    txt(contact, nameX, 71f, tp(9f, false, lightTxt, android.graphics.Paint.Align.LEFT))
                    txt("TAGIHAN", R, 44f, tp(16f, true, android.graphics.Color.WHITE, android.graphics.Paint.Align.RIGHT))
                    txt(root.optString("number"), R, 62f, tp(9.5f, false, lightTxt, android.graphics.Paint.Align.RIGHT))
                    txt("TAGIHAN KEPADA", M, 114f, tp(8.5f, true, gray, android.graphics.Paint.Align.LEFT))
                    txt(root.optString("customer"), M, 134f, tp(13f, true, dark, android.graphics.Paint.Align.LEFT))
                    val meta = listOf(
                        "TAGIHAN" to root.optString("number"),
                        "TANGGAL" to root.optString("date"),
                        "BATAS WAKTU" to root.optString("due"),
                        "MATA UANG" to "IDR"
                    )
                    var my = 114f
                    for ((k, v) in meta) {
                        txt(k, 350f, my, tp(8.5f, true, gray, android.graphics.Paint.Align.LEFT))
                        txt(v, R, my, tp(9.5f, false, dark, android.graphics.Paint.Align.RIGHT))
                        my += 15f
                    }
                    fill(android.graphics.Color.rgb(75, 85, 99), M, tableTop, R, tableTop + 24f)
                    val hy = tableTop + 16f
                    val hw = tp(8.5f, true, android.graphics.Color.WHITE, android.graphics.Paint.Align.LEFT)
                    txt("DESKRIPSI", M + 8f, hy, hw)
                    txt("KUANTITAS", 372f, hy, tp(8.5f, true, android.graphics.Color.WHITE, android.graphics.Paint.Align.CENTER))
                    txt("HARGA JUAL", 478f, hy, tp(8.5f, true, android.graphics.Color.WHITE, android.graphics.Paint.Align.RIGHT))
                    txt("JUMLAH", R - 8f, hy, tp(8.5f, true, android.graphics.Color.WHITE, android.graphics.Paint.Align.RIGHT))
                    var ry = tableTop + 24f
                    val pQty = tp(9.5f, false, dark, android.graphics.Paint.Align.CENTER)
                    val pRight = tp(9.5f, false, dark, android.graphics.Paint.Align.RIGHT)
                    for ((ri, r) in rows.withIndex()) {
                        if (ri % 2 == 1) fill(android.graphics.Color.rgb(249, 250, 251), M, ry, R, ry + r.h)
                        var ly = ry + 17f
                        for (nl in r.nameLines) { txt(nl, M + 8f, ly, pName); ly += 12f }
                        txt(r.qty, 372f, ry + 17f, pQty)
                        txt(r.price, 478f, ry + 17f, pRight)
                        txt(r.amount, R - 8f, ry + 17f, pRight)
                        fill(lineGray, M, ry + r.h - 0.7f, R, ry + r.h)
                        ry += r.h
                    }
                    if (notesLines.isNotEmpty()) {
                        txt("Catatan", M, totalsTop + 11f, tp(9f, true, gray, android.graphics.Paint.Align.LEFT))
                        var ny = totalsTop + 25f
                        val pNotes = tp(9f, false, dark, android.graphics.Paint.Align.LEFT)
                        for (nl in notesLines) { txt(nl, M, ny, pNotes); ny += 12f }
                    }
                    val totals = listOf(
                        "Subtotal" to root.optString("subtotal"),
                        "Diskon" to root.optString("discount"),
                        "Total Tagihan" to root.optString("total"),
                        "Sudah Dibayar" to root.optString("paid")
                    )
                    var ty = totalsTop + 11f
                    val pLab = tp(9.5f, false, gray, android.graphics.Paint.Align.LEFT)
                    val pVal = tp(9.5f, true, dark, android.graphics.Paint.Align.RIGHT)
                    for ((k, v) in totals) { txt(k, 330f, ty, pLab); txt(v, R, ty, pVal); ty += 16f }
                    fill(dark, 330f, ty - 11f, R, ty + 13f)
                    txt("Sisa Tagihan", 338f, ty + 5f, tp(9.5f, true, android.graphics.Color.WHITE, android.graphics.Paint.Align.LEFT))
                    txt(root.optString("remaining"), R - 8f, ty + 5f, tp(9.5f, true, android.graphics.Color.WHITE, android.graphics.Paint.Align.RIGHT))
                    txt("Tanda terima", M, footTop, tp(9.5f, true, dark, android.graphics.Paint.Align.LEFT))
                    txt(root.optString("customer"), M, footTop + 78f, tp(10f, true, dark, android.graphics.Paint.Align.LEFT))
                    val fc = R - 95f
                    txt("Hormat kami", fc, footTop, tp(9.5f, false, dark, android.graphics.Paint.Align.CENTER))
                    val ttd = bmp("ttd.png", 44f)
                    val stempel = bmp("logo-stempel.png", 42f)
                    if (ttd != null) c.drawBitmap(ttd, fc - ttd.width / 2f, footTop + 8f, null)
                    if (stempel != null) {
                        val sx = fc + (if (ttd != null) ttd.width / 2f else 20f) - 8f
                        val scx = sx + stempel.width / 2f
                        val scy = footTop + 18f + stempel.height / 2f
                        c.save(); c.rotate(-8f, scx, scy); c.drawBitmap(stempel, sx, footTop + 18f, null); c.restore()
                    }
                    txt(biz.optString("name"), fc, footTop + 78f, tp(10f, true, dark, android.graphics.Paint.Align.CENTER))
                    doc.finishPage(page)
                    val dir = File(cacheDir, "shared").apply { mkdirs() }
                    val out = File(dir, fileName)
                    java.io.FileOutputStream(out).use { doc.writeTo(it) }
                    doc.close()
                    val uri = FileProvider.getUriForFile(this@MainActivity, "id.zerone.mbg.fileprovider", out)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Kirim nota PDF"))
                    webView.evaluateJavascript("window.__pdfShareOk&&window.__pdfShareOk()", null)
                } catch (e: Exception) {
                    val em = (e.message ?: "native").replace(Regex("[^A-Za-z0-9 _-]"), "").take(40)
                    webView.evaluateJavascript("window.__pdfShareFailed&&window.__pdfShareFailed('native: " + em + "')", null)
                }
            }
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN_FILE) {
            val result = if (resultCode == Activity.RESULT_OK && data?.data != null) arrayOf(data.data!!) else null
            fileCallback?.onReceiveValue(result)
            fileCallback = null
        } else if (requestCode == REQUEST_SAVE_FILE) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                try {
                    contentResolver.openOutputStream(data.data!!)?.use { out: OutputStream ->
                        out.write(pendingBackup ?: ByteArray(0))
                    }
                    Toast.makeText(this, "Backup MBG berhasil disimpan.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Gagal menyimpan backup: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    pendingBackup = null
                }
            } else {
                pendingBackup = null
            }
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
