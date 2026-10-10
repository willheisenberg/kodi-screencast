plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.willheisenberg.kodiscreencast"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.willheisenberg.kodiscreencast"
        // Android 10: ab hier lässt sich der Systemton mitschneiden.
        minSdk = 29
        targetSdk = 36
        // Die Veröffentlichung setzt beides aus dem Versions-Tag.
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1"
    }

    // Fester Schlüssel, damit sich eine neue Version über die alte
    // installieren lässt. Ohne die Angaben bleibt die Release-Variante
    // unsigniert.
    val keystore = System.getenv("KEYSTORE_FILE")
    if (keystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
        buildTypes {
            release {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
