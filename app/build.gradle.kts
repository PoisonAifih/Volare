import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// CI passes the run number so every published build outranks the previous one. Local
// builds stay at 1, which is fine because they are never published.
val appVersionCode = (System.getenv("ONTHEFLY_VERSION_CODE") ?: "1").toInt()

// Release signing is only configured when all four values are present, so a plain
// `assembleRelease` on a machine without the keystore still works (signed with debug).
// Deliberately not named `keyAlias`/`keyPassword`: inside the signingConfigs block those
// names resolve to SigningConfig's own properties, silently assigning null.
val signingStorePath: String? = System.getenv("ONTHEFLY_KEYSTORE_PATH")
val signingStorePassword: String? = System.getenv("ONTHEFLY_KEYSTORE_PASSWORD")
val signingKeyAlias: String? = System.getenv("ONTHEFLY_KEY_ALIAS")
val signingKeyPassword: String? = System.getenv("ONTHEFLY_KEY_PASSWORD")
val hasReleaseSigning =
    listOf(signingStorePath, signingStorePassword, signingKeyAlias, signingKeyPassword)
        .none { it.isNullOrBlank() }

android {
    namespace = "dev.aifih.onthefly"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.aifih.onthefly"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = "0.1.$appVersionCode"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingStorePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}
