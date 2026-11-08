package com.pecbox.streambox

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.pecbox.streambox.data.Notifier
import com.pecbox.streambox.ui.Notices
import com.pecbox.streambox.data.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        session = Session(this)
        Notices.load()
        registerActivityLifecycleCallbacks(foregroundTracker)
    }

    /** O serviço de avisos só roda com o app na tela. */
    private val foregroundTracker = object : ActivityLifecycleCallbacks {
        private var started = 0
        override fun onActivityStarted(a: Activity) { if (started++ == 0) Notifier.start() }
        override fun onActivityStopped(a: Activity) { if (--started == 0) Notifier.stop() }
        override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
        override fun onActivityResumed(a: Activity) = Unit
        override fun onActivityPaused(a: Activity) = Unit
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
        override fun onActivityDestroyed(a: Activity) = Unit
    }

    companion object {
        lateinit var session: Session
            private set

        /** Escopo que sobrevive às telas (ex.: avisar o servidor que a reprodução parou). */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
