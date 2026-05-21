import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Carrega credenciais de assinatura de local.properties (dev) ou env vars (CI)
val localProps = Properties().also { props ->
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { props.load(it) }
}
fun signingProp(envKey: String, localKey: String = envKey): String =
    System.getenv(envKey) ?: localProps.getProperty(localKey) ?: ""

android {
    namespace = "com.hudimpulse.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hudimpulse.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 20
        versionName = "1.0.20"

        buildConfigField("String", "GITHUB_REPO", "\"rafaelcs28/HudImpulse\"")
        buildConfigField("String", "HERE_API_KEY", "\"${signingProp("HERE_API_KEY", "here.api.key")}\"")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = signingProp("SIGNING_STORE_FILE")
            if (storeFilePath.isNotEmpty()) {
                storeFile     = file(storeFilePath)
                storePassword = signingProp("SIGNING_STORE_PASSWORD")
                keyAlias      = signingProp("SIGNING_KEY_ALIAS")
                keyPassword   = signingProp("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
