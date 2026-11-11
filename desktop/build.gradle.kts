import java.net.URI
import org.jetbrains.compose.desktop.application.dsl.TargetFormat


/*
 * StreamBox para Windows e Linux (Compose Multiplatform + libmpv).
 *
 * Duas edições, como no APK:
 *   ./gradlew run                     -> PecBox (servidor fixo)
 *   ./gradlew run -Pedicao=comunidade -> Comunidade (qualquer Jellyfin)
 *   ./gradlew packageDeb / packageMsi -Pedicao=...   (o .msi só pode ser gerado no Windows)
 *   ./gradlew baixarLibmpv            -> baixa a libmpv-2.dll (no Windows é automático no run/package)
 */

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.compose") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

val appVersion = "0.1.4"
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
            "com/pecbox/streambox/ui/ChannelGenres.kt",
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
            modules("java.net.http", "java.prefs", "java.xml", "jdk.unsupported")
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

/*
 * Player no Windows: a libmpv-2.dll vem da compilação do projeto shinchiro/mpv-winbuild-cmake (GitHub).
 * Baixada uma vez para resources/windows/, que o Compose põe ao lado do app (run, .msi e pasta portátil).
 */
val libmpvDll = layout.projectDirectory.file("resources/windows/libmpv-2.dll")

val baixarLibmpv by tasks.registering {
    description = "Baixa a libmpv-2.dll (player do Windows) para resources/windows/"
    group = "streambox"
    val destino = libmpvDll.asFile
    onlyIf { !destino.exists() || project.hasProperty("forcarLibmpv") }
    doLast {
        val api = URI("https://api.github.com/repos/shinchiro/mpv-winbuild-cmake/releases/latest").toURL()
        val json = api.openConnection().apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "streambox-build")
            // No GitHub Actions usa o token do workflow (a API sem token tem limite baixo por IP)
            System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
        }.getInputStream().bufferedReader().readText()
        // Pacote de desenvolvimento x86_64 comum (sem "-v3", que exige CPU mais nova)
        val url = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+/mpv-dev-x86_64-\\d{8}-git-[^\"/]+\\.7z)\"")
            .find(json)?.groupValues?.get(1)
            ?: error("Não achei o pacote mpv-dev-x86_64 na última versão do mpv-winbuild-cmake")
        logger.lifecycle("Baixando $url")
        val arquivo = File(temporaryDir, url.substringAfterLast('/'))
        URI(url).toURL().openConnection().apply { setRequestProperty("User-Agent", "streambox-build") }
            .getInputStream().use { input -> arquivo.outputStream().use { input.copyTo(it) } }
        destino.parentFile.mkdirs()
        // O .7z do mpv usa o filtro BCJ2, que bibliotecas Java não leem: usa o 7-Zip.
        val windows = System.getProperty("os.name").startsWith("Windows")
        val extrator: List<String> = if (windows) {
            // 7zr.exe: versão de linha de comando oficial do 7-Zip (sem instalar nada)
            val exe = File(temporaryDir, "7zr.exe")
            if (!exe.exists()) {
                URI("https://www.7-zip.org/a/7zr.exe").toURL().openConnection()
                    .apply { setRequestProperty("User-Agent", "streambox-build") }
                    .getInputStream().use { input -> exe.outputStream().use { input.copyTo(it) } }
            }
            listOf(exe.absolutePath, "e", "-y", "-o${destino.parentFile.absolutePath}", arquivo.absolutePath, "libmpv-2.dll")
        } else {
            val path = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            fun acha(nome: String) = path.map { File(it, nome) }.firstOrNull { it.canExecute() }?.absolutePath
            val seteZip = acha("7z") ?: acha("7za") ?: acha("7zr")
            val bsdtar = acha("bsdtar")
            when {
                seteZip != null -> listOf(seteZip, "e", "-y", "-o${destino.parentFile.absolutePath}", arquivo.absolutePath, "libmpv-2.dll")
                bsdtar != null -> listOf(bsdtar, "-xf", arquivo.absolutePath, "-C", destino.parentFile.absolutePath, "libmpv-2.dll")
                else -> error("Instale o 7-Zip (sudo apt install 7zip) ou o bsdtar (libarchive-tools) para extrair a DLL")
            }
        }
        val proc = ProcessBuilder(extrator).redirectErrorStream(true).start()
        val saida = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "Falha ao extrair a libmpv-2.dll:\n$saida" }
        arquivo.delete()
        check(destino.exists()) { "libmpv-2.dll não estava no pacote $url" }
        logger.lifecycle("libmpv-2.dll pronta em $destino (${destino.length() / 1_048_576} MB)")
    }
}

// No Windows, garante a DLL antes de juntar os recursos do app (run, createDistributable, packageMsi…)
if (System.getProperty("os.name").startsWith("Windows")) {
    tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(baixarLibmpv) }
}
