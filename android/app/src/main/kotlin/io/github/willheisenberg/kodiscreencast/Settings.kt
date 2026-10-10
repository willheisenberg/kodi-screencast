package io.github.willheisenberg.kodiscreencast

import android.content.Context

data class Settings(
    val host: String,
    val user: String,
    val password: String,
    val audio: Boolean,
    val audioDelayMs: Int,
) {
    fun save(context: Context) {
        preferences(context).edit()
            .putString(HOST, host)
            .putString(USER, user)
            .putString(PASSWORD, password)
            .putBoolean(AUDIO, audio)
            .putInt(AUDIO_DELAY, audioDelayMs)
            .apply()
    }

    companion object {
        const val DEFAULT_AUDIO_DELAY_MS = 350

        private const val HOST = "host"
        private const val USER = "user"
        private const val PASSWORD = "password"
        private const val AUDIO = "audio"
        private const val AUDIO_DELAY = "audioDelayMs"

        private fun preferences(context: Context) =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)

        fun load(context: Context): Settings {
            val preferences = preferences(context)
            return Settings(
                host = preferences.getString(HOST, "")!!.trim(),
                user = preferences.getString(USER, "")!!,
                password = preferences.getString(PASSWORD, "")!!,
                audio = preferences.getBoolean(AUDIO, true),
                audioDelayMs = preferences.getInt(AUDIO_DELAY, DEFAULT_AUDIO_DELAY_MS),
            )
        }
    }
}
