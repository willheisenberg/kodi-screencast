package io.github.willheisenberg.kodiscreencast

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import kotlin.concurrent.thread

/**
 * Führt durch den Start: Berechtigungen, Rückfrage bei laufender Wiedergabe,
 * Androids Dialog zur Bildschirmaufnahme. Selbst unsichtbar, damit der Start
 * über die Kachel nicht erst die App öffnet.
 */
class StartActivity : Activity() {
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings.load(this)
        if (CastState.isActive) {
            finish()
        } else if (settings.host.isEmpty()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else {
            val missing = wantedPermissions().filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) checkKodi() else requestPermissions(missing.toTypedArray(), 0)
        }
    }

    private fun wantedPermissions() = buildList {
        if (settings.audio) add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Abgelehnt heißt nur: ohne Ton bzw. ohne sichtbare Benachrichtigung.
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) = checkKodi()

    private fun checkKodi() {
        thread(name = "screencast.check") {
            // Läuft auf Kodi schon etwas, erst nachfragen.
            val result = try {
                val kodi = KodiClient(settings.host, settings.user, settings.password)
                kodi.requireAddon()
                Result.success(kodi.otherPlayback(CastSession.VIDEO_PORT))
            } catch (e: KodiException) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { title -> if (title == null) askForCapture() else confirmInterrupting(title) },
                    onFailure = { showError(it.message ?: it.toString()) },
                )
            }
        }
    }

    private fun confirmInterrupting(title: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.interrupt_title, title))
            .setMessage(R.string.interrupt_message)
            .setPositiveButton(R.string.interrupt_confirm) { _, _ -> askForCapture() }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun askForCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == RESULT_OK && data != null) {
            CastService.start(this, resultCode, data)
        }
        finish()
    }
}
