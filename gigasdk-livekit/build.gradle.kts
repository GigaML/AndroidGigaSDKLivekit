plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("maven-publish")
}

group = "ai.giga"
version = "0.1.0"

android {
    namespace = "com.gigaml.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = "gigasdk-livekit"
                version = project.version.toString()

                pom {
                    name.set("Giga SDK for Android (LiveKit)")
                    description.set(
                        "Native Android library for Giga voice and chat agent " +
                            "flows built on top of LiveKit.",
                    )
                    url.set("https://github.com/GigaML/AndroidGigaSDKLivekit")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    scm {
                        url.set("https://github.com/GigaML/AndroidGigaSDKLivekit")
                        connection.set("scm:git:https://github.com/GigaML/AndroidGigaSDKLivekit.git")
                        developerConnection.set("scm:git:ssh://git@github.com/GigaML/AndroidGigaSDKLivekit.git")
                    }
                }
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.livekit:livekit-android:2.24.0")
}
