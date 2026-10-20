package it.mio.iptv

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Controlla l'ultima release GitHub e propone l'aggiornamento. */
object Updater {
    private const val REPO = "fp-pez8/test-app-android"

    fun check(act: Activity) {
        thread {
            try {
                val c = URL("https://api.github.com/repos/$REPO/releases/latest")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val json = JSONObject(c.inputStream.bufferedReader().readText())
                val remote = json.getString("tag_name").removePrefix("build-").toLongOrNull() ?: return@thread
                val local = act.packageManager.getPackageInfo(act.packageName, 0).longVersionCode
                if (remote <= local) return@thread
                val assets = json.getJSONArray("assets")
                if (assets.length() == 0) return@thread
                val apkUrl = assets.getJSONObject(0).getString("browser_download_url")
                val notes = json.optString("body", "").trim()
                act.runOnUiThread { if (!act.isFinishing) ask(act, remote, notes, apkUrl) }
            } catch (_: Exception) {
            }
        }
    }

    private fun ask(act: Activity, build: Long, notes: String, apkUrl: String) {
        AlertDialog.Builder(act)
            .setTitle("Nuovo aggiornamento")
            .setMessage(if (notes.isNotEmpty()) "Build $build\n\n$notes" else "È disponibile la build $build.")
            .setPositiveButton("Aggiorna") { _, _ -> download(act, apkUrl) }
            .setNegativeButton("Più tardi", null)
            .show()
    }

    private fun download(act: Activity, apkUrl: String) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            // serve il permesso "installa app sconosciute" per PEZ8 (una volta sola)
            act.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))
            )
            return
        }
        @Suppress("DEPRECATION")
        val dlg = ProgressDialog(act).apply {
            setMessage("Scarico l'aggiornamento…")
            setCancelable(false)
            show()
        }
        thread {
            try {
                val dir = File(act.cacheDir, "updates").apply { mkdirs() }
                val f = File(dir, "pez8.apk")
                val c = URL(apkUrl).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                act.runOnUiThread {
                    dlg.dismiss()
                    val uri = FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", f)
                    act.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (_: Exception) {
                act.runOnUiThread {
                    dlg.dismiss()
                    AlertDialog.Builder(act).setMessage("Download non riuscito. Riprova più tardi.")
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }
}
