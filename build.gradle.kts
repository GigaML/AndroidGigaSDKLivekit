plugins {
    id("com.android.application") version "8.6.1" apply false
    id("com.android.library") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    id("com.vanniktech.maven.publish") version "0.34.0" apply false
}

defaultTasks("buildTestPublishToMavenLocal")

tasks.register("buildTestPublishToMavenLocal") {
    group = "publishing"
    description = "Builds the SDK and sample, runs SDK unit tests, and publishes the SDK to Maven Local."

    dependsOn(
        ":gigasdk-livekit:assemble",
        ":sample:assembleDebug",
        ":gigasdk-livekit:testDebugUnitTest",
        ":gigasdk-livekit:publishToMavenLocal",
    )
}

gradle.projectsEvaluated {
    val sdkAssemble = tasks.getByPath(":gigasdk-livekit:assemble")
    val sampleAssembleDebug = tasks.getByPath(":sample:assembleDebug")
    val sdkTestDebugUnitTest = tasks.getByPath(":gigasdk-livekit:testDebugUnitTest")
    val sdkPublishToMavenLocal = tasks.getByPath(":gigasdk-livekit:publishToMavenLocal")

    sdkTestDebugUnitTest.mustRunAfter(sdkAssemble, sampleAssembleDebug)
    sdkPublishToMavenLocal.mustRunAfter(sdkAssemble, sampleAssembleDebug, sdkTestDebugUnitTest)
}
