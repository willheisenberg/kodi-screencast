import QtQuick
import org.kde.kirigami as Kirigami
import org.kde.plasma.core as PlasmaCore
import org.kde.plasma.plasma5support as P5Support
import org.kde.plasma.plasmoid

PlasmoidItem {
    id: root

    property bool running: false
    // Zwischen Klick und der nächsten Statusabfrage: der erwartete Zustand.
    property bool switching: false
    property string lastError: ""

    readonly property string command: Plasmoid.configuration.command || "kodi-screencast"
    readonly property string statusCommand: command + " status"
    readonly property string stopCommand: command + " stop"

    function quote(text) {
        return "'" + text.replace(/'/g, "'\\''") + "'"
    }

    function startCommand() {
        const config = Plasmoid.configuration
        let line = ""
        if (config.user)
            line += "KODI_USER=" + quote(config.user) + " KODI_PASSWORD=" + quote(config.password) + " "
        line += command + " start"
        if (config.host)
            line += " --host " + quote(config.host)
        return line
    }

    function toggle() {
        if (switching)
            return
        switching = true
        if (running) {
            shell.run(stopCommand)
        } else {
            lastError = ""
            shell.run(startCommand())
        }
    }

    Plasmoid.icon: running ? "media-playback-stop" : "video-television"
    Plasmoid.status: running ? PlasmaCore.Types.ActiveStatus : PlasmaCore.Types.PassiveStatus
    toolTipMainText: "Kodi-Screencast"
    toolTipSubText: {
        if (lastError)
            return lastError
        if (switching)
            return running ? "Wird beendet …" : "Wird gestartet …"
        return running ? "Überträgt. Klicken zum Beenden." : "Klicken, um den Bildschirm zu übertragen."
    }

    Plasmoid.contextualActions: [
        PlasmaCore.Action {
            text: root.running ? "Übertragung beenden" : "Übertragung starten"
            icon.name: root.running ? "media-playback-stop" : "media-playback-start"
            onTriggered: root.toggle()
        }
    ]

    preferredRepresentation: compactRepresentation
    compactRepresentation: MouseArea {
        hoverEnabled: true
        onClicked: root.toggle()

        Kirigami.Icon {
            anchors.fill: parent
            source: Plasmoid.icon
            active: parent.containsMouse
            opacity: root.switching ? 0.5 : 1
        }
    }
    fullRepresentation: Item {}

    // "start" läuft so lange wie die Übertragung; die Antwort kommt erst,
    // wenn sie endet, und trägt im Fehlerfall die Meldung des Senders.
    P5Support.DataSource {
        id: shell
        engine: "executable"

        function run(line) {
            if (connectedSources.indexOf(line) < 0)
                connectSource(line)
        }

        onNewData: (source, data) => {
            disconnectSource(source)
            if (source === root.statusCommand) {
                const now = data["exit code"] === 0
                if (now !== root.running || !root.switching)
                    root.switching = false
                root.running = now
            } else if (source !== root.stopCommand) {
                root.switching = false
                if (data["exit code"] !== 0)
                    root.lastError = (data.stderr || "Übertragung fehlgeschlagen").trim()
            }
        }
    }

    Timer {
        interval: 1500
        running: true
        repeat: true
        triggeredOnStart: true
        onTriggered: shell.run(root.statusCommand)
    }
}
