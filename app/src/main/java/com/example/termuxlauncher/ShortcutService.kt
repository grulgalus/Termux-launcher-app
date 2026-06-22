package com.example.termuxlauncher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import android.view.ContextThemeWrapper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class ShortcutService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var rootOverlay: FrameLayout? = null
    private var persistentWidget: View? = null 
    private var cachedApps: List<AppItem> = emptyList()

    private lateinit var prefs: SharedPreferences
    private var currentFps = 10
    private var blacklist = mutableSetOf<String>()
    private var activePackage = "" 

    private enum class MenuState { MAIN, SYS_SETTINGS, SPOT_SETTINGS, WIDGET_SETTINGS, BLACKLIST }
    private var currentState = MenuState.MAIN
    private var isProgrammaticTextChange = false 

    private var currentResults = listOf<SearchResult>()
    private var spotlightInput: EditText? = null
    private var resultsContainer: LinearLayout? = null
    private var widgetsScroll: HorizontalScrollView? = null

    data class SearchResult(val title: String, val subtitle: String, val icon: Drawable?, val emoji: String, val action: () -> Unit)
    data class AppItem(val name: String, val packageName: String, val icon: Drawable?)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateAppCache()
        }
    }

    override fun onServiceConnected() {
        prefs = getSharedPreferences("MacSpotlightPrefs", Context.MODE_PRIVATE)
        currentFps = prefs.getInt("fps", 30)
        blacklist = prefs.getStringSet("blacklist", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        
        if (!prefs.contains("active_widgets")) {
            prefs.edit().putStringSet("active_widgets", setOf("clock", "battery")).apply()
        }

        Toast.makeText(this, "MAC Spotlight: Přidána klávesa CTRL+TAB", Toast.LENGTH_SHORT).show()
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
        val type = event?.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            event?.packageName?.let { 
                val pkg = it.toString()
                if (pkg != "com.example.termuxlauncher") {
                    activePackage = pkg
                }
                val isDesktop = activePackage.contains("launcher", true) || 
                                activePackage.contains("home", true) || 
                                activePackage.contains("desktop", true) || 
                                activePackage == "com.android.systemui" || 
                                activePackage.isEmpty()
                
                if (persistentWidget != null) {
                    val mainHandler = Handler(Looper.getMainLooper())
                    mainHandler.post {
                        persistentWidget?.visibility = if (isDesktop) View.VISIBLE else View.GONE
                    }
                }
            }
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
                    val icon = app.loadIcon(packageManager)
                    temp.add(AppItem(appName, app.packageName, icon))
                }
            }
            cachedApps = temp.sortedBy { it.name.lowercase() }
        }
    }

    // --- KLÁVESOVÉ ZKRATKY ---
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val isShift = event.isShiftPressed
        val isOption = event.isAltPressed || event.isMetaPressed
        val isCtrl = event.isCtrlPressed // Detekce CTRL klávesy

        // 1. ZAVŘENÍ SPOTLIGHTU (ESC)
        if (rootOverlay != null && event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_DOWN) closeSpotlight()
            return true
        }

        // 2. NOVINKA: OTEVŘÍT NEDÁVNÉ APLIKACE (CTRL + TAB)
        if (event.keyCode == KeyEvent.KEYCODE_TAB && isCtrl) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                // Vyvolá systémové okno přepínání aplikací!
                performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
            return true
        }

        // 3. OTEVŘÍT SPOTLIGHT (SHIFT + ENTER)
        if (event.keyCode == KeyEvent.KEYCODE_ENTER && isShift) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (activePackage in blacklist) {
                    Toast.makeText(this, "Spotlight zakázán!", Toast.LENGTH_SHORT).show()
                } else {
                    toggleSpotlight()
                }
            }
            return true
        }

        // 4. OTEVŘÍT TERMUX (ALT/OPTION + T)
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
                setBackgroundColor(Color.TRANSPARENT)
                fitsSystemWindows = true 
                setPadding(0, 100, 0, 100) 
                setOnClickListener { closeSpotlight() }
            }

            val menuCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 40f 
                    setColor(Color.parseColor("#F21C1C1E")) 
                    setStroke(2, Color.parseColor("#4DFFFFFF")) 
                }
                isClickable = true 
                setPadding(50, 50, 50, 50)
                elevation = 40f
            }

            widgetsScroll = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, 0, 0, 40)
            }

            val widgetsContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val activeWidgets = prefs.getStringSet("active_widgets", setOf("clock", "battery"))!!
            
            if (activeWidgets.contains("clock")) widgetsContainer.addView(createClockWidget(ctx, false))
            if (activeWidgets.contains("calendar")) widgetsContainer.addView(createCalendarWidget(ctx, false))
            if (activeWidgets.contains("battery")) widgetsContainer.addView(createBatteryWidget(ctx, false))
            if (activeWidgets.contains("system")) widgetsContainer.addView(createSystemWidget(ctx, false))
            if (activeWidgets.contains("custom")) widgetsContainer.addView(createCustomNoteWidget(ctx, false))

            widgetsScroll?.addView(widgetsContainer)
            menuCard.addView(widgetsScroll)

            val searchLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 24f
                    setColor(Color.parseColor("#1AFFFFFF")) 
                }
                setPadding(40, 30, 40, 30)
            }

            val searchIcon = TextView(ctx).apply {
                text = "🔍 "; textSize = 22f; setTextColor(Color.parseColor("#8E8E93"))
            }
            searchLayout.addView(searchIcon)

            spotlightInput = EditText(ctx).apply {
                hint = "Spotlight Search..."
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#8E8E93"))
                textSize = 22f
                isSingleLine = true
                background = null
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            searchLayout.addView(spotlightInput)
            menuCard.addView(searchLayout)

            val divider = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply { setMargins(0, 40, 0, 20) }
                setBackgroundColor(Color.parseColor("#33FFFFFF"))
            }
            menuCard.addView(divider)

            resultsContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val scroll = ScrollView(ctx).apply { addView(resultsContainer) }
            menuCard.addView(scroll)

            val cardParams = FrameLayout.LayoutParams(1250, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            rootOverlay?.addView(menuCard, cardParams)

            showDefaultMenu()

            spotlightInput?.setOnKeyListener { _, keyCode, keyEvent ->
                if (keyCode == KeyEvent.KEYCODE_ENTER && keyEvent.action == KeyEvent.ACTION_DOWN) {
                    if (currentResults.isNotEmpty() && currentState != MenuState.BLACKLIST && currentState != MenuState.WIDGET_SETTINGS) {
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
                    if (isProgrammaticTextChange) return

                    val query = s.toString().lowercase().trim()
                    lastSearchRunnable?.let { mainHandler.removeCallbacks(it) }

                    widgetsScroll?.visibility = if (query.isEmpty() && currentState == MenuState.MAIN) View.VISIBLE else View.GONE

                    val delayMs = 1000L / currentFps

                    lastSearchRunnable = Runnable {
                        if (currentState == MenuState.BLACKLIST) { renderBlacklist(query); return@Runnable }
                        
                        if (currentState == MenuState.WIDGET_SETTINGS) {
                            if (query.isNotEmpty()) {
                                prefs.edit().putString("custom_note_text", s.toString()).apply()
                            }
                            renderWidgetSettings()
                            return@Runnable
                        }

                        if (query.isEmpty()) {
                            when (currentState) {
                                MenuState.MAIN -> showDefaultMenu()
                                MenuState.SYS_SETTINGS -> showSettingsSubmenu()
                                MenuState.SPOT_SETTINGS -> showCustomSettings()
                                else -> showDefaultMenu()
                            }
                            return@Runnable
                        }

                        thread {
                            val results = mutableListOf<SearchResult>()
                            for (app in cachedApps) {
                                if (app.name.lowercase().contains(query)) {
                                    results.add(SearchResult(app.name, "Aplikace: ${app.packageName}", app.icon, "") {
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
                                        startActivity(intent); closeSpotlight()
                                    }
                                    currentResults = listOf(SearchResult("Hledat na webu", "", null, "🌐", fallbackAction))
                                    addMenuItem("Hledat na webu", "Vyhledat \"$query\" v prohlížeči", null, "🌐") { fallbackAction() }
                                } else {
                                    for (res in topResults) {
                                        addMenuItem(res.title, res.subtitle, res.icon, res.emoji) { res.action(); closeSpotlight() }
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
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }

            windowManager?.addView(rootOverlay, params)
            spotlightInput?.requestFocus()

        } catch (e: Exception) {}
    }

    private fun showDefaultMenu() {
        currentState = MenuState.MAIN
        widgetsScroll?.visibility = View.VISIBLE
        currentResults = emptyList()
        resultsContainer?.removeAllViews()
        
        addMenuItem("Plovoucí Dashboard (PC Widget)", "Zobrazí na ploše tvé widgety", null, "🖥️") { 
            spawnPersistentWidget()
            closeSpotlight()
        }
        addMenuItem("Nastavení Widgetů", "Vyber si widgety & Vlastní poznámka", null, "🧩") { 
            currentState = MenuState.WIDGET_SETTINGS
            changeTextSafely("")
            spotlightInput?.hint = "Napiš svou vlastní poznámku zde..."
            renderWidgetSettings() 
        }
        addMenuItem("Systémové předvolby", "Wi-Fi, Bluetooth, Displej...", null, "⚙️") { showSettingsSubmenu() }
        addMenuItem("Spotlight Nastavení", "Rychlost (FPS) a Zakázané aplikace", null, "🛠️") { showCustomSettings() }
    }

    private fun renderWidgetSettings() {
        resultsContainer?.removeAllViews()
        widgetsScroll?.visibility = View.GONE
        
        addMenuItem("◄ Zpět na Spotlight", "Vrátí se do hlavního menu (uloží úpravy)", null, "↩️") { 
            if (persistentWidget != null) { spawnPersistentWidget() }
            showDefaultMenu() 
        }
        
        val activeWidgets = prefs.getStringSet("active_widgets", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        
        val toggleWidget = { key: String, name: String, emoji: String ->
            val isEnabled = activeWidgets.contains(key)
            val icon = if (isEnabled) "✅" else "⬜"
            addMenuItem(name, if (isEnabled) "Zobrazeno na ploše" else "Skryto", null, icon) {
                if (isEnabled) activeWidgets.remove(key) else activeWidgets.add(key)
                prefs.edit().putStringSet("active_widgets", activeWidgets).apply()
                renderWidgetSettings()
            }
        }

        toggleWidget("clock", "Hodiny (Mac style)", "🕒")
        toggleWidget("calendar", "Kalendář", "📅")
        toggleWidget("battery", "Stav Baterie", "🔋")
        toggleWidget("system", "RAM a Úložiště", "💾")
        toggleWidget("custom", "Moje Poznámka (Napiš text do vyhledávání nahoře!)", "📝")
    }

    private fun getMacSquare(ctx: Context, isDesktop: Boolean): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER 
            background = GradientDrawable().apply {
                cornerRadius = if (isDesktop) 45f else 32f
                setColor(if (isDesktop) Color.parseColor("#CC1A1A1A") else Color.parseColor("#26FFFFFF"))
                if (isDesktop) setStroke(2, Color.parseColor("#44FFFFFF"))
            }
            val size = if (isDesktop) 250 else 240
            layoutParams = if (isDesktop) GridLayout.LayoutParams().apply { width = size; height = size; setMargins(15, 15, 15, 15) } 
                           else LinearLayout.LayoutParams(size, size).apply { setMargins(0, 0, 20, 0) }
            if (isDesktop) elevation = 20f
        }
    }

    private fun createClockWidget(ctx: Context, isDesktop: Boolean): LinearLayout {
        return getMacSquare(ctx, isDesktop).apply {
            addView(TextClock(ctx).apply { format24Hour = "HH:mm"; textSize = 34f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER })
            addView(TextClock(ctx).apply { format24Hour = "EEEE"; textSize = 13f; setTextColor(Color.parseColor("#A0A0A0")); setPadding(0, 5, 0, 0); gravity = Gravity.CENTER })
        }
    }

    private fun createCalendarWidget(ctx: Context, isDesktop: Boolean): LinearLayout {
        return getMacSquare(ctx, isDesktop).apply {
            addView(TextView(ctx).apply { text = SimpleDateFormat("MMM", Locale.getDefault()).format(Date()).uppercase(); textSize = 15f; setTextColor(Color.parseColor("#FF3B30")); setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER })
            addView(TextView(ctx).apply { text = SimpleDateFormat("d", Locale.getDefault()).format(Date()); textSize = 42f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER })
        }
    }

    private fun createBatteryWidget(ctx: Context, isDesktop: Boolean): LinearLayout {
        return getMacSquare(ctx, isDesktop).apply {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val batLvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            
            val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            
            addView(TextView(ctx).apply { text = if (isCharging) "⚡" else if (batLvl > 20) "🔋" else "🪫"; textSize = 30f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 5) })
            addView(TextView(ctx).apply { text = "$batLvl %"; textSize = 22f; setTextColor(if (batLvl > 20) Color.parseColor("#34C759") else Color.parseColor("#FF3B30")); setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER })
            if (isCharging) {
                addView(TextView(ctx).apply { text = "Nabíjí se"; textSize = 11f; setTextColor(Color.parseColor("#34C759")); gravity = Gravity.CENTER; setPadding(0,5,0,0) })
            }
        }
    }

    private fun createSystemWidget(ctx: Context, isDesktop: Boolean): LinearLayout {
        return getMacSquare(ctx, isDesktop).apply {
            val actManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            val availRam = memInfo.availMem / 1073741824L
            
            val statFs = StatFs(Environment.getExternalStorageDirectory().path)
            val freeGb = statFs.availableBytes / 1073741824L

            addView(TextView(ctx).apply { text = "RAM: ${availRam} GB"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0,0,0,10) })
            addView(TextView(ctx).apply { text = "Disk: ${freeGb} GB"; textSize = 14f; setTextColor(Color.parseColor("#A0A0A0")); gravity = Gravity.CENTER })
        }
    }

    private fun createCustomNoteWidget(ctx: Context, isDesktop: Boolean): LinearLayout {
        return getMacSquare(ctx, isDesktop).apply {
            val noteText = prefs.getString("custom_note_text", "📝 Napiš něco do hledání!")
            addView(TextView(ctx).apply { text = "Poznámka"; textSize = 12f; setTextColor(Color.parseColor("#8E8E93")); gravity = Gravity.CENTER; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0,0,0,10) })
            addView(TextView(ctx).apply { text = noteText; textSize = 16f; setTextColor(Color.CYAN); gravity = Gravity.CENTER; setPadding(10,0,10,0) })
        }
    }

    private fun spawnPersistentWidget() {
        if (persistentWidget != null) {
            windowManager?.removeView(persistentWidget)
            persistentWidget = null
        }

        val ctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        val activeWidgets = prefs.getStringSet("active_widgets", setOf("clock", "battery"))!!

        val widgetContainer = GridLayout(ctx).apply {
            columnCount = if (activeWidgets.size > 2) 2 else activeWidgets.size
            rowCount = (activeWidgets.size + 1) / 2
            setPadding(10, 10, 10, 10)
        }

        if (activeWidgets.contains("clock")) widgetContainer.addView(createClockWidget(ctx, true))
        if (activeWidgets.contains("calendar")) widgetContainer.addView(createCalendarWidget(ctx, true))
        if (activeWidgets.contains("battery")) widgetContainer.addView(createBatteryWidget(ctx, true))
        if (activeWidgets.contains("system")) widgetContainer.addView(createSystemWidget(ctx, true))
        if (activeWidgets.contains("custom")) widgetContainer.addView(createCustomNoteWidget(ctx, true))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END 
            x = 50
            y = 50
        }

        var clickCount = 0
        var lastClickTime = 0L

        widgetContainer.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0; private var initialY = 0
            private var initialTouchX = 0f; private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (event.buttonState == MotionEvent.BUTTON_SECONDARY || event.action == MotionEvent.ACTION_BUTTON_PRESS) {
                    removeWidget(); return true
                }
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y; initialTouchX = event.rawX; initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX; val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            params.x = initialX - dx.toInt()
                            params.y = initialY + dy.toInt()
                            windowManager?.updateViewLayout(widgetContainer, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = Math.abs(event.rawX - initialTouchX); val dy = Math.abs(event.rawY - initialTouchY)
                        if (dx < 10 && dy < 10) {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastClickTime < 400) clickCount++ else clickCount = 1
                            lastClickTime = currentTime
                            if (clickCount >= 3) removeWidget()
                        }
                        return true
                    }
                }
                return false
            }

            private fun removeWidget() {
                try {
                    windowManager?.removeView(persistentWidget)
                    persistentWidget = null
                    Toast.makeText(this@ShortcutService, "Dashboard smazán", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {}
            }
        })

        val handler = Handler(Looper.getMainLooper())
        val updater = object : Runnable {
            override fun run() {
                if (persistentWidget == null) return
                spawnPersistentWidget() 
            }
        }
        handler.postDelayed(updater, 30000)

        persistentWidget = widgetContainer
        windowManager?.addView(widgetContainer, params)
    }

    private fun showSettingsSubmenu() {
        currentState = MenuState.SYS_SETTINGS
        widgetsScroll?.visibility = View.GONE
        changeTextSafely("")
        spotlightInput?.hint = "Hledat v nastavení..."
        resultsContainer?.removeAllViews()

        addMenuItem("◄ Zpět", "Zpět na Spotlight", null, "↩️") { showDefaultMenu() }
        addMenuItem("Wi-Fi", "Připojení k síti", null, "🛜") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); closeSpotlight() }
        addMenuItem("Bluetooth", "Spárovat zařízení", null, "🩵") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); closeSpotlight() }
    }

    private fun showCustomSettings() {
        currentState = MenuState.SPOT_SETTINGS
        widgetsScroll?.visibility = View.GONE
        changeTextSafely("")
        spotlightInput?.hint = "Nastavení Spotlightu..."
        resultsContainer?.removeAllViews()

        addMenuItem("◄ Zpět", "Zpět na Spotlight", null, "↩️") { showDefaultMenu() }
        addMenuItem("Rychlost animací", "Aktuálně: $currentFps FPS", null, "⚡") { currentFps = when (currentFps) { 10 -> 30; 30 -> 60; else -> 10 }; prefs.edit().putInt("fps", currentFps).apply(); showCustomSettings() }
        addMenuItem("Zakázané aplikace (Blacklist)", "Kde se Spotlight neotevře", null, "🚫") { currentState = MenuState.BLACKLIST; changeTextSafely(""); spotlightInput?.hint = "Hledat aplikaci..."; renderBlacklist("") }
    }

    private fun renderBlacklist(query: String) {
        val mainHandler = Handler(Looper.getMainLooper())
        thread {
            val filtered = cachedApps.filter { it.name.lowercase().contains(query) }.take(15)
            mainHandler.post {
                resultsContainer?.removeAllViews()
                addMenuItem("◄ Zpět", "Zpět do nastavení", null, "↩️") { showCustomSettings() }
                for (app in filtered) {
                    val isBlocked = app.packageName in blacklist
                    val iconEmoji = if (isBlocked) "🔴" else "🟢"
                    addMenuItem(app.name, app.packageName, app.icon, iconEmoji) {
                        if (isBlocked) blacklist.remove(app.packageName) else blacklist.add(app.packageName)
                        prefs.edit().putStringSet("blacklist", blacklist).apply()
                        renderBlacklist(query) 
                    }
                }
            }
        }
    }

    private fun addMenuItem(title: String, subtitle: String, imageIcon: Drawable?, emojiIcon: String, action: () -> Unit) {
        val ctx = resultsContainer?.context ?: return
        val itemLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(30, 25, 30, 25)
            isClickable = true
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = 16f }
            setOnClickListener { action() }
        }
        
        if (imageIcon != null) {
            itemLayout.addView(ImageView(ctx).apply { setImageDrawable(imageIcon); layoutParams = LinearLayout.LayoutParams(70, 70).apply { setMargins(0, 0, 30, 0) } })
            if (emojiIcon == "🔴" || emojiIcon == "🟢") itemLayout.addView(TextView(ctx).apply { text = emojiIcon; textSize = 20f; setPadding(0, 0, 20, 0) })
        } else {
            itemLayout.addView(TextView(ctx).apply { text = emojiIcon; textSize = 24f; setPadding(0, 0, 30, 0) })
        }
        
        val textLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        textLayout.addView(TextView(ctx).apply { text = title; setTextColor(Color.WHITE); textSize = 18f })
        if (subtitle.isNotEmpty()) textLayout.addView(TextView(ctx).apply { text = subtitle; setTextColor(Color.parseColor("#A0A0A0")); textSize = 13f })
        
        itemLayout.addView(textLayout)
        resultsContainer?.addView(itemLayout)
    }

    private fun closeSpotlight() {
        if (rootOverlay != null) { windowManager?.removeView(rootOverlay); rootOverlay = null }
    }

    private fun launchFreeform(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                val options = android.app.ActivityOptions.makeBasic()
                try { val method = options.javaClass.getMethod("setLaunchWindowingMode", Int::class.java); method.invoke(options, 5) } catch (e: Exception) {}
                startActivity(intent, options.toBundle())
            }
        } catch (e: Exception) {}
    }
}
