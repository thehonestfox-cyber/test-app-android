package it.mio.iptv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory

class App : Application(), ImageLoaderFactory {

    // Loghi e stemmi restano in cache sul telefono senza essere riverificati ogni pochi minuti:
    // le liste si riempiono subito, senza altre richieste di rete.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .respectCacheHeaders(false)
        .crossfade(false)
        .build()

    private var checked = false

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) {
                    // appena tornato dalle impostazioni del permesso: continua l'aggiornamento
                    if (Updater.resumePending(activity)) {
                        checked = true
                    } else if (!checked) {
                        checked = true
                        Updater.check(activity)
                    }
                }
            }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
