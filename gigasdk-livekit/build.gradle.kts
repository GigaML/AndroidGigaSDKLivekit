plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("maven-publish")
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

val githubPackagesOwner = providers.gradleProperty("GITHUB_PACKAGES_OWNER")
val githubPackagesRepository = providers.gradleProperty("GITHUB_PACKAGES_REPOSITORY")
val githubPackagesUsername = providers.gradleProperty("gpr.user")
    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
val githubPackagesPassword = providers.gradleProperty("gpr.key")
    .orElse(providers.environmentVariable("GITHUB_TOKEN"))

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

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri(
                "https://maven.pkg.github.com/" +
                    "${githubPackagesOwner.get()}/${githubPackagesRepository.get()}",
            )
            credentials {
                username = githubPackagesUsername.orNull
                password = githubPackagesPassword.orNull
            }
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = providers.gradleProperty("POM_ARTIFACT_ID").get()
                version = project.version.toString()

                pom {
                    name.set(providers.gradleProperty("POM_NAME").get())
                    description.set(providers.gradleProperty("POM_DESCRIPTION").get())
                    inceptionYear.set(providers.gradleProperty("POM_INCEPTION_YEAR").get())
                    url.set(providers.gradleProperty("POM_URL").get())
                    licenses {
                        license {
                            name.set(providers.gradleProperty("POM_LICENSE_NAME").get())
                            url.set(providers.gradleProperty("POM_LICENSE_URL").get())
                            distribution.set(providers.gradleProperty("POM_LICENSE_DIST").get())
                        }
                    }
                    developers {
                        developer {
                            id.set(providers.gradleProperty("POM_DEVELOPER_ID").get())
                            name.set(providers.gradleProperty("POM_DEVELOPER_NAME").get())
                            url.set(providers.gradleProperty("POM_DEVELOPER_URL").get())
                        }
                    }
                    scm {
                        url.set(providers.gradleProperty("POM_SCM_URL").get())
                        connection.set(providers.gradleProperty("POM_SCM_CONNECTION").get())
                        developerConnection.set(providers.gradleProperty("POM_SCM_DEV_CONNECTION").get())
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

    testImplementation("junit:junit:4.13.2")
}
