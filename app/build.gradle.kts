import java.util.Properties

plugins {
    id("com.android.application")
}

// 版本号从 version.properties 读，由 build.sh 递增
val versionProps = Properties().apply {
    val file = rootProject.file("version.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val appVersionCode = (versionProps.getProperty("version.code", "1")).toInt()
val appVersionName = versionProps.getProperty("version.name", "1.0")

android {
    namespace = "com.leidian.sk5"
    compileSdk = 35

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        unitTests.all {
            it.workingDir = projectDir
        }
    }

    defaultConfig {
        applicationId = "com.leidian.sk5"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            // 内核心 .so 约 110MB，多打一个 ABI 就多几十兆
            abiFilters += listOf("arm64-v8a")
        }
    }

    val keystoreProps = Properties().apply {
        val file = rootProject.file("signing/keystore.properties")
        if (file.exists()) {
            file.inputStream().use { load(it) }
        }
    }
    val hasKeystore = keystoreProps.getProperty("storeFile") != null

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // debug 也用正式签名，换包时不用先卸载
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        // 规则集是二进制 srs，压缩后内核读不了
        noCompress += listOf("srs", "dat", "bin")
    }
}

dependencies {
    // sing-box 内核（官方 gomobile 构建）
    implementation(files("libs/libbox.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
