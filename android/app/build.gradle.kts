import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.taskassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.taskassistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // ------------------------------------------------------------------
    // 签名配置（显式固定，不依赖各机器自动生成的 debug 密钥）
    // ------------------------------------------------------------------
    // 背景：项目原先没有任何 signingConfigs，AGP 会使用 ~/.android/debug.keystore，
    // 而 GitHub Actions 的 runner 每次都是全新环境 → 每次构建都会新生成一个随机
    // debug 密钥，导致**同一个应用每次产物的签名都不一样**。
    // 后果：
    //   1) 覆盖安装失败（INSTALL_FAILED_UPDATE_INCOMPATIBLE，"应用未安装"）；
    //   2) 部分安装器在证书校验阶段直接判定 APK 异常（"解析软件包时出现问题"）。
    //
    // 现在改用仓库内的 app/debug.keystore，签名在所有机器/所有构建中保持一致。
    // 这是 debug 密钥（口令 android），仅供调试安装，绝不要用于发布。
    signingConfigs {
        // AGP 默认已登记名为 "debug" 的签名配置；maybeCreate 作为兜底，
        // 避免在个别 AGP 版本上因 "debug" 尚不存在而 getByName 失败。
        maybeCreate("debug")
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "jks"
            // 三种签名方案全部启用，最大化安装器兼容性：
            //   v1 = JAR 签名（老安装器 / 部分国产 ROM 的校验只认这个）
            //   v2 = APK Signature Scheme v2（Android 7+ 主流方案）
            //   v3 = v2 增强（Android 9+，支持密钥轮换）
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // 显式绑定到上面固定的 debug 签名配置
            signingConfig = signingConfigs.getByName("debug")
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
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-schedule"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    // 说明：界面用「状态驱动的 Compose 页面切换」（MainViewModel.screen + when），
    // 不使用 Navigation 图，因此不引入 androidx.navigation:navigation-compose。
    // libs.versions.toml 里的 navigation-compose 条目仅作参考保留，未在此引用。

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
