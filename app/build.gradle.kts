plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jp.imamura.nokori"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.imamura.nokori"
        minSdk = 26
        targetSdk = 36
        // GitHub Actions の実行番号をバージョンにする（上書きインストールできるように）
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // 検証用ビルド（.github/workflows/build-exp.yml）は別のアプリIDにして、
        // 普段使いの版（jp.imamura.nokori）と並べて入れられるようにする（データも別）
        if (System.getenv("NOKORI_EXP") == "1") {
            applicationIdSuffix = ".exp"
            versionName = "exp." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        }
    }

    signingConfigs {
        create("personal") {
            // 署名鍵は app/nokori.jks.enc を GitHub Actions がビルド時に復号して使う。
            // パスワードは GitHub の Secrets（NOKORI_KEY_PASSWORD）にだけ置く。
            val pass = System.getenv("NOKORI_KEY_PASSWORD") ?: ""
            storeFile = file("nokori.jks")
            storePassword = pass
            keyAlias = "nokori"
            keyPassword = pass
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

dependencies {
    // 単体テスト（端末なしで動く計算の検査）。アプリ本体には入らない
    testImplementation("junit:junit:4.13.2")
}
