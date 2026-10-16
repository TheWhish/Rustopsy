import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.jna)

    implementation(libs.compose.uiToolingPreview)
}

val appVersion = providers.gradleProperty("rustopsy.version").get()
val msiVersion = appVersion.substringBefore('-')
require(msiVersion.matches(Regex("""\d+\.\d+\.\d+"""))) { "rustopsy.version must start with X.Y.Z, got $appVersion" }

compose.desktop {
    application {
        mainClass = "xyz.thewhish.rustopsy.MainKt"
        jvmArgs += listOf("-Xmx256m", "-Drustopsy.version=$appVersion")

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Rustopsy"
            packageVersion = msiVersion
            modules("java.instrument", "jdk.unsupported", "java.net.http", "jdk.httpserver")

            windows {
                upgradeUuid = "049839ef-4b28-4048-b777-4219b93c5e8a"
                perUserInstall = true
                menu = true
                shortcut = true
            }
        }
    }
}
