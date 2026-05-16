package com.example.termuxlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class ShortcutService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Zde nepotřebujeme nic řešit
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Kontrola stisku klávesy 'T'
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_T) {
            // Option klávesa se na Androidu hlásí jako Alt nebo Meta
            if (event.isAltPressed || event.isMetaPressed) {
                launchTermux()
                return true // Zabrání předání klávesy dalším aplikacím
            }
        }
        return super.onKeyEvent(event)
    }

    private fun launchTermux() {
        val intent = packageManager.getLaunchIntentForPackage("com.termux")
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }
}
