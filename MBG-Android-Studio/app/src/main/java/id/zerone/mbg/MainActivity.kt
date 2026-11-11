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
        @JavascriptInterface
        fun appVersion(): String {
            return try {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            } catch (e: Exception) { "" }
        }

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
