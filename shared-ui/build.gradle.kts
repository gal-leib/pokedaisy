// The companion's screens (app/.../companion/ui, the companion's settings and state next to them, the co-op
// screens' model, the manual reader, cheats, the controls' bindings) compiled for iOS where they live, against
// shared/ (the data and language code) and Compose Multiplatform. The Android app still compiles these files
// itself; this module is what the iPad app will link, and CI's iOS job keeps it building (advisory for now).
// Nothing in these files may use java.* or android.*: what differs by platform comes in through hosts set at
// start (FontSource, PictureCodec, ThemeStore, DocsSources, CheatParser, DeviceDates, Logs' sink).
plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
}

val app = rootDir.resolve("app/src/main/kotlin")

// The co-op transport and session: Android / JVM only (sockets, Android NSD, Bouncy Castle, OkHttp).
val coopJvmOnly = listOf(
    "CoopChannel", "CoopDirect", "CoopFrames", "CoopIdentity", "CoopIpHello", "CoopKeys", "CoopLan", "CoopNoise",
    "CoopSession", "OkHttpRelayConnector", "RelayCoopTransport", "lock/CoopLockStore",
)

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            kotlin.srcDir(app)
            kotlin.include(
                "com/pokedaisy/app/companion/ui/**",
                "com/pokedaisy/app/companion/*.kt",
                "com/pokedaisy/app/companion/coop/**",
                "com/pokedaisy/app/cheats/Cheats.kt",
                "com/pokedaisy/app/DocsApi.kt",
                "com/pokedaisy/app/DocsSearch.kt",
                "com/pokedaisy/app/GbaControls.kt",
                "com/pokedaisy/app/Hotkeys.kt",
            )
            kotlin.exclude(coopJvmOnly.map { "com/pokedaisy/app/companion/coop/$it.kt" })
            dependencies {
                implementation(project(":shared"))
                // Compose Multiplatform 1.7.x, as shared/ (Compose 1.7 on Android, the app's BOM).
                implementation("org.jetbrains.compose.foundation:foundation:1.7.3")
                implementation("org.jetbrains.compose.material3:material3:1.7.3")
                implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:atomicfu:0.23.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
            }
        }
    }
}
