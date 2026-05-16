package com.example.termuxlauncher

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.view.ContextThemeWrapper
import android.util.Log

class ShortcutService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var spotlightView: View? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Detekce ALT (nebo OPTION/META na externí klávesnici)
        val isAlt = event.isAltPressed || event.isMetaPressed

        // Zkratka ALT + MEZERNÍK pro Spotlight (jako na Macu)
        if (event.keyCode == KeyEvent.KEYCODE_SPACE && isAlt) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                toggleSpotlight()
            }
            return true
        }

        // Původní ALT + T pro okamžitý Termux
        if (event.keyCode == KeyEvent.KEYCODE_T && isAlt) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                launchAppInFreeform("com.termux")
            }
            return true
        }

        return super.onKeyEvent(event)
    }

    private fun toggleSpotlight() {
        if (spotlightView != null) {
            hideSpotlight()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Vytváříme grafiku menu přímo v kódu! (Tmavý MacOS styl)
        val ctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val shape = GradientDrawable().apply {
                cornerRadius = 30f
                setColor(Color.parseColor("#EE202020")) // Tmavě šedá poloprůhledná
            }
            background = shape
            setPadding(40, 40, 40, 40)
            elevation = 50f
        }

        val input = EditText(ctx).apply {
            hint = "Napiš ID aplikace (např. com.android.settings)..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 22f
            setBackgroundColor(Color.TRANSPARENT)
            isSingleLine = true
            requestFocus()

            // Odeslání Enterem
            setOnEditorActionListener { _, actionId, event ->
                if (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                    val pkgName = text.toString().trim()
                    if (pkgName.isNotEmpty()) {
                        launchAppInFreeform(pkgName)
                        hideSpotlight()
                    }
                    true
                } else false
            }
        }

        layout.addView(input)

        // Tady používáme naše tvrdě vydobyté právo kreslit přes obrazovku!
        val params = WindowManager.LayoutParams(
            800, // Šířka menu v pixelech
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 200 // Odsazení zeshora
        }

        windowManager?.addView(layout, params)
        spotlightView = layout
    }

    private fun hideSpotlight() {
        spotlightView?.let {
            windowManager?.removeView(it)
            spotlightView = null
        }
    }

    private fun launchAppInFreeform(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                Log.e("TermuxLauncher", "Aplikace $packageName nenalezena!")
                return
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

            // !!! MAGIE: Násilné Freeform Okénko !!!
            val options = ActivityOptions.makeBasic()
            
            // Reflexe pro hacknutí skrytého API (5 = WINDOWING_MODE_FREEFORM)
            try {
                val method = ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                method.invoke(options, 5) 
            } catch (e: Exception) {}

            // Říkáme Androidu přesné souřadnice, kde se má okno vykreslit
            // Vlevo 100, Nahoře 100, Vpravo 1100, Dole 800
            options.launchBounds = Rect(100, 100, 1100, 800)

            startActivity(intent, options.toBundle())
            Log.d("TermuxLauncher", "Spuštěno v okně: $packageName")
            
        } catch (e: Exception) {
            Log.e("TermuxLauncher", "Chyba spouštění: ${e.message}")
        }
    }
}
