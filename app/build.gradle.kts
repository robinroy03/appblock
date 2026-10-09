import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing credentials live in <repo>/keystore.properties (gitignored).
// Without them the release build stays unsigned, so any clone can still build.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.robin.appblock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.robin.appblock"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.8.0"
        // e2e tests (app/src/androidTest) drive the real app on a device.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (!keystoreProps.isEmpty) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
        buildTypes {
            release {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // e2e: UI Automator finds and taps things on screen across apps (the
    // block wall is drawn over another app), like Playwright for Android.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

// ShortcutsTest reads these files straight from disk; declare them so an edit
// to them re-runs the tests instead of leaving the task UP-TO-DATE.
tasks.withType<Test>().configureEach {
    inputs.file("src/main/AndroidManifest.xml")
    inputs.dir("src/main/res/xml")
}
