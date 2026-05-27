version = 4

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "YouTube provider with NewPipe-backed Indonesian channels, direct stream links, and category rows."

    /**
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf("Others")
    isCrossPlatform = false
    iconUrl = "https://www.youtube.com/s/desktop/711fd789/img/logos/favicon_144x144.png"
}

android {
    namespace = "com.youtube"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.25.2")
}
