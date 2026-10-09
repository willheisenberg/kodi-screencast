"""Findet Kodi über dessen mDNS-Ankündigung der JSON-RPC-Schnittstelle."""

import subprocess

SERVICE = "_xbmc-jsonrpc-h._tcp"


def parse(output):
    """Liest (name, adresse, port) aus `avahi-browse -rtp`, nur IPv4."""
    found = []
    for line in output.splitlines():
        fields = line.split(";")
        if len(fields) >= 9 and fields[0] == "=" and fields[2] == "IPv4":
            entry = (fields[3], fields[7], int(fields[8]))
            if entry not in found:
                found.append(entry)
    return found


def find():
    result = subprocess.run(
        ["avahi-browse", "-rtp", SERVICE],
        capture_output=True, text=True, timeout=10,
    )
    return parse(result.stdout)
