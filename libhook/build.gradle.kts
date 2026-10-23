plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "top.spacejoy.spacehook.libhook"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":common"))

    // Xposed API：仅编译期可见，运行时由 LSPosed 框架注入
    compileOnly(libs.libxposed.api)

    implementation(libs.androidx.core.ktx)

    // DexKit：目标 App 启动时扫 dex，按方法签名 / 字符串特征定位混淆类。
    // 自带 arm64-v8a 原生库（libdexkit.so 约 395 KB），由 :app 的 abiFilters 裁剪。
    implementation(libs.dexkit)
}
