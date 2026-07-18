plugins {
    kotlin("jvm") version "2.4.0"
    application
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
    id("dev.detekt") version "2.0.0-alpha.5"
}

group = "com.philipwilcox.spotifybutler"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass = "com.philipwilcox.spotifybutler.MainKt"
}

ktlint {
    version.set("1.5.0")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    parallel = true
}

tasks.register("lint") {
    dependsOn("ktlintCheck", "detekt")
}
