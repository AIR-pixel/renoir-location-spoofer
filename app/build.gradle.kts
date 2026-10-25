plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.air.locspoof"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.air.locspoof"
        // 28 而不是 30：模块主体是「App 进程内改 getter」的路线，只用到 API 18+
        // 的 API（isMock / setMock 是 API 31，但都走反射按方法名扫描，找不到就跳过），
        // 所以可以在 Android 9 的虚拟机（VMOS 等）上跑。
        // 降 minSdk 不影响向上兼容，A17 那台照常。
        minSdk = 28
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
