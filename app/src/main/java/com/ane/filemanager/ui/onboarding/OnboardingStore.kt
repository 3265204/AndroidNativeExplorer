package com.ane.filemanager.ui.onboarding

import android.content.Context

/** A non-versioned completion bit intentionally survives ordinary app updates. */
internal class OnboardingStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isCompleted(): Boolean = preferences.getBoolean(KEY_COMPLETED, false)

    fun markCompleted() {
        // This is the only write in the flow; persist it before dismissing so a fast exit
        // immediately after the tutorial cannot cause it to reappear.
        preferences.edit().putBoolean(KEY_COMPLETED, true).commit()
    }

    private companion object {
        const val PREFERENCES = "onboarding"
        const val KEY_COMPLETED = "interaction_tutorial_completed"
    }
}
