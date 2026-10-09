"""Startet den Ton-Empfänger, solange Kodi einen Screencast mit Ton zeigt.

Kodi bekommt nur das Bild. Der Ton läuft an Kodis Player vorbei direkt auf
die Soundkarte; dafür muss Kodi das Gerät für die Dauer freigeben.
"""

import json
import os
import subprocess

import xbmc

from resources.lib import stream

RECEIVER = os.path.join(os.path.dirname(__file__), "resources", "lib", "receiver.py")


def log(message, level=xbmc.LOGINFO):
    xbmc.log(f"[{stream.ADDON_ID}] {message}", level)


def kodi_audio_device():
    answer = json.loads(xbmc.executeJSONRPC(json.dumps({
        "jsonrpc": "2.0", "id": 1, "method": "Settings.GetSettingValue",
        "params": {"setting": "audiooutput.audiodevice"},
    })))
    return answer.get("result", {}).get("value", "")


class Audio:
    def __init__(self):
        self.current = None
        self.process = None

    def switch(self, wanted):
        if wanted == self.current:
            return
        self.stop()
        if wanted:
            port, delay = wanted
            device = stream.alsa_device(kodi_audio_device())
            xbmc.audioSuspend()
            try:
                self.process = subprocess.Popen(
                    ["python3", RECEIVER, str(port), str(delay), device]
                )
            except OSError as error:
                xbmc.audioResume()
                log(f"Ton-Empfänger startet nicht: {error}", xbmc.LOGERROR)
            else:
                log(f"Ton auf Port {port} über {device}, {delay} ms verzögert")
        self.current = wanted

    def stop(self):
        self.current = None
        if self.process is None:
            return
        self.process.terminate()
        try:
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.process.kill()
        self.process = None
        xbmc.audioResume()
        log("Ton wieder bei Kodi")


def main():
    monitor = xbmc.Monitor()
    player = xbmc.Player()
    audio = Audio()
    try:
        while not monitor.waitForAbort(0.25):
            try:
                file = player.getPlayingFile() if player.isPlaying() else ""
            except RuntimeError:
                file = ""
            audio.switch(stream.audio_of_playing(file))
    finally:
        audio.stop()


main()
