plugins {
    id("com.android.application")
}

android {
    namespace = "app.ticker"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.ticker"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

val sdkRoot = System.getenv("ANDROID_HOME")
    ?: file("local.properties").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }
        ?.substringAfter("=")
    ?: error("Android SDK not found")
val androidJar = File(sdkRoot, "platforms/android-37.0/android.jar")
val stubClasses = layout.buildDirectory.dir("notification-assistant-stub/classes")
val stubJar = layout.buildDirectory.file("notification-assistant-stub/notification-assistant-stub.jar")

val compileNotificationAssistantStub = tasks.register<JavaCompile>("compileNotificationAssistantStub") {
    source = fileTree("stub/java")
    classpath = files(androidJar)
    destinationDirectory.set(stubClasses)
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

val notificationAssistantStubJar = tasks.register<Jar>("notificationAssistantStubJar") {
    dependsOn(compileNotificationAssistantStub)
    from(stubClasses)
    archiveFileName.set("notification-assistant-stub.jar")
    destinationDirectory.set(layout.buildDirectory.dir("notification-assistant-stub"))
}

dependencies {
    compileOnly(files(stubJar))
}

tasks.configureEach {
    if (name == "compileDebugKotlin" || name == "compileReleaseKotlin") {
        dependsOn(notificationAssistantStubJar)
    }
}
