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

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "xyz.thewhish.rustopsy.MainKt"
        jvmArgs += listOf("-Xmx256m")

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Rustopsy"
            packageVersion = "1.0.0"
            modules("java.instrument", "jdk.unsupported")

            windows {
                upgradeUuid = "049839ef-4b28-4048-b777-4219b93c5e8a"
                perUserInstall = true
                menu = true
                shortcut = true
            }
        }
    }
}
