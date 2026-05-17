package com.example.termuxlauncher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
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

    private lateinit var prefs: SharedPreferences
    private var currentFps = 10
    private var blacklist = mutableSetOf<String>()
    private var activePackage = "" 

    // OCHRANA PŘED BUGEM (STAVY MENU)
    private enum class MenuState { MAIN, SYS_SETTINGS, SPOT_SETTINGS, BLACKLIST }
    private var currentState = MenuState.MAIN
    private var isProgrammaticTextChange = false // Brání vyhledávači, aby bláznil, když mažeme text kódem

    private var currentResults = listOf<SearchResult>()
    private var spotlightInput: EditText? = null
    private var resultsContainer: LinearLayout? = null

    data class SearchResult(val title: String, val subtitle: String, val action: () -> Unit)
    data class AppItem(val name: String, val packageName: String)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateAppCache()
        }
    }

    override fun onServiceConnected() {
        prefs = getSharedPreferences("SpotlightPrefs", Context.MODE_PRIVATE)
        currentFps = prefs.getInt("fps", 10)
        blacklist = prefs.getStringSet("blacklist", mutableSetOf())?.toMutableSet() ?: mutableSetOf()

        Toast.makeText(this, "Omarchy Menu: Bug opraven!", Toast.LENGTH_SHORT).show()
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.let { activePackage = it.toString() }
        }
    }

    override fun onInterrupt() {}

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
            cachedApps = temp.sortedBy { it.name.lowercase() }
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
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (activePackage in blacklist) {
                    Toast.makeText(this, "Spotlight je zde zakázán!", Toast.LENGTH_SHORT).show()
                } else {
                    toggleSpotlight()
                }
            }
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_T && isOption) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (activePackage !in blacklist) {
                    launchFreeform("com.termux")
                    closeSpotlight()
                }
            }
            return true
        }

        return super.onKeyEvent(event)
    }

    // Bezpečné smazání textu bez vyvolání chybného překreslení
    private fun changeTextSafely(text: String) {
        isProgrammaticTextChange = true
        spotlightInput?.setText(text)
        spotlightInput?.setSelection(text.length)
        isProgrammaticTextChange = false
    }

    private fun toggleSpotlight() {
        if (rootOverlay != null) {
            closeSpotlight()
            return
        }

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val ctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
            
            rootOverlay = FrameLayout(ctx).apply {
                setBackgroundColor(Color.parseColor("#66000000")) 
                setOnClickListener { closeSpotlight() }
            }

            val menuCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 24f
                    setColor(Color.parseColor("#181825")) 
                    setStroke(2, Color.parseColor("#313244"))
                }
                isClickable = true 
            }

            val header = TextView(ctx).apply {
                text = "Omarchy Spotlight"
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

            spotlightInput = EditText(ctx).apply {
                hint = "Hledat aplikace..."
                setTextColor(Color.parseColor("#CDD6F4"))
                setHintTextColor(Color.parseColor("#585B70"))
                textSize = 20f
                isSingleLine = true
                background = null
                setPadding(0, 0, 0, 30)
            }
            bodyLayout.addView(spotlightInput)

            resultsContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val scroll = ScrollView(ctx).apply { addView(resultsContainer) }
            bodyLayout.addView(scroll)
            menuCard.addView(bodyLayout)

            val cardParams = FrameLayout.LayoutParams(1100, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            rootOverlay?.addView(menuCard, cardParams)

            showDefaultMenu()

            spotlightInput?.setOnKeyListener { _, keyCode, keyEvent ->
                if (keyCode == KeyEvent.KEYCODE_ENTER && keyEvent.action == KeyEvent.ACTION_DOWN) {
                    if (currentResults.isNotEmpty() && currentState != MenuState.BLACKLIST) {
                        currentResults[0].action() 
                        closeSpotlight()
                    }
                    return@setOnKeyListener true
                }
                false
            }

            val mainHandler = Handler(Looper.getMainLooper())
            var lastSearchRunnable: Runnable? = null 

            spotlightInput?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    // POKUD PŘEPISUJEME KÓDEM, ZASTAVÍME TENTO BLOK!
                    if (isProgrammaticTextChange) return

                    val query = s.toString().lowercase().trim()
                    lastSearchRunnable?.let { mainHandler.removeCallbacks(it) }

                    val delayMs = 1000L / currentFps

                    lastSearchRunnable = Runnable {
                        if (currentState == MenuState.BLACKLIST) {
                            renderBlacklist(query)
                            return@Runnable
                        }

                        // KDYŽ SMAŽEŠ TEXT, VRÁTÍ TĚ TO PŘESNĚ TAM, KDE JSI BYL!
                        if (query.isEmpty()) {
                            when (currentState) {
                                MenuState.MAIN -> showDefaultMenu()
                                MenuState.SYS_SETTINGS -> showSettingsSubmenu()
                                MenuState.SPOT_SETTINGS -> showCustomSettings()
                                else -> showDefaultMenu()
                            }
                            return@Runnable
                        }

                        // Vlastní hledání aplikací (jen pokud zrovna nespravuješ blacklist)
                        thread {
                            val results = mutableListOf<SearchResult>()
                            for (app in cachedApps) {
                                if (app.name.lowercase().contains(query)) {
                                    results.add(SearchResult(app.name, "Aplikace: ${app.packageName}") {
                                        launchFreeform(app.packageName)
                                    })
                                }
                            }
                            
                            val topResults = results.take(8)
                            
                            mainHandler.post {
                                if (spotlightInput?.text.toString().trim() != query) return@post
                                
                                currentResults = topResults 
                                resultsContainer?.removeAllViews()

                                if (topResults.isEmpty()) {
                                    val fallbackAction = {
                                        val url = "https://www.google.com/search?q=" + Uri.encode(query)
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        startActivity(intent)
                                        closeSpotlight()
                                    }
                                    currentResults = listOf(SearchResult("Hledat na webu", "", fallbackAction))
                                    addMenuItem("Hledat na webu", "Vyhledat \"$query\" v prohlížeči", "■ ", "#89B4FA", fallbackAction)
                                } else {
                                    for (res in topResults) {
                                        addMenuItem(res.title, res.subtitle, "■ ", "#89B4FA") { res.action(); closeSpotlight() }
                                    }
                                }
                            }
                        }
                    }
                    mainHandler.postDelayed(lastSearchRunnable!!, delayMs)
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
            spotlightInput?.requestFocus()

        } catch (e: Exception) {}
    }

    // --- ZÁKLADNÍ MENU ---
    private fun showDefaultMenu() {
        currentState = MenuState.MAIN
        currentResults = emptyList()
        resultsContainer?.removeAllViews()
        
        addMenuItem("Nastavení systému", "Wi-Fi, Bluetooth, Přístupnost...", "⚙ ", "#89B4FA") {
            showSettingsSubmenu()
        }
        
        addMenuItem("Nastavení Spotlightu", "Rychlost (FPS) a Zakázané aplikace", "🛠 ", "#F9E2AF") {
            showCustomSettings()
        }
        
        addMenuItem("Nainstalovat aplikace", "Otevřít Obchod Play", "■ ", "#A6E3A1") {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent); closeSpotlight()
            } catch (e: Exception) {}
        }
    }

    // --- ANDROID NASTAVENÍ PODMENU ---
    private fun showSettingsSubmenu() {
        currentState = MenuState.SYS_SETTINGS
        changeTextSafely("")
        spotlightInput?.hint = "Hledat aplikace..."
        resultsContainer?.removeAllViews()

        addMenuItem("◄ Zpět", "Zpět do hlavního menu", "■ ", "#CDD6F4") { showDefaultMenu() }
        
        addMenuItem("Wi-Fi", "Připojení k síti", "■ ", "#89B4FA") {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); closeSpotlight()
        }
        addMenuItem("Bluetooth", "Spárovat zařízení", "■ ", "#89B4FA") {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); closeSpotlight()
        }
        addMenuItem("Přístupnost", "Nastavení usnadnění", "■ ", "#89B4FA") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); closeSpotlight()
        }
        addMenuItem("Všechna nastavení", "Otevřít hlavní systémové nastavení", "■ ", "#89B4FA") {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); closeSpotlight()
        }
    }

    // --- TVOJE VLASTNÍ NASTAVENÍ SPOTLIGHTU ---
    private fun showCustomSettings() {
        currentState = MenuState.SPOT_SETTINGS
        changeTextSafely("")
        spotlightInput?.hint = "Hledat aplikace..."
        resultsContainer?.removeAllViews()

        addMenuItem("◄ Zpět", "Zpět do hlavního menu", "■ ", "#CDD6F4") { showDefaultMenu() }
        
        addMenuItem("Rychlost překreslování", "Aktuálně: $currentFps FPS (Klikni pro změnu)", "⚡ ", "#A6E3A1") {
            currentFps = when (currentFps) {
                10 -> 30
                30 -> 60
                else -> 10
            }
            prefs.edit().putInt("fps", currentFps).apply()
            showCustomSettings()
        }

        addMenuItem("Zakázané aplikace (Blacklist)", "Vybrat, kde se menu neotevře", "🚫 ", "#F38BA8") {
            currentState = MenuState.BLACKLIST
            changeTextSafely("")
            spotlightInput?.hint = "Hledat aplikaci k zakázání..."
            renderBlacklist("")
        }
    }

    // --- BLACKLIST MENU ---
    private fun renderBlacklist(query: String) {
        val mainHandler = Handler(Looper.getMainLooper())
        thread {
            val filtered = cachedApps.filter { it.name.lowercase().contains(query) }.take(15)
            mainHandler.post {
                resultsContainer?.removeAllViews()
                addMenuItem("◄ Zpět", "Zpět do nastavení", "■ ", "#CDD6F4") { showCustomSettings() }
                
                for (app in filtered) {
                    val isBlocked = app.packageName in blacklist
                    val icon = if (isBlocked) "☒ " else "☐ "
                    val color = if (isBlocked) "#F38BA8" else "#A6E3A1" 
                    
                    addMenuItem(app.name, app.packageName, icon, color) {
                        if (isBlocked) blacklist.remove(app.packageName) else blacklist.add(app.packageName)
                        prefs.edit().putStringSet("blacklist", blacklist).apply()
                        renderBlacklist(query) 
                    }
                }
            }
        }
    }

    // VYKRESLOVAČ POLOŽEK
    private fun addMenuItem(title: String, subtitle: String, iconText: String, iconColor: String, action: () -> Unit) {
        val ctx = resultsContainer?.context ?: return
        val itemLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 20, 20, 20)
            isClickable = true
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 12f
            }
            setOnClickListener { action() }
        }
        
        val icon = TextView(ctx).apply {
            text = iconText
            setTextColor(Color.parseColor(iconColor)) 
            textSize = 16f
            setPadding(0, 0, 20, 0)
        }
        
        val textLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
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
        resultsContainer?.addView(itemLayout)
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
}
