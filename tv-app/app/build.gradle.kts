plugins {
    id("com.android.application")
}

android {
    namespace = "com.splitscreen.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.splitscreen.tv"
        minSdk = 24
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.10.0")

    // WebSocket
    implementation("org.java-websocket:Java-WebSocket:1.5.4")

    // ExoPlayer for video zones
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")

    // Image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // Embedded HTTP server (file upload + media serving)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
