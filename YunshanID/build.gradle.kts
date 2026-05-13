import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.gradle.api.Project

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.21")
    }
}

apply(plugin = "com.android.library")
apply(plugin = "kotlin-android")
apply(plugin = "com.github.recloudstream")

configure<CloudstreamExtension> {
    setPluginId("Yunshanid")
    setPluginName("Yunshanid")
    setPluginClass("com.Yunshanid.YunshanidPlugin")
    // Deskripsi diperbarui
    setPluginDescription("Dibuat oleh BetbetMiro untuk menonton konten dari Yunshanid")
    // Author tanpa spasi
    author = "BetbetMiro"
}

dependencies {
    val cloudstreamVersion = "latest-SNAPSHOT"
    compileOnly("com.github.recloudstream:cloudstream3:$cloudstreamVersion")
}