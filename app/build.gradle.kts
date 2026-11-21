plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Phiên bản được workflow GitHub truyền vào khi build release (-PappVersion=1.2.3 -PappVersionCode=15)
val appVersion = (project.findProperty("appVersion") as String?) ?: "1.0.0"
val appVersionCode = ((project.findProperty("appVersionCode") as String?) ?: "1").toInt()

android {
    namespace = "com.autoclicker.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autoclicker.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion

        // >>> SỬA DÒNG NÀY thành "ten-github/ten-repo" của bạn (repo phải public) <<<
        buildConfigField("String", "GITHUB_REPO", "\"dhaskhffouf/autoclicker\"")
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_PATH")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "autoclicker.apk"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}