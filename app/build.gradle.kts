plugins {
    id("com.android.application")
}

android {
    namespace = "com.xavier.subdata"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.xavier.subdata"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.3.1-beta"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 故意不混淆：模块只有几个类，保留原始堆栈更利于排查。
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/*.version")
    }
}

dependencies {
    // Xposed API 只在编译期使用，运行时由宿主框架提供。
    compileOnly(files("libs/xposed-api-82.jar"))
    testImplementation("junit:junit:4.13.2")

    // 界面只依赖 Material Components（自带 appcompat / recyclerview / viewpager2）。
    implementation("com.google.android.material:material:1.12.0")
}
