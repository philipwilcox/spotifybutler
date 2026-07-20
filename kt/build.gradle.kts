import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
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
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.12.1")
    testImplementation("org.snakeyaml:snakeyaml-engine:2.10")
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

tasks.register<Test>("playlistGenerationContractTest") {
    group = "verification"
    description = "Run playlist-generation contract tests with visible deterministic reports."
    dependsOn("testClasses")
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("playlist-generation-contract")
    }
    testLogging.showStandardStreams = true
    systemProperty(
        "playlistGenerationReportDir",
        layout.buildDirectory
            .dir("reports/playlist-generation-contract")
            .get()
            .asFile.absolutePath,
    )
}

tasks.register("proposePlaylistGenerationGoldens") {
    group = "verification"
    description = "Generate sanitized playlist-generation reports for explicit golden review."
    dependsOn("playlistGenerationContractTest")
    doLast {
        logger.lifecycle(
            "Review proposed playlist-generation reports under " +
                layout.buildDirectory
                    .dir("reports/playlist-generation-contract")
                    .get()
                    .asFile,
        )
        logger.lifecycle("This task never overwrites committed fixture expectations.")
    }
}

open class TeeOutputStream(
    private val terminal: OutputStream,
    private val file: OutputStream,
) : OutputStream() {
    override fun write(value: Int) {
        synchronized(this) {
            terminal.write(value)
            file.write(value)
        }
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        synchronized(this) {
            terminal.write(bytes, offset, length)
            file.write(bytes, offset, length)
        }
    }

    override fun flush() {
        synchronized(this) {
            terminal.flush()
            file.flush()
        }
    }

    override fun close() {
        synchronized(this) {
            file.close()
        }
    }
}

val captureLogProperty = providers.gradleProperty("captureLog")
val captureDatabaseProperty = providers.gradleProperty("databasePath")
val captureRunIdProperty = providers.gradleProperty("captureRunId")
val fixtureOutputProperty = providers.gradleProperty("fixtureOutput")
val fixtureReportProperty = providers.gradleProperty("fixtureReport")
val scrubWorkersProperty = providers.gradleProperty("scrubWorkers")
val maxPlaylistTracksCallsProperty = providers.gradleProperty("maxPlaylistTracksCalls")
val maxPlaylistTracksProperty = providers.gradleProperty("maxPlaylistTracks")
val maxSavedTracksProperty = providers.gradleProperty("maxSavedTracks")
val maxTopItemsProperty = providers.gradleProperty("maxTopItems")
val maxAvailableMarketsProperty = providers.gradleProperty("maxAvailableMarkets")

tasks.register<JavaExec>("captureSpotifyRun") {
    group = "spotify support"
    description = "Run the service while teeing stdout and stderr into an ignored capture log."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)

    doFirst {
        val timestamp =
            DateTimeFormatter
                .ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
        val captureLog =
            project.file(
                captureLogProperty.orNull ?: "raw-captures/spotify-${timestamp.format(Instant.now())}.log",
            )
        val databasePath = project.file(captureDatabaseProperty.orNull ?: "spotify.db")
        captureLog.parentFile.mkdirs()
        databasePath.parentFile.mkdirs()
        val logStream =
            Files.newOutputStream(
                captureLog.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        val runId = "run-${timestamp.format(Instant.now())}-${UUID.randomUUID()}"
        val tee = TeeOutputStream(System.out, logStream)
        standardOutput = tee
        errorOutput = tee
        environment("SPOTIFY_BUTLER_CAPTURE_LOG", captureLog.absolutePath)
        environment("SPOTIFY_BUTLER_CAPTURE_RUN_ID", runId)
        environment("SPOTIFY_BUTLER_DATABASE_PATH", databasePath.absolutePath)
        logger.lifecycle("Spotify capture log: ${captureLog.absolutePath}")
        logger.lifecycle("Spotify capture database: ${databasePath.absolutePath}")
    }
}

tasks.register<JavaExec>("buildSpotifyFixtures") {
    group = "spotify support"
    description = "Build an ignored draft JSONL fixture from a structured capture log and SQLite database."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.philipwilcox.spotifybutler.support.BuildSpotifyFixturesMainKt")

    doFirst {
        val captureLog =
            captureLogProperty.orNull ?: throw GradleException(
                "Pass -PcaptureLog=/path/to/spotify-run.log to buildSpotifyFixtures.",
            )
        val configuredScrubWorkers = scrubWorkersProperty.orNull
        val scrubWorkers =
            configuredScrubWorkers?.toIntOrNull()?.takeIf { it > 0 }
                ?: if (configuredScrubWorkers != null) {
                    throw GradleException("-PscrubWorkers must be a positive integer.")
                } else {
                    Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                }
        logger.lifecycle("Effective fixture scrub workers: $scrubWorkers")
        args("--capture-log", project.file(captureLog).absolutePath)
        args("--scrub-workers", scrubWorkers.toString())
        maxPlaylistTracksCallsProperty.orNull?.let { args("--max-playlist-tracks-calls", it) }
        maxPlaylistTracksProperty.orNull?.let { args("--max-playlist-tracks", it) }
        maxSavedTracksProperty.orNull?.let { args("--max-saved-tracks", it) }
        maxTopItemsProperty.orNull?.let { args("--max-top-items", it) }
        maxAvailableMarketsProperty.orNull?.let { args("--max-available-markets", it) }
        captureDatabaseProperty.orNull?.let { args("--database", project.file(it).absolutePath) }
        captureRunIdProperty.orNull?.let { args("--run-id", it) }
        fixtureOutputProperty.orNull?.let { args("--output", project.file(it).absolutePath) }
        fixtureReportProperty.orNull?.let { args("--report", project.file(it).absolutePath) }
    }
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
