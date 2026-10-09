import QtQuick
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import org.kde.kcmutils as KCM

KCM.SimpleKCM {
    property alias cfg_host: host.text
    property alias cfg_user: user.text
    property alias cfg_password: password.text
    property alias cfg_command: command.text

    Kirigami.FormLayout {
        QQC2.TextField {
            id: host
            Kirigami.FormData.label: "IP-Adresse von Kodi:"
            placeholderText: "leer = im Netz suchen"
        }
        QQC2.TextField {
            id: user
            Kirigami.FormData.label: "Benutzer:"
            placeholderText: "nur falls Kodi eine Anmeldung verlangt"
        }
        Kirigami.PasswordField {
            id: password
            Kirigami.FormData.label: "Passwort:"
        }
        QQC2.TextField {
            id: command
            Kirigami.FormData.label: "Befehl:"
        }
    }
}
