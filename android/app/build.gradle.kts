plugins {
    id("com.android.application")
}

android {
    namespace = "com.fanqie.focus"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fanqie.focus"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.1.6"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 用 debug 签名，方便自己装到手机上测；正式上架前换成自己的 keystore
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 网页资源不做压缩，保持 index.html 原样
    androidResources {
        noCompress += listOf("html", "js", "css", "webmanifest")
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.18.0")
}
