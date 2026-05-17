package com.example.termuxlauncher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.ContextThemeWrapper
import kotlin.concurrent.thread

class ShortcutService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var rootOverlay: FrameLayout? = null
    private var cachedApps: List<AppItem> = emptyList()

    data class SearchResult(val title: String, val subtitle: String, val action: () -> Unit)
    data class AppItem(val name: String, val packageName: String)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateAppCache()
        }
    }

    override fun onServiceConnected() {
        Toast.makeText(this, "Omarchy Menu v3: Připraveno!", Toast.LENGTH_SHORT).show()
        updateAppCache()
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

        if (rootOverlay != null && event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
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
        if (rootOverlay != null) {
            closeSpotlight()
            return
        }

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val ctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
            
            // 1. NEVIDITELNÁ VRSTVA (Chytá kliknutí vedle)
            rootOverlay = FrameLayout(ctx).apply {
                setBackgroundColor(Color.parseColor("#66000000")) 
                setOnClickListener { closeSpotlight() }
            }

            // 2. OMARCHY RÁMEČEK
            val menuCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 24f
                    setColor(Color.parseColor("#181825")) // Catppuccin tmavá
                    setStroke(2, Color.parseColor("#313244"))
                }
                isClickable = true 
            }

            // HLAVIČKA
            val header = TextView(ctx).apply {
                text = "System Menu"
                setTextColor(Color.parseColor("#CDD6F4"))
                textSize = 14f
                setPadding(50, 30, 50, 30)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E1E2E"))
                    cornerRadii = floatArrayOf(24f, 24f, 24f, 24f, 0f, 0f, 0f, 0f)
                }
            }
            menuCard.addView(header)

            val bodyLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 40, 40, 40)
            }

            val input = EditText(ctx).apply {
                hint = "Search apps or execute..."
                setTextColor(Color.parseColor("#CDD6F4"))
                setHintTextColor(Color.parseColor("#585B70"))
                textSize = 20f
                isSingleLine = true
                background = null
                setPadding(0, 0, 0, 30)
            }
            bodyLayout.addView(input)

            val resultsContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }
            val scroll = ScrollView(ctx).apply {
                addView(resultsContainer)
            }
            bodyLayout.addView(scroll)
            menuCard.addView(bodyLayout)

            val cardParams = FrameLayout.LayoutParams(1100, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            rootOverlay?.addView(menuCard, cardParams)

            // VYKRESLENÍ DEFAULTNÍHO MENU (Když není nic napsáno)
            showDefaultMenu(resultsContainer)

            val mainHandler = Handler(Looper.getMainLooper())

            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val query = s.toString().lowercase().trim()
                    
                    // KDYŽ UŽIVATEL SMAŽE TEXT, UKÁŽE SE ZPĚT MENU (Settings, Shortcuts...)
                    if (query.isEmpty()) {
                        mainHandler.post { showDefaultMenu(resultsContainer) }
                        return
                    }

                    // BLESKOVÉ HLEDÁNÍ APLIKACÍ
                    thread {
                        val results = mutableListOf<SearchResult>()
                        for (app in cachedApps) {
                            if (app.name.lowercase().contains(query)) {
                                results.add(SearchResult(app.name, "App: ${app.packageName}") {
                                    launchFreeform(app.packageName)
                                })
                            }
                        }
                        
                        val topResults = results.take(8)
                        
                        mainHandler.post {
                            if (input.text.toString().trim() != query) return@post
                            
                            resultsContainer.removeAllViews()
                            for (res in topResults) {
                                addMenuItem(resultsContainer, res.title, res.subtitle, res.action)
                            }
                        }
                    }
                }
            })

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, 
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )

            windowManager?.addView(rootOverlay, params)
            input.requestFocus()

        } catch (e: Exception) {}
    }

    // TADY JE TVÉ DEFAULTNÍ MENU
    private fun showDefaultMenu(container: LinearLayout) {
        container.removeAllViews()
        
        addMenuItem(container, "Settings", "System settings") {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        
        addMenuItem(container, "Shortcuts", "Manage keybinds") {
            Toast.makeText(this, "Tady si časem naprogramuješ Shortcuts", Toast.LENGTH_SHORT).show()
        }
        
        addMenuItem(container, "Install apps", "Open app store") {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q="))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Obchod Google Play nebyl nalezen", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // UNIVERZÁLNÍ VYKRESLOVAČ POLOŽEK (S tou modrou čtverečkovou ikonkou)
    private fun addMenuItem(container: LinearLayout, title: String, subtitle: String, action: () -> Unit) {
        val ctx = container.context
        val itemLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 20, 20, 20)
            isClickable = true
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 12f
            }
            setOnClickListener { action(); closeSpotlight() }
        }
        
        val icon = TextView(ctx).apply {
            text = "■ "
            setTextColor(Color.parseColor("#89B4FA")) // Catppuccin Modrá
            textSize = 14f
            setPadding(0, 0, 20, 0)
        }
        
        val textLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        
        val titleView = TextView(ctx).apply {
            text = title
            setTextColor(Color.parseColor("#CDD6F4"))
            textSize = 18f
        }
        textLayout.addView(titleView)
        
        if (subtitle.isNotEmpty()) {
            val subView = TextView(ctx).apply {
                text = subtitle
                setTextColor(Color.parseColor("#A6ADC8"))
                textSize = 12f
            }
            textLayout.addView(subView)
        }
        
        itemLayout.addView(icon)
        itemLayout.addView(textLayout)
        container.addView(itemLayout)
    }

    private fun closeSpotlight() {
        if (rootOverlay != null) {
            windowManager?.removeView(rootOverlay)
            rootOverlay = null
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
