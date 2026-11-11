import org.jetbrains.compose.desktop.application.dsl.TargetFormat

/*
 * StreamBox para Windows e Linux (Compose Multiplatform + libmpv).
 *
 * Duas edições, como no APK:
 *   ./gradlew run                     -> PecBox (servidor fixo)
 *   ./gradlew run -Pedicao=comunidade -> Comunidade (qualquer Jellyfin)
 *   ./gradlew packageDeb / packageMsi -Pedicao=...   (o .msi só pode ser gerado no Windows)
 */

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.compose") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

val appVersion = "0.1.2"
val edicao = (findProperty("edicao") as String?) ?: "pecbox"

data class Edicao(
    val nome: String,            // nome do app instalado
    val pacote: String,          // nome do pacote (.deb) / pasta de dados
    val servidor: String,
    val indexador: String,
    val seerr: String,
    val travaServidor: Boolean,
    val whatsapp: String,
    val whatsappLabel: String,
    val upgradeUuid: String,     // fixo por edição: o Windows usa para atualizar no lugar
)

val edicoes = mapOf(
    "pecbox" to Edicao(
        nome = "StreamBox", pacote = "streambox",
        servidor = "https://midia.pecbox.com.br/api", indexador = "https://strm.pecbox.com.br/strm-api",
        seerr = "https://seer.pecbox.com.br",
        travaServidor = true, whatsapp = "5593991807963", whatsappLabel = "(93) 99180-7963",
        upgradeUuid = "c8cae80e-ce46-4ba3-b4a0-4989139fca2d",
    ),
    "comunidade" to Edicao(
        nome = "StreamBox Comunidade", pacote = "streambox-comunidade",
        servidor = "", indexador = "", seerr = "", travaServidor = false, whatsapp = "", whatsappLabel = "",
        upgradeUuid = "00482c2e-4874-4e62-8431-37d6ee1ae8e9",
    ),
)
val cfg = edicoes[edicao] ?: error("Edição desconhecida: $edicao (use pecbox ou comunidade)")

// Cada edição compila em uma pasta própria, para uma não sobrescrever a outra.
layout.buildDirectory.set(layout.projectDirectory.dir("build/$edicao"))

/* Equivalente ao BuildConfig do Android, gerado a partir da edição escolhida. */
val gerarEdicao by tasks.registering {
    val dir = layout.buildDirectory.dir("generated/edicao")
    inputs.property("edicao", edicao)
    inputs.property("versao", appVersion)
    inputs.property("config", cfg.toString())
    outputs.dir(dir)
    doLast {
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val f = dir.get().file("com/pecbox/streambox/desktop/BuildConfig.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            """
            |package com.pecbox.streambox.desktop
            |
            |object BuildConfig {
            |    const val EDICAO = ${q(edicao)}
            |    const val APP_NAME = ${q(cfg.nome)}
            |    const val DATA_DIR = ${q(cfg.pacote)}
            |    const val VERSION_NAME = ${q(appVersion)}
            |    const val DEFAULT_SERVER = ${q(cfg.servidor)}
            |    const val DEFAULT_INDEXER = ${q(cfg.indexador)}
            |    const val DEFAULT_SEERR = ${q(cfg.seerr)}
            |    const val LOCK_SERVER = ${cfg.travaServidor}
            |    const val ADMIN_WHATSAPP = ${q(cfg.whatsapp)}
            |    const val ADMIN_WHATSAPP_LABEL = ${q(cfg.whatsappLabel)}
            |}
            |""".trimMargin()
        )
    }
}

kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.srcDir(gerarEdicao)
        // Código sem nada de Android, usado tal e qual pelo app do celular/TV.
        kotlin.srcDir("../app/src/main/java")
        kotlin.include(
            "com/pecbox/streambox/desktop/**",
            "com/pecbox/streambox/data/Models.kt",
            "com/pecbox/streambox/data/Net.kt",
        )
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("net.java.dev.jna:jna:5.15.0")
}

compose.desktop {
    application {
        mainClass = "com.pecbox.streambox.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Djna.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi)
            packageName = cfg.pacote
            packageVersion = appVersion
            description = "Cliente Jellyfin que toca o arquivo original, sem conversão"
            vendor = "PecBox"
            // Bibliotecas nativas que vão junto (ex.: resources/windows/libmpv-2.dll)
            appResourcesRootDir.set(layout.projectDirectory.dir("resources"))
            modules("java.net.http", "java.prefs", "jdk.unsupported")
            linux {
                packageName = cfg.pacote
                menuGroup = "AudioVideo"
                appCategory = "video"
                shortcut = true
                iconFile.set(layout.projectDirectory.file("icons/streambox.png"))
            }
            windows {
                packageName = cfg.nome
                menu = true
                menuGroup = "StreamBox"
                shortcut = true
                dirChooser = true
                upgradeUuid = cfg.upgradeUuid
                iconFile.set(layout.projectDirectory.file("icons/streambox.ico"))
            }
        }
    }
}
