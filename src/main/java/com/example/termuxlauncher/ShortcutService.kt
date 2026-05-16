package com.example.termuxlauncher

import android.inputmethodservice.InputMethodService
import android.content.Intent
import android.view.KeyEvent
import android.util.Log

class ShortcutService : InputMethodService() {
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Logování kláves pro jistotu
        Log.d("TermuxLauncherIME", "Klavesa: $keyCode, Meta: ${event.isMetaPressed}")

        if (keyCode == KeyEvent.KEYCODE_T && (event.isAltPressed || event.isMetaPressed || event.isCtrlPressed)) {
            launchTermux()
            return true 
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun launchTermux() {
        try {
            // Tady je ta magie! Místo hledání ho rovnou střílíme na cíl:
            val intent = Intent()
            intent.setClassName("com.termux", "com.termux.app.TermuxActivity")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            
            startActivity(intent)
            Log.d("TermuxLauncherIME", "Termux spuštěn HRUBOU SILOU!")
        } catch (e: Exception) {
            Log.e("TermuxLauncherIME", "Chyba: ${e.message}")
        }
    }
}
