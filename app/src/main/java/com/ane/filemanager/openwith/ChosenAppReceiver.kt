package com.ane.filemanager.openwith

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

class ChosenAppReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_ASSOCIATION_KEY) ?: return
        val component = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        } ?: return
        OpenWithStore.put(context, key, component)
    }

    companion object {
        const val EXTRA_ASSOCIATION_KEY = "com.ane.filemanager.openwith.ASSOCIATION_KEY"
    }
}
