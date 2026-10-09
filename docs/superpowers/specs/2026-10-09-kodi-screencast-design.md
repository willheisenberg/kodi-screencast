# kodi-screencast – Entwurf Stufe 1

Stand: 2026-10-09

## Ziel

Den Bildschirm eines Linux-Rechners (KDE/Wayland) mit einem Befehl auf Kodi
(LibreELEC, Raspberry Pi 5) anzeigen, ähnlich wie bei Chromecast. Kodi läuft
dabei weiter; es wird nichts beendet oder neu gestartet.

Anwendungsfall ist Zeigen und Zuschauen, nicht Arbeiten am Fernseher.
Zielwert für die Verzögerung: unter einer Sekunde. Stufe 1 endet mit einer
Messung, die entscheidet, ob ein eigenes Inputstream-Addon (Stufe 2) nötig ist.

## Nicht Teil von Stufe 1

- Tray-Symbol oder grafische Oberfläche
- macOS-Sender (nur die Trennstellen dafür werden vorbereitet)
- Fortsetzen eines vorher laufenden Films nach dem Trennen
- Eingaben vom Fernseher zurück zum Rechner
- Fehlerkorrektur bei Paketverlust

## Komponenten

### Sender (`sender/kodi_screencast`, Python + GStreamer)

| Modul | Aufgabe | plattformabhängig |
|---|---|---|
| `portal.py` | Bildschirm über das ScreenCast-Portal freigeben, liefert PipeWire-fd und Node-ID | ja (Linux) |
| `pipeline.py` | GStreamer-Pipeline aus Aufnahme-, Kodier- und Transportteil zusammensetzen | Aufnahme/Encoder ja |
| `kodi.py` | JSON-RPC-Client: Wiedergabe starten und stoppen | nein |
| `discovery.py` | Kodi über dessen mDNS-Ankündigung finden | nein |
| `cli.py` | `kodi-screencast start` / `stop` | nein |

Für einen macOS-Port werden `portal.py` und die Aufnahme-/Encoder-Elemente in
`pipeline.py` ersetzt, der Rest bleibt.

### Kodi-Addon (`plugin.video.screencast`, Python)

Wird per `Player.Open` mit `plugin://plugin.video.screencast/?port=…`
aufgerufen und öffnet den Stream über `inputstream.ffmpegdirect` mit
gesetztem Echtzeit-Flag.

Nachtrag: Kodis Player hält mit Ton rund 2 s Puffer (Messung im README).
Der Ton läuft deshalb am Player vorbei: `service.py` startet während eines
Screencasts `resources/lib/receiver.py`, das rohes PCM per UDP annimmt und
über `aplay` ausgibt, und nimmt Kodi so lange das Tongerät weg.

## Datenfluss

1. Sender findet Kodi (mDNS `_xbmc-jsonrpc-h._tcp` oder `--host`).
2. Sender holt den Bildschirm über das Portal. Beim ersten Mal fragt KDE nach
   dem Bildschirm, danach wird die Auswahl über ein Restore-Token gemerkt.
3. Pipeline: PipeWire → Skalierung auf max. 1080 Zeilen → HEVC (VAAPI, keine
   B-Frames, Keyframe jede Sekunde) → MPEG-TS → UDP an den Pi. Der
   Systemton geht getrennt davon als rohes PCM per UDP an den Ton-Empfänger.
4. Sender ruft `Player.Open` auf, Kodi spielt `udp://@:<port>`.
5. Bei `stop`, Strg+C oder Ende der Freigabe: `Player.Stop`, sofern noch der
   eigene Stream läuft.

HEVC ist gesetzt, weil der Pi 5 nur HEVC in Hardware dekodiert.

## Fehlerfälle

- Kodi nicht gefunden oder JSON-RPC nicht erreichbar: Abbruch mit Hinweis,
  welche Kodi-Einstellung fehlt.
- Portal-Freigabe abgelehnt: Abbruch ohne Stream.
- Pipeline-Fehler während des Betriebs: Kodi-Wiedergabe stoppen, Fehler
  ausgeben, Exit-Code ungleich 0.

## Test und Messung

- Unit-Tests (pytest) für alles ohne Hardware: Pipeline-Beschreibung,
  Skalierung, JSON-RPC-Client, mDNS-Parser, Stream-URL des Addons.
- Latenz: laufende Stoppuhr auf dem Rechner, Foto von Rechner und Fernseher
  zusammen, Differenz ablesen. UDP und zum Vergleich HTTP messen, Ergebnis im
  README festhalten.

## Nachtrag: Latenz und der Weg des Tons

Die Werte stammen aus Kodis Debug-Log und den Pufferständen, nicht aus einer
Messung mit Stoppuhr am Fernseher: Bild etwa 0,3 s, Ton etwa 0,1 s plus die
eingestellte Verzögerung. Im Sender selbst entstehen rund 0,2 s.

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

Verworfen wurden: die Sendepause mit Tonspur im Strom, eine kurzzeitig
schnellere Wiedergabe (lehnt Kodi bei Live-Streams ab), Kodis eingebauter
Demuxer statt `inputstream.ffmpegdirect` (analysiert genauso lange) und
H.264 statt HEVC (spart nur etwa 0,2 s, auf dem Pi 5 ohne Hardware-Dekoder).
