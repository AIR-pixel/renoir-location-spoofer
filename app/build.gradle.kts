plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.air.locspoof"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.air.locspoof"
        // A11 起 LocationManagerService$Receiver 结构稳定；A10 以下未做适配
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    // 编译期依赖，运行期由 Xposed 框架注入
    compileOnly("de.robv.android.xposed:api:82")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    // 地图选点：osmdroid 不需要任何 API key
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
