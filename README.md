# kodi-screencast

Spiegelt den Bildschirm eines Linux-Rechners (KDE/Wayland) mit einem Befehl
auf Kodi, ohne dass Kodi beendet wird. Das Bild spielt Kodi mit seinem
eigenen Player ab, der Ton läuft daran vorbei direkt auf die Soundkarte.

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
einmal aktivieren. Nach einem Update das Addon dort aus- und wieder
einschalten (oder Kodi neu starten), damit der Ton-Dienst neu lädt.

## Benutzen

    PYTHONPATH=sender python3 -m kodi_screencast start
    PYTHONPATH=sender python3 -m kodi_screencast stop

Verlangt Kodi für die Fernsteuerung eine Anmeldung, die Zugangsdaten über
`KODI_USER` und `KODI_PASSWORD` oder `--user`/`--password` mitgeben.

Beim ersten Start fragt KDE, welcher Bildschirm freigegeben wird. Die Auswahl
wird gemerkt. `start --help` zeigt die Optionen (Zieladresse, Bitrate,
Bildhöhe, ohne Ton).

Kurz nach dem Start steht das Bild einmal für etwa zwei Sekunden; damit
wird Kodis Start-Rückstand abgebaut (siehe Latenz).

Läuft der Ton dem Bild voraus oder hinterher, mit `--audio-delay` (in ms,
Standard 350) nachstellen. Kodis Lautstärke wirkt nicht auf die Übertragung,
die des Fernsehers schon. Solange übertragen wird, gibt Kodi selbst keinen
Ton aus.

## Tests

    python3 -m pytest

## Latenz

Noch nicht am Fernseher gemessen. Aus Kodis Debug-Log und den Puffern
gerechnet: Bild etwa 0,3 s, Ton etwa 0,1 s plus die eingestellte Verzögerung.

Kodis Player allein kommt mit Ton nicht unter rund 2 s:

- Er startet das Bild 1,2 s hinter dem ersten Tonpaket, um den Tonpuffer zu
  füllen.
- Bei Live-Streams mit Ton spielt er 5 % langsamer, sobald der Tonpuffer
  knapp wird, und füllt ihn so wieder auf.
- Was während des Starts ankommt (Stream-Analyse rund 0,9 s), bleibt als
  Rückstand im Puffer.

Deshalb bekommt Kodi nur das Bild. Den Start-Rückstand baut der Sender mit
einer Sendepause von 2 s ab: Kodis Puffer läuft leer, und den Zeitsprung
danach rechnet Kodi heraus. Mit Tonspur im Strom hilft das kaum (etwa 0,4 s),
weil Kodi den Puffer dann wieder auffüllt.

Der Ton geht als rohes PCM per UDP an `resources/lib/receiver.py`, das ihn
über `aplay` ausgibt. Der Dienst des Addons startet den Empfänger, sobald ein
Screencast mit Ton läuft, und nimmt Kodi für die Dauer das Tongerät weg.
