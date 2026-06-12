// use an integer for version numbers
version = 2

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "id"
    description = "AKTV live channel provider from APK-extracted playlist evidence"
    authors = listOf("sad25kag")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3
    tvTypes = listOf("Live")

    requiresResources = false
    isCrossPlatform = false
}
