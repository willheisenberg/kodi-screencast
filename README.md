# kodi-screencast

Spiegelt den Bildschirm eines Linux-Rechners (KDE/Wayland) mit einem Befehl
auf Kodi, ohne dass Kodi beendet wird. Stufe 1: Kodi spielt den Stream mit
seinem eigenen Player ab.

Entwurf: [docs/superpowers/specs/2026-10-09-kodi-screencast-design.md](docs/superpowers/specs/2026-10-09-kodi-screencast-design.md)

## Voraussetzungen

Rechner (Arch):

    sudo pacman -S gst-plugin-pipewire gst-plugin-va gst-plugins-bad gst-plugins-good python-gobject avahi

Kodi (getestet mit LibreELEC 12.2.1 auf einem Pi 5):

- Addon `inputstream.ffmpegdirect` installiert
- Einstellungen > Dienste > Steuerung: Fernsteuerung über HTTP erlaubt
- Einstellungen > Dienste > Allgemein: Zeroconf an (nur für die automatische Suche)

## Addon auf Kodi installieren

    scp -r plugin.video.screencast root@<KODI_IP>:/storage/.kodi/addons/
    ssh root@<KODI_IP> 'kodi-send --action="UpdateLocalAddons"'

Danach das Addon in Kodi unter Addons > Benutzer-Addons > Video-Addons
einmal aktivieren.

## Benutzen

    PYTHONPATH=sender python3 -m kodi_screencast start
    PYTHONPATH=sender python3 -m kodi_screencast stop

Verlangt Kodi für die Fernsteuerung eine Anmeldung, die Zugangsdaten über
`KODI_USER` und `KODI_PASSWORD` oder `--user`/`--password` mitgeben.

Beim ersten Start fragt KDE, welcher Bildschirm freigegeben wird. Die Auswahl
wird gemerkt. `start --help` zeigt die Optionen (Zieladresse, Bitrate,
Bildhöhe, ohne Ton).

## Tests

    python3 -m pytest

## Latenz

Noch nicht gemessen.
