plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

android {
    namespace = "top.spacejoy.spacehook"
    compileSdk = 37

    defaultConfig {
        applicationId = "top.spacejoy.spacehook"
        minSdk = 26
        targetSdk = 37
        // ==================== 版本号（语义化 SemVer）====================
        // versionName = major.minor.patch
        //   major：不兼容的大改 / 架构调整
        //   minor：新增功能
        //   patch：修 bug / 适配新版目标 App
        // versionCode 由上面的三段自动换算，不用手改：
        //   major * 10000 + minor * 100 + patch
        //   例：1.0.0 → 10000，1.1.0 → 10100，1.1.3 → 10103
        val versionMajor = 1
        val versionMinor = 1
        val versionPatch = 0
        versionName = "$versionMajor.$versionMinor.$versionPatch"
        versionCode = versionMajor * 10000 + versionMinor * 100 + versionPatch

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        // Miuix 0.9.4（含 miuix-nav）的 class 文件是 JVM 21（class major 65），
        // 而 `NavEntryBuilder.entry { }` 是 inline 函数：宿主模块的 jvmTarget 低于 21 会报
        // "Cannot inline bytecode built with JVM target 21 into bytecode built with JVM target 17"。
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        // 与 compileOptions 一致：必须 >= Miuix 的 21，否则 inline 的 nav DSL 无法内联
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":libhook"))

    // ---------- Xposed：模块 App 与框架通信 ----------
    implementation(libs.libxposed.service)

    // ---------- Miuix UI ----------
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    // Miuix 官方导航库：NavDisplay / NavController / NavTransitions（含预测性返回与 Y 轴上滑动画）
    implementation(libs.miuix.nav)

    // ---------- Compose / AndroidX ----------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigationevent.compose)
}
