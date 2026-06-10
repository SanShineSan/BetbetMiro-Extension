version = 4

cloudstream {
    language = "id"
    authors = listOf("sad25kag")
    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA"
    )
    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://filmanime.id&size=%size%"
}

dependencies {
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.25.2")
}
