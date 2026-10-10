package io.github.willheisenberg.kodiscreencast

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

/** Einstellungen, Stand der Übertragung und ein Knopf zum Starten und Beenden. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var host: EditText
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var audio: Switch
    private lateinit var audioDelay: EditText

    private val render = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        host = findViewById(R.id.host)
        user = findViewById(R.id.user)
        password = findViewById(R.id.password)
        audio = findViewById(R.id.audio)
        audioDelay = findViewById(R.id.audio_delay)

        if (savedInstanceState == null) {
            val settings = Settings.load(this)
            host.setText(settings.host)
            user.setText(settings.user)
            password.setText(settings.password)
            audio.isChecked = settings.audio
            audioDelay.setText(settings.audioDelayMs.toString())
        }

        toggle.setOnClickListener {
            if (CastState.isActive) {
                CastService.stop()
            } else {
                save()
                if (host.text.isBlank()) {
                    host.requestFocus()
                    render()
                } else {
                    startActivity(Intent(this, StartActivity::class.java))
                }
            }
        }

        val addTile = findViewById<Button>(R.id.add_tile)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addTile.setOnClickListener {
                getSystemService(StatusBarManager::class.java).requestAddTileService(
                    ComponentName(this, CastTileService::class.java),
                    getString(R.string.tile_label),
                    Icon.createWithResource(this, R.drawable.ic_tile),
                    mainExecutor,
                ) {}
            }
        } else {
            // Ältere Geräte: die Kachel über „Bearbeiten“ in den Schnelleinstellungen hinzufügen.
            addTile.visibility = View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        CastState.listeners += render
    }

    // Erst hier stehen auch wiederhergestellte Eingaben in den Feldern.
    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onStop() {
        CastState.listeners -= render
        super.onStop()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun save() {
        Settings(
            host = host.text.toString().trim(),
            user = user.text.toString(),
            password = password.text.toString(),
            audio = audio.isChecked,
            audioDelayMs = audioDelay.text.toString().toIntOrNull()
                ?: Settings.DEFAULT_AUDIO_DELAY_MS,
        ).save(this)
    }

    private fun render() {
        val state = CastState.state
        status.text = when (state) {
            State.Idle -> getString(
                if (host.text.isBlank()) R.string.status_no_host else R.string.status_idle)
            State.Starting -> getString(R.string.status_starting)
            State.Running -> getString(R.string.status_running, Settings.load(this).host)
            is State.Failed -> state.message
        }
        toggle.setText(if (CastState.isActive) R.string.stop else R.string.start)
    }
}
