package io.github.willheisenberg.kodiscreencast

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Die Kachel in den Schnelleinstellungen: ein Tipp startet, der nächste beendet. */
class CastTileService : TileService() {
    private val refresh = { refresh() }

    override fun onStartListening() {
        CastState.listeners += refresh
        refresh()
    }

    override fun onStopListening() {
        CastState.listeners -= refresh
    }

    override fun onClick() {
        if (CastState.isActive) {
            CastService.stop()
        } else if (isLocked) {
            unlockAndRun { openStart() }
        } else {
            openStart()
        }
    }

    private fun openStart() {
        val intent = Intent(this, StartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val state = CastState.state
        tile.state = if (CastState.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when (state) {
            State.Idle -> null
            State.Starting -> getString(R.string.status_starting)
            State.Running -> getString(R.string.tile_running)
            is State.Failed -> getString(R.string.tile_failed)
        }
        tile.updateTile()
    }
}
