import sys

import xbmc
import xbmcgui
import xbmcplugin

from resources.lib import stream


def main():
    handle = int(sys.argv[1])
    port = stream.port_from_query(sys.argv[2])
    audio = stream.audio_from_query(sys.argv[2])
    source = stream.source_from_query(sys.argv[2])

    item = xbmcgui.ListItem(label="Screencast", path=stream.stream_url(port, audio, source))
    item.setMimeType(stream.MIME_TYPE)
    item.setContentLookup(False)
    for key, value in stream.PROPERTIES.items():
        item.setProperty(key, value)

    if handle >= 0:
        xbmcplugin.setResolvedUrl(handle, True, item)
    else:
        xbmc.Player().play(item.getPath(), item)


main()
