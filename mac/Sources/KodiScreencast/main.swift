import Foundation
import ScreencastCore

// "--selftest DATEI" prüft Encoder und Muxer ohne Oberfläche und ohne
// Bildschirmfreigabe; alles andere startet die Menüleisten-App.
let arguments = CommandLine.arguments
if let index = arguments.firstIndex(of: "--selftest"), index + 1 < arguments.count {
    do {
        try SelfTest.writeStream(to: arguments[index + 1])
        print("Teststrom geschrieben: \(arguments[index + 1])")
        exit(0)
    } catch {
        FileHandle.standardError.write(Data("\(error.localizedDescription)\n".utf8))
        exit(1)
    }
}

KodiScreencastApp.main()
