import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Logica di sync condivisa da :app (Android) e :desktop (Linux): Kotlin/JVM puro, zero API Android.
// Bytecode 17 come :app, senza toolchain: si compila con il JDK in uso (qui 21).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // api: questi tipi compaiono nelle firme pubbliche che i due frontend usano.
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp.logging)
}
