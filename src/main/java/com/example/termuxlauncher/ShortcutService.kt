package com.example.termuxlauncher

import android.inputmethodservice.InputMethodService
import android.content.Intent
import android.view.KeyEvent
import android.util.Log

class ShortcutService : InputMethodService() {
    
    // Tato metoda se zavolá při KAŽDÉM stisku jakékoliv klávesy
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Log.d("TermuxLauncherIME", "Stisknuto: $keyCode, Alt: ${event.isAltPressed}, Meta: ${event.isMetaPressed}, Ctrl: ${event.isCtrlPressed}")

        if (keyCode == KeyEvent.KEYCODE_T && (event.isAltPressed || event.isMetaPressed || event.isCtrlPressed)) {
            launchTermux()
            return true // Zabrání napsání písmene "t" na obrazovku
        }
        
        // Všechny ostatní klávesy pošleme dál, aby se dalo normálně psát
        return super.onKeyDown(keyCode, event)
    }

    private fun launchTermux() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.termux")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(intent)
                Log.d("TermuxLauncherIME", "Termux spuštěn přes klávesnici!")
            }
        } catch (e: Exception) {
            Log.e("TermuxLauncherIME", "Chyba: ${e.message}")
        }
    }
}
