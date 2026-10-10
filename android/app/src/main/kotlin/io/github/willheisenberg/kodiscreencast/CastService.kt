package io.github.willheisenberg.kodiscreencast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Hält die Übertragung am Leben, auch wenn die App nicht zu sehen ist.
 *
 * Android verlangt für Bildschirmaufnahmen einen Vordergrunddienst mit
 * Dauerbenachrichtigung; über sie lässt sich die Übertragung auch beenden.
 */
class CastService : Service() {
    private val main = Handler(Looper.getMainLooper())

    // Auf- und Abbau laufen nacheinander in einem Thread, weil sie auf Kodi warten.
    private val worker = Executors.newSingleThreadExecutor()
    private var session: CastSession? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        worker.shutdown()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> shutDown(State.Idle)
            else -> if (!CastState.isActive) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val settings = Settings.load(this)
        // Muss stehen, bevor Android die Aufnahme herausgibt.
        startForeground(
            NOTIFICATION_ID, notification(settings.host),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (CastState.isActive || stopping) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val grant = intent.getParcelableExtra<Intent>(EXTRA_GRANT)
        CastState.set(State.Starting)
        worker.execute {
            try {
                val projection = grant?.let {
                    getSystemService(MediaProjectionManager::class.java)
                        .getMediaProjection(resultCode, it)
                } ?: throw KodiException("Android hat die Bildschirmaufnahme nicht freigegeben.")
                // Android beendet die Aufnahme z. B. über die Statusleiste oder beim Sperren.
                projection.registerCallback(
                    object : MediaProjection.Callback() {
                        override fun onStop() = shutDown(State.Idle)
                    }, main)
                val session = CastSession(
                    this, settings, projection,
                    onFailure = { message -> main.post { shutDown(State.Failed(message)) } },
                    onDisplaced = {
                        main.post {
                            Toast.makeText(this, R.string.displaced, Toast.LENGTH_LONG).show()
                            shutDown(State.Idle)
                        }
                    },
                )
                this.session = session
                session.start()
                main.post { if (!stopping) CastState.set(State.Running) }
            } catch (e: Exception) {
                main.post { shutDown(State.Failed(e.message ?: e.toString())) }
            }
        }
    }

    private fun shutDown(next: State) {
        if (stopping) return
        stopping = true
        worker.execute {
            session?.stop()
            session = null
            main.post {
                CastState.set(next)
                if (next is State.Failed) {
                    Toast.makeText(this, next.message, Toast.LENGTH_LONG).show()
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun notification(host: String): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.status_running, host))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_short), stop).build())
            .build()
    }

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_GRANT = "grant"
        private const val CHANNEL_ID = "cast"
        private const val NOTIFICATION_ID = 1

        private var instance: CastService? = null

        /** @param grant Androids Freigabe der Bildschirmaufnahme aus dem Systemdialog */
        fun start(context: Context, resultCode: Int, grant: Intent) {
            context.startForegroundService(
                Intent(context, CastService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_GRANT, grant))
        }

        fun stop() {
            instance?.shutDown(State.Idle)
        }
    }
}
