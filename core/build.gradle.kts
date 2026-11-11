// Noyau en Kotlin pur : cryptographie, TOTP, délais, échéances, archive.
// Aucune dépendance Android : tout est testable sur JVM (./gradlew :core:test).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
}
