package com.example.termuxlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class ShortcutService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Logování pro ladění (uvidíš v logcatu, jestli klávesa prošla)
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.d("TermuxLauncher", "Stisknuta klávesa: ${event.keyCode}, Alt: ${event.isAltPressed}, Meta: ${event.isMetaPressed}")
            
            if (event.keyCode == KeyEvent.KEYCODE_T && (event.isAltPressed || event.isMetaPressed)) {
                launchTermux()
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    private fun launchTermux() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.termux")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(intent)
                Log.d("TermuxLauncher", "Termux spuštěn")
            } else {
                Log.e("TermuxLauncher", "Balíček com.termux nenalezen")
            }
        } catch (e: Exception) {
            Log.e("TermuxLauncher", "Chyba při spouštění: ${e.message}")
        }
    }
}
