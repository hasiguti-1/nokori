plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jp.imamura.nokori"
    compileSdk = 34

    defaultConfig {
        applicationId = "jp.imamura.nokori"
        minSdk = 26
        targetSdk = 34
        // GitHub Actions の実行番号をバージョンにする（上書きインストールできるように）
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        create("personal") {
            // 自分用の署名鍵。同じ鍵で署名し続けるので、新しい版をそのまま上書きできる
            storeFile = file("nokori.jks")
            storePassword = "nokori-pass"
            keyAlias = "nokori"
            keyPassword = "nokori-pass"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
