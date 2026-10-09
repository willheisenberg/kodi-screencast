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
    PYTHONPATH=sender python3 -m kodi_screencast status

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

## Plasma-Widget

Startet und beendet die Übertragung per Klick (Plasma 6). Der Sender muss
dafür als Befehl installiert sein:

    pipx install --system-site-packages -e .
    kpackagetool6 -t Plasma/Applet -i plasmoid     # Update: -u statt -i

Danach „Kodi-Screencast" als Miniprogramm zur Kontrollleiste hinzufügen. In
den Einstellungen des Widgets stehen die IP-Adresse von Kodi (leer = im Netz
suchen) und, falls Kodi eine Anmeldung verlangt, Benutzer und Passwort.
Schlägt der Start fehl, zeigt der Tooltip die Meldung des Senders.

## Mac-App

Unter `mac/` liegt eine Menüleisten-App für macOS 14 oder neuer, die dasselbe
Addon auf Kodi benutzt. Sie nimmt Bild und Systemton über macOS selbst auf
(ScreenCaptureKit, VideoToolbox) und braucht keine weiteren Programme. Auf
einem echten Mac ist sie noch nicht ausprobiert.

### Herunterladen

GitHub baut die App bei jeder Änderung. Unter
[Actions > Mac-App](https://github.com/willheisenberg/kodi-screencast/actions/workflows/mac.yml)
den neuesten Lauf öffnen und unten bei „Artifacts" das Paket `KodiScreencast`
laden (dafür muss man bei GitHub angemeldet sein). Darin liegt
`KodiScreencast.zip`; die Datei `selftest.ts` wird nicht gebraucht.

`KodiScreencast.zip` erst auf dem Mac entpacken. Wird sie unter Linux oder
Windows entpackt und der Ordner kopiert, können Ausführungsrecht und Signatur
verloren gehen, und die App startet nicht.

### Einrichten

1. `KodiScreencast.zip` per Doppelklick entpacken und die App in den Ordner
   „Programme" ziehen.
2. Die App öffnen. Sie ist nicht von Apple signiert, macOS blockiert sie
   deshalb zunächst: unter Systemeinstellungen > Datenschutz & Sicherheit
   „Dennoch öffnen" wählen.
3. In der Menüleiste oben rechts erscheint ein Fernseher-Symbol; im Dock
   taucht die App nicht auf. Über das Symbol die Einstellungen öffnen und die
   IP-Adresse von Kodi eintragen, dazu Benutzer und Passwort, falls Kodi eine
   Anmeldung verlangt. Die automatische Suche im Netz gibt es hier nicht.
4. „Übertragung starten" wählen und die Abfragen zu Bildschirmaufnahme und
   lokalem Netz erlauben. Nach der Freigabe der Bildschirmaufnahme die App
   einmal beenden und neu öffnen.

Der Mac muss im selben Netz sein wie Kodi. Liegt der Ton nicht auf dem Bild,
in den Einstellungen die Verzögerung nachstellen.

Nach einem Neustart des Macs ist das Symbol erst wieder da, wenn die App
läuft. Der Schalter „Beim Anmelden starten" in den Einstellungen erledigt
das; nimmt macOS ihn nicht an, die App unter Systemeinstellungen > Allgemein >
Anmeldeobjekte von Hand eintragen.

### Selbst bauen

    cd mac && ./build-app.sh        # auf einem Mac mit Xcode

Das Ergebnis ist `mac/build/KodiScreencast.zip`.

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
