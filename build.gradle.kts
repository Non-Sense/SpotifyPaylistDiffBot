plugins {
    kotlin("jvm") version "1.7.10"
    id("com.github.johnrengelman.shadow") version "7.1.0"
    application
}

group = "com.n0n5ense"
version = "1.0-SNAPSHOT"


val spotifyClientId: String? by project
val spotifyClientSecret: String? by project
val spotifyDiscordBotToken: String? by project



repositories {
    mavenCentral()
    maven {
        name = "m2-dv8tion"
        url = uri("https://m2.dv8tion.net/releases")
    }
}

application {
    mainClass.value("com.n0n5ense.spotifydiff.MainKt")
//    System.setProperty("spotifyClientId", spotifyClientId ?: "")
//    System.setProperty("spotifyClientSecret", spotifyClientSecret ?: "")
//    System.setProperty("spotifyDiscordBotToken", spotifyDiscordBotToken ?: "")
}

dependencies {
    val exposedVersion = "0.40.1"
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.39.3.0")
    implementation("io.arrow-kt:arrow-core:1.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.5")
    implementation("com.adamratzman:spotify-api-kotlin-core:3.8.8")
    implementation("net.dv8tion:JDA:4.3.0_277")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.7.20")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.7.20")
}

tasks.withType<JavaExec> {
    systemProperties["spotifyClientId"] = spotifyClientId ?: ""
    systemProperties["spotifyClientSecret"] = spotifyClientSecret ?: ""
    systemProperties["spotifyDiscordBotToken"] = spotifyDiscordBotToken ?: ""
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

val jar by tasks.getting(Jar::class) {
    manifest {
        attributes["Main-Class"] = "com.n0n5ense.spotifydiff.MainKt"
        attributes["Multi-Release"] = true
    }
}

tasks.test {
    useJUnitPlatform()
}
