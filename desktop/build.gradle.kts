import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Frontend Linux: stessa logica di :app, ma TUI Lanterna al posto di Compose,
// file di config al posto di DataStore e coroutine al posto di WorkManager.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.lanterna)
}

application {
    applicationName = "navisync"
    mainClass.set("eu.todaro.navisync.desktop.MainKt")
}
