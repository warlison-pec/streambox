package com.pecbox.streambox.desktop.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Ligação mínima com o libmpv (API C estável, client.h) via JNA.
 *
 * Linux: pacote libmpv2 (apt install libmpv2).
 * Windows: libmpv-2.dll junto do app (pasta resources do instalador).
 */
@Suppress("FunctionName")
interface LibMpv : Library {
    fun mpv_client_api_version(): Long
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_free(data: Pointer)
    fun mpv_error_string(error: Int): String
    fun mpv_terminate_destroy(ctx: Pointer)

    companion object {
        /** Carrega uma vez; null (com o motivo em [loadError]) se o libmpv não estiver instalado. */
        val instance: LibMpv? by lazy { load() }
        var loadError: String? = null
            private set

        /** Pastas onde a DLL/.so foi procurada além das do sistema (para a mensagem de erro). */
        var searched: List<String> = emptyList()
            private set

        private fun load(): LibMpv? {
            System.setProperty("jna.encoding", "UTF-8")
            // Pasta de recursos do instalador (onde fica a libmpv-2.dll no Windows)
            val dirs = listOfNotNull(
                System.getProperty("compose.application.resources.dir"),
                System.getenv("STREAMBOX_MPV_DIR"),
            ).filter { File(it).isDirectory }
            if (dirs.isNotEmpty()) {
                val old = System.getProperty("jna.library.path")
                System.setProperty("jna.library.path", (dirs + listOfNotNull(old)).joinToString(File.pathSeparator))
            }
            val names = listOf("mpv", "libmpv.so.2", "mpv-2", "libmpv-2", "libmpv.so.1", "mpv-1")
            val errors = mutableListOf<String>()
            for (name in names) {
                try {
                    return Native.load(name, LibMpv::class.java)
                } catch (e: UnsatisfiedLinkError) {
                    errors += "$name: ${e.message?.lineSequence()?.firstOrNull()}"
                }
            }
            searched = dirs
            loadError = errors.joinToString("\n")
            return null
        }
    }
}

/* Códigos de evento do client.h */
object MpvEvent {
    const val NONE = 0
    const val SHUTDOWN = 1
    const val LOG_MESSAGE = 2
    const val START_FILE = 6
    const val END_FILE = 7
    const val FILE_LOADED = 8
    const val PLAYBACK_RESTART = 21
}

/* mpv_end_file_reason */
object MpvEndReason {
    const val EOF = 0
    const val STOP = 2
    const val QUIT = 3
    const val ERROR = 4
    const val REDIRECT = 5
}

/** setlocale da libc (só Linux; no Windows o mpv não exige). */
@Suppress("FunctionName")
private interface LibC : Library {
    fun setlocale(category: Int, locale: String?): Pointer?
}

/**
 * O libmpv se recusa a iniciar se o formato numérico não for "C" (em pt_BR a vírgula é o separador decimal,
 * e o Java aplica o idioma do sistema ao abrir). Só o LC_NUMERIC da libc muda; a interface usa o Locale do Java.
 */
private fun forceCNumericLocale() {
    if (System.getProperty("os.name").orEmpty().startsWith("Windows")) return
    runCatching {
        val libc = Native.load("c", LibC::class.java)
        libc.setlocale(1 /* LC_NUMERIC na glibc */, "C")
    }.onFailure { System.err.println("setlocale(LC_NUMERIC) falhou: ${it.message}") }
}

/** Instância do mpv com chamadas seguras (todas as funções do client API podem ser chamadas de qualquer thread). */
class Mpv(private val lib: LibMpv) {
    val ctx: Pointer = run {
        forceCNumericLocale()
        lib.mpv_create() ?: error(
            "mpv_create falhou (configuração regional numérica: ${System.getenv("LC_NUMERIC") ?: System.getenv("LC_ALL") ?: System.getenv("LANG")})"
        )
    }
    @Volatile var destroyed = false
        private set
    // Evita usar o contexto enquanto outra thread o destrói
    private val lock = ReentrantReadWriteLock()

    fun option(name: String, value: String) {
        val r = lib.mpv_set_option_string(ctx, name, value)
        if (r < 0) System.err.println("mpv: opção $name=$value ignorada (${lib.mpv_error_string(r)})")
    }

    fun initialize() = check(lib.mpv_initialize(ctx) >= 0) { "mpv_initialize falhou" }

    fun command(vararg args: String): Int = lock.read {
        if (destroyed) -1 else lib.mpv_command(ctx, arrayOf(*args, null))
    }

    fun set(name: String, value: String): Int = lock.read {
        if (destroyed) -1 else lib.mpv_set_property_string(ctx, name, value)
    }

    fun get(name: String): String? = lock.read {
        if (destroyed) return null
        val p = lib.mpv_get_property_string(ctx, name) ?: return null
        try { p.getString(0, "UTF-8") } finally { lib.mpv_free(p) }
    }

    fun double(name: String): Double? = get(name)?.toDoubleOrNull()
    fun flag(name: String): Boolean = get(name) == "yes"

    fun requestLog(level: String) = lib.mpv_request_log_messages(ctx, level)

    fun errorString(code: Int): String = lib.mpv_error_string(code)

    /** Espera o próximo evento. Layout de mpv_event: int event_id; int error; uint64 reply_userdata; void* data. */
    fun waitEvent(timeout: Double): Event {
        val p = lib.mpv_wait_event(ctx, timeout)
        val id = p.getInt(0)
        val error = p.getInt(4)
        val data = p.getPointer(16)
        return when (id) {
            MpvEvent.END_FILE -> Event(id, error, endReason = data?.getInt(0), endError = data?.getInt(4))
            MpvEvent.LOG_MESSAGE -> Event(
                id, error,
                log = data?.let {
                    val prefix = it.getPointer(0)?.getString(0, "UTF-8").orEmpty()
                    val level = it.getPointer(Native.POINTER_SIZE.toLong())?.getString(0, "UTF-8").orEmpty()
                    val text = it.getPointer(2L * Native.POINTER_SIZE)?.getString(0, "UTF-8").orEmpty()
                    "[$level] $prefix: ${text.trimEnd()}"
                }
            )
            else -> Event(id, error)
        }
    }

    fun wakeup() = lock.read { if (!destroyed) lib.mpv_wakeup(ctx) }

    /** Só a thread de eventos chama (depois de sair do laço de eventos). */
    fun destroy() = lock.write {
        if (destroyed) return
        destroyed = true
        lib.mpv_terminate_destroy(ctx)
    }

    data class Event(
        val id: Int,
        val error: Int,
        val endReason: Int? = null,
        val endError: Int? = null,
        val log: String? = null,
    )
}
