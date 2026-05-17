package com.example.termuxlauncher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlin.concurrent.thread

class ShortcutService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var spotlightView: View? = null
    private var cachedApps: List<AppItem> = emptyList()

    data class SearchResult(val title: String, val subtitle: String, val action: () -> Unit)
    data class AppItem(val name: String, val packageName: String)

    // HLÍDAČ INSTALACÍ: Čeká, až něco nainstaluješ/smažeš a hned to updatne!
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateAppCache() // Bleskový update na pozadí
        }
    }

    override fun onServiceConnected() {
        Toast.makeText(this, "Omarchy Menu načteno (Blesková verze)!", Toast.LENGTH_SHORT).show()
        
        // 1. Načteme to jednou po startu
        updateAppCache()

        // 2. Zapneme hlídače instalací aplikací (Nežere baterku)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(packageReceiver) } catch (e: Exception) {}
    }

    private fun updateAppCache() {
        thread {
            val apps = packageManager.getInstalledApplications(0)
            val temp = mutableListOf<AppItem>()
            for (app in apps) {
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
                    cornerRadius = 16f
                    setColor(Color.parseColor("#E60F0F14"))
                    setStroke(4, Color.parseColor("#89B4FA"))
                }
                setPadding(40, 40, 40, 40)
            }

            val searchPanel = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 20)
            }

            val prompt = TextView(ctx).apply {
                text = "> "
                setTextColor(Color.parseColor("#89B4FA"))
                textSize = 26f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 10, 0)
            }

            val input = EditText(ctx).apply {
                hint = "Vyhledat aplikaci..."
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#555555"))
                textSize = 24f
                isSingleLine = true
                background = null
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            searchPanel.addView(prompt)
            searchPanel.addView(input)
            layout.addView(searchPanel)
            
            val divider = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
                setBackgroundColor(Color.parseColor("#333333"))
            }
            layout.addView(divider)

            val resultsContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 0)
            }
            val scroll = ScrollView(ctx).apply {
                addView(resultsContainer)
            }

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

                    thread {
                        val results = performSearch(query)
                        mainHandler.post {
                            if (input.text.toString().trim() != query) return@post
                            
                            resultsContainer.removeAllViews()
                            for (res in results) {
                                val itemLayout = LinearLayout(ctx).apply {
                                    orientation = LinearLayout.VERTICAL
                                    setPadding(20, 20, 20, 20)
                                    isClickable = true
                                    background = GradientDrawable().apply {
                                        setColor(Color.TRANSPARENT)
                                        cornerRadius = 8f
                                    }
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
                                    setTextColor(Color.parseColor("#A6ADC8"))
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

            layout.addView(scroll)

            val params = WindowManager.LayoutParams(
                1200, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
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

        if ("nastavení".contains(query) || "settings".contains(query)) {
            results.add(SearchResult("Nastavení", "Systém") {
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            })
        }

        for (app in cachedApps) {
            if (app.name.lowercase().contains(query)) {
                results.add(SearchResult(app.name, "Aplikace: ${app.packageName}") {
                    launchFreeform(app.packageName)
                })
            }
        }

        return results.take(10)
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
