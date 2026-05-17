package com.example.termuxlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.ContextThemeWrapper
import java.io.File

class ShortcutService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var spotlightView: View? = null
    private var cachedApps: List<ApplicationInfo> = emptyList()

    data class SearchResult(val title: String, val subtitle: String, val action: () -> Unit)

    override fun onServiceConnected() {
        Toast.makeText(this, "Super Spotlight připraven!", Toast.LENGTH_SHORT).show()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val isShift = event.isShiftPressed
        val isOption = event.isAltPressed || event.isMetaPressed

        if (spotlightView != null && event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_DOWN) closeSpotlight()
            return true
        }

        // ZKRATKA: SHIFT + ENTER (Otevře Spotlight)
        if (event.keyCode == KeyEvent.KEYCODE_ENTER && isShift) {
            if (event.action == KeyEvent.ACTION_DOWN) toggleSpotlight()
            return true
        }

        // ZKRATKA: OPTION + T (Termux)
        if (event.keyCode == KeyEvent.KEYCODE_T && isOption) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                launchFreeform("com.termux")
                closeSpotlight()
            }
            return true
        }

        return super.onKeyEvent(event)
    }

    private fun toggleSpotlight() {
        if (spotlightView != null) {
            closeSpotlight()
            return
        }

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val ctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
            
            // Načteme aplikace do paměti pro bleskové hledání
            cachedApps = packageManager.getInstalledApplications(0)

            // Hlavní okno (Card)
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 40f
                    setColor(Color.parseColor("#F2181818")) // Hezká tmavě šedá s průhledností
                }
                setPadding(50, 50, 50, 50)
            }

            // Vyhledávací pole
            val input = EditText(ctx).apply {
                hint = "Hledat aplikace, soubory, nastavení..."
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#888888"))
                textSize = 22f
                isSingleLine = true
                background = null // Odstraní ošklivou čáru dole
                setPadding(0, 0, 0, 30)
            }

            // Kontejner na výsledky
            val resultsContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }
            val scroll = ScrollView(ctx).apply {
                addView(resultsContainer)
            }

            // Logika hledání při psaní
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    resultsContainer.removeAllViews()
                    val query = s.toString().lowercase().trim()
                    
                    if (query.isNotEmpty()) {
                        val results = performSearch(query)
                        for (res in results) {
                            val itemLayout = LinearLayout(ctx).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(0, 25, 0, 25)
                                isClickable = true
                                setOnClickListener { res.action(); closeSpotlight() }
                            }
                            
                            val titleView = TextView(ctx).apply {
                                text = res.title
                                setTextColor(Color.WHITE)
                                textSize = 18f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                            }
                            val subView = TextView(ctx).apply {
                                text = res.subtitle
                                setTextColor(Color.parseColor("#AAAAAA"))
                                textSize = 12f
                            }
                            
                            itemLayout.addView(titleView)
                            itemLayout.addView(subView)
                            resultsContainer.addView(itemLayout)
                        }
                    }
                }
            })

            layout.addView(input)
            
            // Dělící čára
            val divider = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
                setBackgroundColor(Color.parseColor("#444444"))
                setPadding(0, 10, 0, 10)
            }
            layout.addView(divider)
            
            layout.addView(scroll)

            val params = WindowManager.LayoutParams(
                1000, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 200
            }

            windowManager?.addView(layout, params)
            spotlightView = layout
            input.requestFocus()

        } catch (e: Exception) {
            Toast.makeText(this, "Chyba okna: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun performSearch(query: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // 1. ZKRATKY A NASTAVENÍ
        if ("nastaveni".contains(query) || "settings".contains(query)) {
            results.add(SearchResult("Nastavení (Settings)", "Systém") {
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            })
        }

        // 2. APLIKACE (Hledá podle reálného jména)
        for (app in cachedApps) {
            val appName = app.loadLabel(packageManager).toString()
            if (appName.lowercase().contains(query)) {
                results.add(SearchResult(appName, "Aplikace: ${app.packageName}") {
                    launchFreeform(app.packageName)
                })
            }
        }

        // 3. SOUBORY A SLOŽKY (Základní skenování /sdcard/)
        try {
            val sdcard = File("/sdcard/")
            sdcard.listFiles()?.forEach { file ->
                if (file.name.lowercase().contains(query)) {
                    val type = if (file.isDirectory) "Složka" else "Soubor"
                    results.add(SearchResult(file.name, "$type v ${file.absolutePath}") {
                        Toast.makeText(this, "Nalezeno: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                    })
                }
            }
        } catch (e: Exception) {
            // Ignorujeme, pokud nemáme práva
        }

        return results.take(8) // Omezíme na max 8 výsledků, ať to nevypadá blbě
    }

    private fun closeSpotlight() {
        if (spotlightView != null) {
            windowManager?.removeView(spotlightView)
            spotlightView = null
        }
    }

    private fun launchFreeform(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                val options = android.app.ActivityOptions.makeBasic()
                try {
                    val method = options.javaClass.getMethod("setLaunchWindowingMode", Int::class.java)
                    method.invoke(options, 5)
                } catch (e: Exception) {}
                startActivity(intent, options.toBundle())
            } else {
                Toast.makeText(this, "Aplikace nejde spustit", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
