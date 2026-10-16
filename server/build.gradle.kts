plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
}

application {
    mainClass = "xyz.thewhish.rustopsy.server.MainKt"
}

ktor {
    fatJar {
        archiveFileName = "rustopsy-server.jar"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverCio)
    implementation(libs.ktor.serverAutoHeadResponse)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverRateLimit)
    implementation(libs.ktor.serverForwardedHeader)
    implementation(libs.ktor.serverWebsockets)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serializationJson)
    implementation(libs.logback)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)

    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientWebsockets)
    testImplementation(libs.embeddedPostgres)
    testImplementation(enforcedPlatform(libs.embeddedPostgres.binariesBom))
}
