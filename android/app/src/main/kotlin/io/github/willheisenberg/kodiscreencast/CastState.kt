package io.github.willheisenberg.kodiscreencast

import java.util.concurrent.CopyOnWriteArraySet

sealed interface State {
    data object Idle : State
    data object Starting : State
    data object Running : State
    data class Failed(val message: String) : State
}

/** Stand der Übertragung für Kachel und Oberfläche; wird nur im Hauptthread geändert. */
object CastState {
    var state: State = State.Idle
        private set

    val listeners = CopyOnWriteArraySet<() -> Unit>()

    val isActive get() = state == State.Starting || state == State.Running

    fun set(next: State) {
        state = next
        listeners.forEach { it() }
    }
}
