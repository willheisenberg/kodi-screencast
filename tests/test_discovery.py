from kodi_screencast import discovery

OUTPUT = """\
+;eth0;IPv4;Kodi\\032\\040LibreELEC\\041;_xbmc-jsonrpc-h._tcp;local
+;eth0;IPv6;Kodi\\032\\040LibreELEC\\041;_xbmc-jsonrpc-h._tcp;local
=;eth0;IPv6;Kodi\\032\\040LibreELEC\\041;_xbmc-jsonrpc-h._tcp;local;LibreELEC.local;fe80::1;8080;"uuid=x"
=;eth0;IPv4;Kodi\\032\\040LibreELEC\\041;_xbmc-jsonrpc-h._tcp;local;LibreELEC.local;192.168.178.10;8080;"uuid=x"
=;wlan0;IPv4;Kodi\\032\\040LibreELEC\\041;_xbmc-jsonrpc-h._tcp;local;LibreELEC.local;192.168.178.10;8080;"uuid=x"
"""


def test_parse_returns_resolved_ipv4_entries_once():
    assert discovery.parse(OUTPUT) == [
        ("Kodi\\032\\040LibreELEC\\041", "192.168.178.10", 8080),
    ]


def test_parse_of_empty_output_finds_nothing():
    assert discovery.parse("") == []
