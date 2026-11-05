package com.pecbox.streambox

import android.app.Application
import com.pecbox.streambox.data.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        session = Session(this)
    }

    companion object {
        lateinit var session: Session
            private set

        /** Escopo que sobrevive às telas (ex.: avisar o servidor que a reprodução parou). */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
