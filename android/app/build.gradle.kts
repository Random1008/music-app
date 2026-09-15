import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Keystore de dev STABLE (hors dépôt git) : garantit que l'app se réinstalle
// par-dessus l'ancienne version sans devoir la désinstaller.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasDevKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "fr.hermesmusic"
    // compileSdk 37 est exigé par plusieurs dépendances (Compose 1.12.1,
    // core 1.19.0, lifecycle 2.11.0, Coil 3.6.2, okhttp-android 5.5.0).
    // targetSdk reste à 36 : on n'opte pas dans les nouveaux comportements
    // d'exécution d'Android 17 (compileSdk et targetSdk sont indépendants).
    compileSdk = 37

    defaultConfig {
        applicationId = "fr.hermesmusic"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.3.0"
        resourceConfigurations += listOf("fr", "en")
    }

    if (hasDevKeystore) {
        signingConfigs {
            create("dev") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (hasDevKeystore) signingConfig = signingConfigs.getByName("dev")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasDevKeystore) signingConfig = signingConfigs.getByName("dev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// jvmTarget est aligné automatiquement sur compileOptions par le support Kotlin
// intégré d'AGP 9 : inutile (et risqué) de le fixer à la main.

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
