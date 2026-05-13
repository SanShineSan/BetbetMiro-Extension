import com.lagradost.cloudstream3.gradle.CloudstreamExtension

apply(plugin = "com.android.library")
apply(plugin = "kotlin-android")
apply(plugin = "com.github.recloudstream")

configure<CloudstreamExtension> {
    // Gunakan nama properti spesifik ini agar tidak bentrok dengan Gradle Project
    pluginId = "Yunshanid"
    pluginName = "Yunshanid"
    pluginClass = "com.Yunshanid.YunshanidPlugin"
    description = "Dibuat oleh BetbetMiro untuk Yunshanid"
    authors = listOf("BetbetMiro")
}

dependencies {
    val cloudstreamVersion = "latest-SNAPSHOT"
    compileOnly("com.github.recloudstream:cloudstream3:$cloudstreamVersion")
}
