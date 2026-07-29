plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.zidonghua"
    compileSdk = 34  // ✅ 稳定版本

    defaultConfig {
        applicationId = "com.example.zidonghua"
        minSdk = 24
        targetSdk = 34  // ✅ 稳定版本
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation("com.google.code.gson:gson:2.10.1")

    // ====== ML Kit 文字识别 ======
    // 基础库（必须）
    implementation("com.google.mlkit:text-recognition:16.0.0")
    // 中文识别
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
    // 日文识别
    implementation("com.google.mlkit:text-recognition-japanese:16.0.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}