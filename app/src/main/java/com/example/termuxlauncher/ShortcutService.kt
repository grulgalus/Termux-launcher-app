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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.ContextThemeWrapper
import kotlin.concurrent.thread

class ShortcutService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var rootOverlay: FrameLayout? = null
    private var cachedApps: List<AppItem> = emptyList()

    data class AppItem(val name: String, val packageName: String)

    override fun onServiceConnected() {
        updateAppCache()
    }

    private fun updateAppCache() {
        thread {
            val apps = packageManager.getInstalledApplications(0)
            cachedApps = apps.filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
                .map { AppItem(it.loadLabel(packageManager).toString(), it.packageName) }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ENTER && event.isShiftPressed && event.action == KeyEvent.ACTION_DOWN) {
            toggleSpotlight()
            return true
        }
        return super.onKeyEvent(event)
    }

    private fun toggleSpotlight() {
        if (rootOverlay != null) { closeSpotlight(); return }

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
            }
            setPadding(40, 40, 40, 40)
            isClickable = true
        }

        val input = EditText(ctx).apply {
            hint = "Vyhledat aplikace..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 20f
            background = null
        }
        menuCard.addView(input)

        val actionsContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        
        // Pevné zkratky
        addShortcut(actionsContainer, "⚙️ Nastavení systému") { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        addShortcut(actionsContainer, "📦 Instalované aplikace") { /* Zde můžeš přidat logiku */ }

        menuCard.addView(ScrollView(ctx).apply { addView(actionsContainer) })

        rootOverlay?.addView(menuCard, FrameLayout.LayoutParams(1000, 800).apply { gravity = Gravity.CENTER })
        windowManager?.addView(rootOverlay, WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT))
    }

    private fun addShortcut(container: LinearLayout, title: String, action: () -> Unit) {
        val tv = TextView(this).apply {
            text = title
            setTextColor(Color.LTGRAY)
            setPadding(20, 20, 20, 20)
            setOnClickListener { action(); closeSpotlight() }
        }
        container.addView(tv)
    }

    private fun closeSpotlight() {
        rootOverlay?.let { windowManager?.removeView(it) }
        rootOverlay = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
