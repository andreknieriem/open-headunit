import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    compileSdk = 34
    namespace = "com.andrerinas.openheadunit.contract"

    defaultConfig {
        minSdk = 16
        targetSdk = 34
    }

//    buildTypes {
//        create("release") {
//            postprocessing {
//                removeUnusedCode = false
//                removeUnusedResources = false
//                obfuscate = false
//                optimizeCode = false
//                proguardFile("proguard-rules.pro")
//            }
//        }
//    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}
