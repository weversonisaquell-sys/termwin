plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.termwin.console"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.termwin.console"
        minSdk = 28          // Android 9+
        targetSdk = 34
        versionCode = 13
        versionName = "2.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Fixed signing key: every build (GitHub Actions or local) is signed with the
    // same key, so a new version installs OVER the old one without conflict.
    signingConfigs {
        create("termwin") {
            storeFile = file("termwin.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("termwin") }
        getByName("release") { signingConfig = signingConfigs.getByName("termwin") }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST", "META-INF/versions/**", "plugin.properties", "about.html")
        }
    }
}

dependencies {
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r")
    implementation("org.slf4j:slf4j-nop:1.7.36")
}
