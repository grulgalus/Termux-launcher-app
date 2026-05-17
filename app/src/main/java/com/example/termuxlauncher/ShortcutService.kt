package com.example.termuxlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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
import kotlin.concurrent.thread

class ShortcutService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var spotlightView: View? = null
    private var cachedApps: List<AppItem> = emptyList()

    data class SearchResult(val title: String, val subtitle: String, val action: () -> Unit)
    data class AppItem(val name: String, val packageName: String)

    override fun onServiceConnected() {
        Toast.makeText(this, "Super Spotlight připraven!", Toast.LENGTH_SHORT).show()
        
        // Magie č.1: Načteme seznam všech aplikací jen JEDNOU a NA POZADÍ!
        thread {
            val apps = packageManager.getInstalledApplications(0)
            val temp = mutableListOf<AppItem>()
            for (app in apps) {
                // Přidáme jen ty aplikace, které jdou reálně spustit
                if (packageManager.getLaunchIntentForPackage(app.packageName) != null) {
                    val appName = app.loadLabel(packageManager).toString()
                    temp.add(AppItem(appName, app.packageName))
                }
            }
            cachedApps = temp
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val isShift = event.isShiftPressed
        val isOption = event.isAltPressed || event.isMetaPressed

        if (spotlightView != null && event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_DOWN) closeSpotlight()
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_ENTER && isShift) {
            if (event.action == KeyEvent.ACTION_DOWN) toggleSpotlight()
            return true
        }

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
            
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 40f
                    setColor(Color.parseColor("#F2181818"))
                }
                setPadding(50, 50, 50, 50)
            }

            val input = EditText(ctx).apply {
                hint = "Hledat aplikace, soubory, nastavení..."
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#888888"))
                textSize = 22f
                isSingleLine = true
                background = null
                setPadding(0, 0, 0, 30)
            }

            val resultsContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }
            val scroll = ScrollView(ctx).apply {
                addView(resultsContainer)
            }

            // Handler pro vracení výsledků z vlákna zpět do UI
            val mainHandler = Handler(Looper.getMainLooper())

            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val query = s.toString().lowercase().trim()
                    
                    if (query.isEmpty()) {
                        resultsContainer.removeAllViews()
                        return
                    }

                    // Magie č.2: Samotné vyhledávání počítáme na pozadí, abychom nelagovali klávesnici
                    thread {
                        val results = performSearch(query)
                        
                        // Zpět do hlavního vlákna pro vykreslení
                        mainHandler.post {
                            // Ještě jednou zkontrolujeme, jestli text uživatel nesmazal
                            if (input.text.toString().trim() != query) return@post
                            
                            resultsContainer.removeAllViews()
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
                }
            })

            layout.addView(input)
            
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

        if ("nastaveni".contains(query) || "settings".contains(query)) {
            results.add(SearchResult("Nastavení (Settings)", "Systém") {
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            })
        }

        // Bleskové hledání v paměti cache (názvy aplikací)
        for (app in cachedApps) {
            if (app.name.lowercase().contains(query)) {
                results.add(SearchResult(app.name, "Aplikace: ${app.packageName}") {
                    launchFreeform(app.packageName)
                })
            }
        }

        // Hledání souborů
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
        } catch (e: Exception) {}

        return results.take(8)
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
            }
        } catch (e: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
