plugins {
    kotlin("jvm") version "1.9.0"
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("com.github.lagradost:cloudstream3:pre-release")
}