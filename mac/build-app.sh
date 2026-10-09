#!/bin/sh
# Baut build/KodiScreencast.app und packt sie als ZIP. Läuft nur auf macOS
# mit installiertem Xcode oder den Command Line Tools.
set -eu
cd "$(dirname "$0")"

swift build -c release --arch arm64 --arch x86_64
APP=build/KodiScreencast.app
rm -rf build
mkdir -p "$APP/Contents/MacOS"
cp .build/apple/Products/Release/KodiScreencast "$APP/Contents/MacOS/"
cp Info.plist "$APP/Contents/"
# Ohne Entwicklerkonto nur lokal signiert: macOS verlangt beim ersten Start
# eine Freigabe unter Datenschutz & Sicherheit.
codesign --force --sign - "$APP"
ditto -c -k --keepParent "$APP" build/KodiScreencast.zip
echo "Fertig: $APP"
