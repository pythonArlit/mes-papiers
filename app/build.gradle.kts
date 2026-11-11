import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// La signature de production reste hors du dépôt : le chemin d'un fichier de propriétés
// est donné par la variable d'environnement MESPAPIERS_SIGNING (voir README.md).
val signingFile = System.getenv("MESPAPIERS_SIGNING")?.let(::File)?.takeIf { it.isFile }
val signingProps = Properties().apply { signingFile?.inputStream()?.use(::load) }

android {
    namespace = "app.mespapiers"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.mespapiers"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "variante"
    productFlavors {
        // Parcours réel uniquement. Le code de src/demo n'est pas compilé dans cette variante.
        create("prod") { dimension = "variante" }
        create("demo") {
            dimension = "variante"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
        }
    }

    signingConfigs {
        if (signingFile != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module", "org/bouncycastle/**/*.properties")
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
