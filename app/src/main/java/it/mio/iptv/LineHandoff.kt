package it.mio.iptv

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Passaggio della linea tra i TUOI dispositivi sulla stessa rete (es. telefono e TV).
 * Quando un dispositivo sta per avviare un canale manda un messaggio in broadcast: l'altro, se sta tenendo
 * la linea, la rilascia e risponde. In questo modo la linea (1 connessione) è sempre usata da uno solo.
 */
class LineHandoff(private val ctx: Context, private val onTaken: () -> Unit) {
    private val id = UUID.randomUUID().toString().take(8)
    private var socket: DatagramSocket? = null
    private var mcast: WifiManager.MulticastLock? = null
    @Volatile private var latch: CountDownLatch? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        try {
            mcast = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("pez8:handoff").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {
        }
        Thread {
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(java.net.InetSocketAddress(PORT))
                }
                socket = s
                val buf = ByteArray(128)
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val parts = String(p.data, 0, p.length).split('|')
                    if (parts.size != 3 || parts[0] != "PEZ8" || parts[2] == id) continue
                    when (parts[1]) {
                        "TAKE" -> {
                            onTaken()
                            val ack = "PEZ8|ACK|$id".toByteArray()
                            try { s.send(DatagramPacket(ack, ack.size, p.address, PORT)) } catch (_: Exception) {}
                        }
                        "ACK" -> latch?.countDown()
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; name = "pez8-handoff" }.start()
    }

    /** Chiede agli altri dispositivi di rilasciare la linea; aspetta la risposta al massimo `waitMs` (da thread di sfondo). */
    fun claim(waitMs: Long) {
        val s = socket ?: return
        val l = CountDownLatch(1)
        latch = l
        try {
            val msg = "PEZ8|TAKE|$id".toByteArray()
            s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), PORT))
            l.await(waitMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        } finally {
            latch = null
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        try { mcast?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
    }

    companion object {
        private const val PORT = 48123
    }
}
