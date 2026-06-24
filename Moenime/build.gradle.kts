plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    authors = listOf("BetbetMiro")
    language = "id"
    name = "Moenime"
    version = 1
    status = 3 // Working
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    iconUrl = "https://moenime.com/favicon.ico"
}

android {
    namespace = "com.sad25kag.moenime"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.lagradost.cloudstream3:lib:master-SNAPSHOT")
}