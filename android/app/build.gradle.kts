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
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
