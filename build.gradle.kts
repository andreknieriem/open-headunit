
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 8.13 bundles R8 8.13, which cannot read Kotlin 2.4 metadata and warns once per class.
        classpath("com.android.tools:r8:9.1.57")
        classpath("com.android.tools.build:gradle:8.13.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

