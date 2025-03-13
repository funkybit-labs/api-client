import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jmailen.kotlinter") version "4.4.1"
}

group = "xyz.funkybit"
version = "1.0.0"

repositories {
    mavenCentral()
}

// Configure source sets to include examples
sourceSets {
    main {
        kotlin {
            srcDirs("src/main/kotlin", "examples")
        }
    }
}

val http4kVersion = "5.33.1.0"

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.21")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("org.apache.logging.log4j:log4j-api:2.23.1")
    implementation("org.apache.logging.log4j:log4j-core:2.23.1")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.23.1")
    implementation("org.awaitility:awaitility-kotlin:4.2.2")
    implementation("io.arrow-kt:arrow-core:1.2.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.web3j:core:4.12.2")
    implementation("org.bitcoinj:bitcoinj-core:0.16.3") {
        exclude(group = "com.google.protobuf", module = "protobuf-javalite") // exclude since included version contains a vulnerability
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18") // exclude since included version contains a vulnerability
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.http4k:http4k-core:$http4kVersion")
    implementation("org.http4k:http4k-realtime-core:$http4kVersion")
    implementation("org.http4k:http4k-contract:$http4kVersion")
    implementation("org.http4k:http4k-format-kotlinx-serialization:$http4kVersion")
    implementation("org.http4k:http4k-format-argo:$http4kVersion")
    implementation("org.http4k:http4k-client-okhttp:$http4kVersion")
    implementation("org.http4k:http4k-client-websocket:$http4kVersion")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

// Add task to run the example
tasks.register<JavaExec>("runExample") {
    description = "Run the funkybit API client example"
    mainClass.set("xyz.funkybit.client.example.ExampleKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir
}

