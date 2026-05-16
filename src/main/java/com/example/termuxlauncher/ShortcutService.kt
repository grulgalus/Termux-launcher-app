package com.example.termuxlauncher

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.content.Intent
import android.content.ComponentName
import android.util.Log

class ShortcutService : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_T && (event.isAltPressed || event.isMetaPressed || event.isCtrlPressed)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                launchTermux()
            }
            return true 
        }
        return super.onKeyEvent(event)
    }

    private fun launchTermux() {
        Log.d("TermuxLauncher", "Pokus o spuštění...")
        
        try {
            // Metoda 1: Oficiální hrubá síla
            val intent = Intent().apply {
                component = ComponentName("com.termux", "com.termux.app.TermuxActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
            Log.d("TermuxLauncher", "Metoda 1 prošla")
        } catch (e: Exception) {
            Log.e("TermuxLauncher", "Metoda 1 ZABLOKOVÁNA: ${e.message}")
            
            // Metoda 2: ZADNÍ VRÁTKA (Příkazový řádek Androidu)
            try {
                Runtime.getRuntime().exec("am start -n com.termux/com.termux.app.TermuxActivity")
                Log.d("TermuxLauncher", "Metoda 2 (AM START) odeslána!")
            } catch (e2: Exception) {
                Log.e("TermuxLauncher", "Metoda 2 selhala: ${e2.message}")
            }
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent) {}
    override fun onInterrupt() {}
}
