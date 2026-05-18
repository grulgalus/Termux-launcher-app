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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import android.view.ContextThemeWrapper
import kotlin.concurrent.thread

class ShortcutService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var rootOverlay: FrameLayout? = null
    private var persistentWidget: View? = null // Plovoucí widget
    private var cachedApps: List<AppItem> = emptyList()

    private lateinit var prefs: SharedPreferences
    private var currentFps = 10
    private var blacklist = mutableSetOf<String>()
    private var activePackage = "" 

    private enum class MenuState { MAIN, SYS_SETTINGS, SPOT_SETTINGS, BLACKLIST }
    private var currentState = MenuState.MAIN
    private var isProgrammaticTextChange = false 

    private var currentResults = listOf<SearchResult>()
    private var spotlightInput: EditText? = null
    private var resultsContainer: LinearLayout? = null
    private var widgetsContainer: LinearLayout? = null

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

        Toast.makeText(this, "MAC Spotlight: Plovoucí Widgety aktivovány!", Toast.LENGTH_SHORT).show()
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
                    val icon = app.loadIcon(packageManager)
                    temp.add(AppItem(appName, app.packageName, icon))
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
                
                // OPRAVA USEKNUTÍ: Už žádný negativní topMargin. Jen pěkný vyvážený padding shora i zespoda
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

            widgetsContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                setPadding(0, 0, 0, 40)
            }

            val clockWidget = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER 
                background = GradientDrawable().apply {
                    cornerRadius = 32f
                    setColor(Color.parseColor("#26FFFFFF"))
                }
                layoutParams = LinearLayout.LayoutParams(0, 250, 1f).apply { setMargins(0, 0, 20, 0) }
            }
            val timeText = TextClock(ctx).apply {
                format12Hour = "HH:mm"
                format24Hour = "HH:mm"
                textSize = 34f 
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER 
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val dateText = TextClock(ctx).apply {
                format12Hour = "EEEE, dd. MM."
                format24Hour = "EEEE, dd. MM."
                textSize = 14f
                gravity = Gravity.CENTER 
                setTextColor(Color.parseColor("#A0A0A0"))
                setPadding(0, 10, 0, 0)
            }
            clockWidget.addView(timeText)
            clockWidget.addView(dateText)

            val batteryWidget = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = 32f
                    setColor(Color.parseColor("#26FFFFFF"))
                }
                layoutParams = LinearLayout.LayoutParams(0, 250, 1f).apply { setMargins(20, 0, 0, 0) }
            }
            
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val batLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            
            val batIcon = TextView(ctx).apply {
                text = if (batLevel > 20) "🔋 $batLevel %" else "🪫 $batLevel %"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(if (batLevel > 20) Color.parseColor("#34C759") else Color.parseColor("#FF3B30"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 10)
            }
            val devInfo = TextView(ctx).apply {
                text = "Android ${Build.VERSION.RELEASE}"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#A0A0A0"))
            }
            batteryWidget.addView(batIcon)
            batteryWidget.addView(devInfo)

            widgetsContainer?.addView(clockWidget)
            widgetsContainer?.addView(batteryWidget)
            menuCard.addView(widgetsContainer)

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
                text = "🔍 "
                textSize = 22f
                setTextColor(Color.parseColor("#8E8E93"))
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
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                    setMargins(0, 40, 0, 20)
                }
                setBackgroundColor(Color.parseColor("#33FFFFFF"))
            }
            menuCard.addView(divider)

            resultsContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val scroll = ScrollView(ctx).apply { addView(resultsContainer) }
            menuCard.addView(scroll)

            // Tady zmizel ten zlý topMargin=-50 !
            val cardParams = FrameLayout.LayoutParams(1250, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
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
                    if (isProgrammaticTextChange) return

                    val query = s.toString().lowercase().trim()
                    lastSearchRunnable?.let { mainHandler.removeCallbacks(it) }

                    widgetsContainer?.visibility = if (query.isEmpty() && currentState == MenuState.MAIN) View.VISIBLE else View.GONE

                    val delayMs = 1000L / currentFps

                    lastSearchRunnable = Runnable {
                        if (currentState == MenuState.BLACKLIST) {
                            renderBlacklist(query)
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
        widgetsContainer?.visibility = View.VISIBLE
        currentResults = emptyList()
        resultsContainer?.removeAllViews()
        
        // PŘIDÁNA NOVÁ FUNKCE PRO VYTÁHNUTÍ WIDGETU NA PLOCHU
        addMenuItem("Připnout widgety na plochu", "Hodiny a baterie zůstanou trvale zobrazené", null, "📌") { 
            spawnPersistentWidget()
            closeSpotlight()
        }
        addMenuItem("Systémové předvolby", "Wi-Fi, Bluetooth, Displej...", null, "⚙️") { showSettingsSubmenu() }
        addMenuItem("Spotlight Nastavení", "Rychlost (FPS) a Zakázané aplikace", null, "🛠️") { showCustomSettings() }
        addMenuItem("App Store", "Nainstalovat nové aplikace", null, "🛍️") {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent); closeSpotlight()
            } catch (e: Exception) {}
        }
    }

    // ----- NOVÁ FUNKCE: TRVALÝ WIDGET NA PLOCHU -----
    private fun spawnPersistentWidget() {
        if (persistentWidget != null) {
            windowManager?.removeView(persistentWidget)
            persistentWidget = null
        }

        val ctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        val widgetCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(40, 30, 40, 30)
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E61C1C1E"))
                cornerRadius = 40f
                setStroke(2, Color.parseColor("#4DFFFFFF"))
            }
            elevation = 30f
        }

        val timeView = TextClock(ctx).apply {
            format12Hour = "HH:mm"
            format24Hour = "HH:mm"
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 40, 0)
        }

        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val initialLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val battView = TextView(ctx).apply {
            text = "🔋 $initialLevel %"
            textSize = 20f
            setTextColor(if (initialLevel > 20) Color.parseColor("#34C759") else Color.parseColor("#FF3B30"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        widgetCard.addView(timeView)
        widgetCard.addView(battView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100 // Počáteční pozice
            y = 100
        }

        // TAŽENÍ MYŠÍ (DRAG AND DROP) PRO WIDGET!
        widgetCard.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(widgetCard, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        // Pokud jsi widgetem nepohnul o víc jak pár pixelů = bylo to kliknutí pro smazání
                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)
                        if (diffX < 10 && diffY < 10) {
                            windowManager?.removeView(persistentWidget)
                            persistentWidget = null
                            Toast.makeText(this@ShortcutService, "Widget odstraněn z plochy", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                }
                return false
            }
        })

        // Tikátko baterie pro widget na ploše (Čas se aktualizuje sám v TextClock)
        val handler = Handler(Looper.getMainLooper())
        val battUpdater = object : Runnable {
            override fun run() {
                if (persistentWidget == null) return
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                battView.text = "🔋 $level %"
                battView.setTextColor(if (level > 20) Color.parseColor("#34C759") else Color.parseColor("#FF3B30"))
                handler.postDelayed(this, 15000) // Každých 15s kontrola baterky
            }
        }
        handler.post(battUpdater)

        persistentWidget = widgetCard
        windowManager?.addView(widgetCard, params)
        Toast.makeText(this, "Widget přidán! Tažením myši jej přesuneš, KLIKNUTÍM JEJ SMAŽEŠ.", Toast.LENGTH_LONG).show()
    }
    // ----- KONEC NOVÉ FUNKCE -----

    private fun showSettingsSubmenu() {
        currentState = MenuState.SYS_SETTINGS
        widgetsContainer?.visibility = View.GONE
        changeTextSafely("")
        spotlightInput?.hint = "Hledat v nastavení..."
        resultsContainer?.removeAllViews()

        addMenuItem("◄ Zpět", "Zpět na Spotlight", null, "↩️") { showDefaultMenu() }
        addMenuItem("Wi-Fi", "Připojení k síti", null, "🛜") {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); closeSpotlight()
        }
        addMenuItem("Bluetooth", "Spárovat zařízení", null, "🩵") {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); closeSpotlight()
        }
        addMenuItem("Všechna nastavení", "Hlavní panel", null, "⚙️") {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); closeSpotlight()
        }
    }

    private fun showCustomSettings() {
        currentState = MenuState.SPOT_SETTINGS
        widgetsContainer?.visibility = View.GONE
        changeTextSafely("")
        spotlightInput?.hint = "Nastavení Spotlightu..."
        resultsContainer?.removeAllViews()

        addMenuItem("◄ Zpět", "Zpět na Spotlight", null, "↩️") { showDefaultMenu() }
        addMenuItem("Rychlost animací", "Aktuálně: $currentFps FPS", null, "⚡") {
            currentFps = when (currentFps) { 10 -> 30; 30 -> 60; else -> 10 }
            prefs.edit().putInt("fps", currentFps).apply()
            showCustomSettings()
        }
        addMenuItem("Zakázané aplikace (Blacklist)", "Kde se Spotlight neotevře", null, "🚫") {
            currentState = MenuState.BLACKLIST
            changeTextSafely("")
            spotlightInput?.hint = "Hledat aplikaci..."
            renderBlacklist("")
        }
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
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 16f
            }
            setOnClickListener { action() }
        }
        
        if (imageIcon != null) {
            val imageView = ImageView(ctx).apply {
                setImageDrawable(imageIcon)
                layoutParams = LinearLayout.LayoutParams(70, 70).apply { setMargins(0, 0, 30, 0) }
            }
            itemLayout.addView(imageView)
            
            if (emojiIcon == "🔴" || emojiIcon == "🟢") {
                 val statusIcon = TextView(ctx).apply {
                    text = emojiIcon
                    textSize = 20f
                    setPadding(0, 0, 20, 0)
                }
                itemLayout.addView(statusIcon)
            }
        } else {
            val iconView = TextView(ctx).apply {
                text = emojiIcon
                textSize = 24f
                setPadding(0, 0, 30, 0)
            }
            itemLayout.addView(iconView)
        }
        
        val textLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val titleView = TextView(ctx).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 18f
        }
        textLayout.addView(titleView)
        
        if (subtitle.isNotEmpty()) {
            val subView = TextView(ctx).apply {
                text = subtitle
                setTextColor(Color.parseColor("#A0A0A0"))
                textSize = 13f
            }
            textLayout.addView(subView)
        }
        
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
