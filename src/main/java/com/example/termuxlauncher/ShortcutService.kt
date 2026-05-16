package com.example.termuxlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import android.util.Log

class ShortcutService : AccessibilityService() {

    override fun onServiceConnected() {
        Toast.makeText(this, "Termux Služba běží!", Toast.LENGTH_SHORT).show()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Logujeme každý stisk do paměti (logcatu)
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.d("TermuxLauncher", "Zmáčknuto: ${event.keyCode} | Alt: ${event.isAltPressed} | Ctrl: ${event.isCtrlPressed}")
        }

        val isAlt = event.isAltPressed || event.isMetaPressed

        // Záchranný test: Alt + Q
        if (event.keyCode == KeyEvent.KEYCODE_Q && isAlt) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Toast.makeText(this, "DIAGNOSTIKA FUNGUJE!", Toast.LENGTH_SHORT).show()
            }
            return true
        }

        // Alt + Mezerník na otestování Freeform okna pro Termux
        if (event.keyCode == KeyEvent.KEYCODE_SPACE && isAlt) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                launchFreeform("com.termux")
            }
            return true
        }

        return super.onKeyEvent(event)
    }

    private fun launchFreeform(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

            val options = android.app.ActivityOptions.makeBasic()
            try {
                // Skryté API na okénka
                val method = options.javaClass.getMethod("setLaunchWindowingMode", Int::class.java)
                method.invoke(options, 5) // 5 = Freeform
            } catch (e: Exception) {}

            startActivity(intent, options.toBundle())
        } catch (e: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
