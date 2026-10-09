import QtQuick
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import org.kde.plasma.components as PlasmaComponents
import org.kde.plasma.core as PlasmaCore
import org.kde.plasma.plasma5support as P5Support
import org.kde.plasma.plasmoid

PlasmoidItem {
    id: root

    property bool running: false
    // Zwischen Klick und der nächsten Statusabfrage: der erwartete Zustand.
    property bool switching: false
    property string lastError: ""
    // Was Kodi gerade spielt, solange die Rückfrage zum Unterbrechen offen ist.
    property string interrupts: ""

    readonly property string command: Plasmoid.configuration.command || "kodi-screencast"
    readonly property string statusCommand: command + " status"
    readonly property string stopCommand: command + " stop"

    function quote(text) {
        return "'" + text.replace(/'/g, "'\\''") + "'"
    }

    function kodiCommand(action) {
        const config = Plasmoid.configuration
        let line = ""
        if (config.user)
            line += "KODI_USER=" + quote(config.user) + " KODI_PASSWORD=" + quote(config.password) + " "
        line += command + " " + action
        if (config.host)
            line += " --host " + quote(config.host)
        return line
    }

    function start() {
        interrupts = ""
        expanded = false
        shell.run(kodiCommand("start"))
    }

    function cancel() {
        interrupts = ""
        expanded = false
        switching = false
    }

    function toggle() {
        if (switching)
            return
        switching = true
        if (running) {
            shell.run(stopCommand)
        } else {
            // Erst nachsehen, ob auf Kodi etwas läuft, das unterbrochen würde.
            lastError = ""
            shell.run(kodiCommand("playing"))
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
    // Schließt sich das Fenster ohne Antwort, gilt das als Abbruch.
    // Ohne offene Rückfrage hat das Fenster nichts zu zeigen.
    onExpandedChanged: {
        if (!expanded && interrupts)
            cancel()
        else if (expanded && !interrupts)
            expanded = false
    }

    fullRepresentation: ColumnLayout {
        Layout.minimumWidth: Kirigami.Units.gridUnit * 18
        Layout.maximumWidth: Kirigami.Units.gridUnit * 18
        spacing: Kirigami.Units.largeSpacing

        PlasmaComponents.Label {
            Layout.fillWidth: true
            Layout.margins: Kirigami.Units.largeSpacing
            wrapMode: Text.WordWrap
            text: "Auf Kodi läuft gerade „" + root.interrupts + "“. Für die Übertragung unterbrechen? "
                + "Danach läuft es an derselben Stelle weiter."
        }
        RowLayout {
            Layout.alignment: Qt.AlignRight
            Layout.margins: Kirigami.Units.largeSpacing

            PlasmaComponents.Button {
                text: "Abbrechen"
                onClicked: root.cancel()
            }
            PlasmaComponents.Button {
                text: "Unterbrechen"
                icon.name: "media-playback-start"
                onClicked: root.start()
            }
        }
    }

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
            } else if (source === root.kodiCommand("playing")) {
                if (data["exit code"] === 0 && data.stdout.trim()) {
                    root.interrupts = data.stdout.trim()
                    root.expanded = true
                } else {
                    root.start()
                }
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
