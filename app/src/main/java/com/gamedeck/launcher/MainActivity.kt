package com.gamedeck.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var cutout = "0,0,0,0"
    private val reqPick = 4711
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    private val bgFile by lazy { File(filesDir, "bg.jpg") }

    private var lastInsets: WindowInsets? = null
    private var dbgText = "No info yet"

    // Small status line shown when you tap "Launcher" in the top-left (helps find cutout problems)
    private fun refreshDbg() {
        if (!::web.isInitialized) return
        val loc = IntArray(2)
        web.getLocationOnScreen(loc)
        val sb = StringBuilder("SDK ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER}")
        sb.append(" | page x=${loc[0]} w=${web.width} | screen w=${resources.displayMetrics.widthPixels}")
        if (Build.VERSION.SDK_INT >= 28) {
            sb.append(" | mode=${window.attributes.layoutInDisplayCutoutMode}")
            sb.append(" | cutout=${lastInsets?.displayCutout?.boundingRects}")
        }
        dbgText = sb.toString()
    }

    private fun pushCutout() {
        web.evaluateJavascript("window.setInsets&&setInsets(" + cutout + ")", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fill the area around the camera cutout with the launcher background instead of a black bar
        val savedBg = prefs.getInt("bgColor", 0xFF0C141C.toInt())
        window.setBackgroundDrawable(ColorDrawable(savedBg))
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(savedBg)
            addJavascriptInterface(Bridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pushCutout()
                    refreshDbg()
                }
            }
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
        // Tell the page how much room the camera cutout takes, so nothing is drawn under it
        window.decorView.setOnApplyWindowInsetsListener { v, insets ->
            lastInsets = insets
            web.post { refreshDbg() }
            if (Build.VERSION.SDK_INT >= 28) {
                val c = insets.displayCutout
                val d = resources.displayMetrics.density
                cutout = if (c == null) "0,0,0,0"
                else "${c.safeInsetLeft / d},${c.safeInsetTop / d},${c.safeInsetRight / d},${c.safeInsetBottom / d}"
                pushCutout()
            }
            v.onApplyWindowInsets(insets)
        }
    }

    // Re-scan every time the launcher comes back, so new installs show up
    override fun onResume() {
        super.onResume()
        web.evaluateJavascript("window.scan&&scan()", null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            web.post { refreshDbg() }
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    // Image picked from the gallery comes back here
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqPick || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val ok = saveBg(uri)
            runOnUiThread {
                web.evaluateJavascript(if (ok) "window.bgPicked&&bgPicked()" else "window.bgFail&&bgFail()", null)
            }
        }.start()
    }

    // Shrink the chosen picture (max 1600px), fix its rotation and keep a private copy
    private fun saveBg(uri: Uri): Boolean {
        return try {
            val max = 1600
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bm = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false
            val rot = contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            val big = maxOf(bm.width, bm.height)
            val scale = if (big > max) max.toFloat() / big else 1f
            if (rot != 0f || scale != 1f) {
                val m = Matrix().apply { postRotate(rot); postScale(scale, scale) }
                bm = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
            }
            FileOutputStream(bgFile).use { bm.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            true
        } catch (e: Throwable) {
            false
        }
    }

    override fun onBackPressed() {
        web.evaluateJavascript("act('back')", null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun apps(): String {
            val pm = packageManager
            val seen = HashSet<String>()
            val out = JSONArray()
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (r in pm.queryIntentActivities(q, 0)) {
                val ai = r.activityInfo.applicationInfo
                if (ai.packageName == packageName || !seen.add(ai.packageName)) continue
                val game = (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0 ||
                    (Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME)
                out.put(
                    JSONObject()
                        .put("p", ai.packageName)
                        .put("n", r.loadLabel(pm).toString())
                        .put("g", game)
                        .put("i", icon(r.loadIcon(pm)))
                )
            }
            return out.toString()
        }

        @JavascriptInterface
        fun dbg(): String = dbgText

        // Background color chosen in Settings: also paints the area around the camera cutout
        @JavascriptInterface
        fun setBgColor(hex: String) {
            val c = try { Color.parseColor(hex) } catch (e: Exception) { return }
            prefs.edit().putInt("bgColor", c).apply()
            runOnUiThread {
                window.setBackgroundDrawable(ColorDrawable(c))
                web.setBackgroundColor(c)
            }
        }

        @JavascriptInterface
        fun pickImage() {
            runOnUiThread {
                val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                try {
                    startActivityForResult(Intent.createChooser(i, null), reqPick)
                } catch (e: Exception) {
                    web.evaluateJavascript("window.bgFail&&bgFail()", null)
                }
            }
        }

        @JavascriptInterface
        fun bg(): String = try {
            if (!bgFile.exists()) "" else "data:image/jpeg;base64," + Base64.encodeToString(bgFile.readBytes(), Base64.NO_WRAP)
        } catch (e: Throwable) {
            ""
        }

        @JavascriptInterface
        fun clearBg() {
            bgFile.delete()
        }

        @JavascriptInterface
        fun launch(pkg: String) {
            runOnUiThread { packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } }
        }

        private fun icon(d: Drawable): String {
            val bm = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 160, 160)
            d.draw(Canvas(bm))
            val o = ByteArrayOutputStream()
            bm.compress(Bitmap.CompressFormat.PNG, 90, o)
            return "data:image/png;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
        }
    }
}
