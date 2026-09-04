package com.localmind.chat.data.repo

import android.content.Context

/**
 * Plain SharedPreferences, deliberately not the encrypted store.
 *
 * These are settings, not content — knowing that a phone keeps chats for 30 days
 * reveals nothing about the chats. Keeping them out of the SQLCipher database also
 * means the sweeper can read its own configuration without opening the encrypted file.
 */
class RetentionSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var days: Int
        get() = prefs.getInt(KEY_DAYS, RetentionPolicy.DEFAULT_DAYS)
        set(value) = prefs.edit().putInt(KEY_DAYS, value).apply()

    var lastVacuumAt: Long
        get() = prefs.getLong(KEY_LAST_VACUUM, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_VACUUM, value).apply()

    /**
     * Set once the user has actually been told that old chats get deleted.
     * Auto-deleting a user's data without ever having said so is the kind of surprise
     * that costs you their trust permanently — gate the sweep on this in onboarding.
     */
    var disclosureShown: Boolean
        get() = prefs.getBoolean(KEY_DISCLOSED, false)
        set(value) = prefs.edit().putBoolean(KEY_DISCLOSED, value).apply()

    private companion object {
        const val PREFS = "localmind_prefs"
        const val KEY_DAYS = "retention_days"
        const val KEY_LAST_VACUUM = "last_vacuum_at"
        const val KEY_DISCLOSED = "retention_disclosed"
    }
}
