package com.example.termuxlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.view.ContextThemeWrapper

class ShortcutService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var spotlightView: View? = null

    override fun onServiceConnected() {
        Toast.makeText(this, "Termux Spotlight připraven!", Toast.LENGTH_SHORT).show()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Kontrolujeme, jestli je zmáčknutý SHIFT
        val isShift = event.isShiftPressed

        // Zavření okna pomocí klávesy ESC
        if (spotlightView != null && event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_DOWN) closeSpotlight()
            return true
        }

        // ZMĚNĚNO NA: Shift + Mezerník
        if (event.keyCode == KeyEvent.KEYCODE_SPACE && isShift) {
            if (event.action == KeyEvent.ACTION_DOWN) toggleSpotlight()
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
                    setColor(Color.parseColor("#E6202020"))
                }
                setPadding(50, 50, 50, 50)
            }

            val input = EditText(ctx).apply {
                hint = "Napiš com.termux a dej Enter..."
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                textSize = 22f
                isSingleLine = true
                background = null
                
                setOnKeyListener { _, keyCode, keyEvent ->
                    if (keyEvent.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                        val text = text.toString().trim()
                        val appToLaunch = if (text.isNotEmpty()) text else "com.termux"
                        launchFreeform(appToLaunch)
                        closeSpotlight()
                        return@setOnKeyListener true
                    }
                    false
                }
            }
            layout.addView(input)

            val params = WindowManager.LayoutParams(
                900, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 250
            }

            windowManager?.addView(layout, params)
            spotlightView = layout
            input.requestFocus()
        } catch (e: Exception) {
            Toast.makeText(this, "Chyba okna: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
                Toast.makeText(this, "Aplikace $packageName nenalezena!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Chyba spuštění", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
