plugins {
    kotlin("jvm") version "2.4.0"
    application
    id("app.cash.sqldelight") version "2.1.0"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
    id("dev.detekt") version "2.0.0-alpha.5"
}

group = "com.philipwilcox.spotifybutler"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("app.cash.sqldelight:sqlite-driver:2.1.0")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test"))
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

sqldelight {
    databases {
        create("SpotifyDatabase") {
            packageName.set("com.philipwilcox.spotifybutler.db")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.1.0")
        }
    }
}

tasks.register("lint") {
    dependsOn("ktlintCheck", "detekt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>().configureEach {
    when (name) {
        "runKtlintCheckOverMainSourceSet",
        "runKtlintFormatOverMainSourceSet",
        -> setSource(fileTree("src/main/kotlin"))

        "runKtlintCheckOverTestSourceSet",
        "runKtlintFormatOverTestSourceSet",
        -> setSource(fileTree("src/test/kotlin"))
    }
}
