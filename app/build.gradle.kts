plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val keystoreFile = rootProject.file("keystore/release.jks")
val keystorePass = System.getenv("KS_PASS") ?: "streambox"

android {
    namespace = "com.pecbox.streambox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pecbox.streambox"
        minSdk = 23
        targetSdk = 35
        versionCode = 35
        versionName = "0.7.20"
    }

    /*
     * Duas versões do mesmo app:
     *  - pecbox:     servidor PecBox fixo; o login pede só usuário/senha ou código.
     *  - comunidade: sem nenhum endereço; cada pessoa informa o próprio Jellyfin.
     * Compile uma só com:  ./build.sh pecbox   ou   ./build.sh comunidade
     */
    flavorDimensions += "dist"
    productFlavors {
        create("pecbox") {
            dimension = "dist"
            buildConfigField("String", "DEFAULT_SERVER", "\"https://midia.pecbox.com.br/api\"")
            buildConfigField("String", "DEFAULT_INDEXER", "\"https://strm.pecbox.com.br/strm-api\"")
            buildConfigField("String", "DEFAULT_SEERR", "\"https://seer.pecbox.com.br\"")
            buildConfigField("boolean", "LOCK_SERVER", "true")
            // Contato mostrado no login para quem não tem conta (WhatsApp, com DDI)
            buildConfigField("String", "ADMIN_WHATSAPP", "\"5593991807963\"")
            buildConfigField("String", "ADMIN_WHATSAPP_LABEL", "\"(93) 99180-7963\"")
            buildConfigField("String", "UPDATE_INDEX", "\"https://midia.pecbox.com.br/downloads/files/\"")
            buildConfigField("String", "DOWNLOAD_PAGE", "\"https://midia.pecbox.com.br/#/downloads\"")
        }
        create("comunidade") {
            dimension = "dist"
            applicationIdSuffix = ".comunidade"   // pode ficar instalado junto com a versão PecBox
            buildConfigField("String", "DEFAULT_SERVER", "\"\"")
            buildConfigField("String", "DEFAULT_INDEXER", "\"\"")
            buildConfigField("String", "DEFAULT_SEERR", "\"\"")
            buildConfigField("boolean", "LOCK_SERVER", "false")
            buildConfigField("String", "ADMIN_WHATSAPP", "\"\"")
            buildConfigField("String", "ADMIN_WHATSAPP_LABEL", "\"\"")
            buildConfigField("String", "UPDATE_INDEX", "\"https://midia.pecbox.com.br/downloads/files/comunidade/\"")
            buildConfigField("String", "DOWNLOAD_PAGE", "\"https://midia.pecbox.com.br/#/downloads?p=comunidade\"")
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = keystorePass
                keyAlias = "streambox"
                keyPassword = keystorePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreFile.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource:1.5.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
