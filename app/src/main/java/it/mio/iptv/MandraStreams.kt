package it.mio.iptv

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Motore automatico dei canali in stile MandraKodi: scarica da solo il catalogo dei canali italiani
 * e ne risolve i flussi al momento della riproduzione (con token/firme aggiornati e fallback multipli),
 * senza bisogno di conoscere o aggiungere playlist M3U a mano.
 *
 * Fonti combinate in automatico:
 *  1. Digitale terrestre gratuito ufficiale (API Sky Free per TV8/Cielo/SkyTG24, HLS Mediaset, IPTV-Italia per Rai,
 *     Sportitalia, SuperTennis, NOVE, DMAX, Motor Trend, LA7…) -> sourceRank = 0
 *  2. Catalogo live Vavoo Italia (mediahubmx-catalog + mediahubmx-resolve / lokke fallback) -> sourceRank = 2
 *  3. Catalogo live Kool.to (canali italiani + resolver dinamico) -> sourceRank = 2
 *  4. Tabella integrata DaddyLive (Sky Sport Uno/Calcio/F1/MotoGP/Tennis/Arena/Max/Basket/251..254, DAZN 1,
 *     Eurosport 1/2, Rai Sport…) pronta subito anche prima del primo download di rete -> sourceRank = 2
 */
object MandraStreams {
    private const val TAG = "MandraStreams"
    private const val SCHEME = "mandra://"
    private const val CACHE_FILE = "mandra_auto_v2.json"
    private const val REFRESH_MS = 15 * 60_000L
    private const val SIG_TTL_MS = 8 * 60_000L

    private const val URL_DTT_M3U =
        "https://raw.githubusercontent.com/Tundrak/IPTV-Italia/main/iptvitaplus.m3u"
    private const val URL_AMSTAFF_FEED = "https://test34344.herokuapp.com/filter.php?numTest=A1A260"
    private const val URL_VAVOO_PING = "https://www.vavoo.tv/api/app/ping"
    private const val URL_VAVOO_CATALOG = "https://vavoo.to/mediahubmx-catalog.json"
    private const val URL_VAVOO_RESOLVE = "https://vavoo.to/mediahubmx-resolve.json"
    private const val URL_LOKKE_PING = "https://www.lokke.app/api/box/ping2"
    private const val URL_KOOL_CHANNELS = "https://vavoo.papadipeppe.workers.dev/channels"
    private const val URL_KOOL_RESOLVE = "https://vavoo.papadipeppe.workers.dev/resolve?url=https://kool.to/live/play/"

    private const val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
    private const val NOW_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    private const val DADDY_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 OPR/130.0.0.0"
    private const val VAVOO_PING_UA =
        "electron-fetch/1.0 electron (+https://github.com/arantes555/electron-fetch)"
    private const val MANDRA_FEED_UA = "MandraKodi2@@2.0.0@@MandraKodi3@@2K1WPN"

    // Vettore per la firma TS di fallback (stesso di VavooResolver in myResolver.py di MandraKodi)
    private const val LOKKE_VEC =
        "9frjpxPjxSNilxJPCJ0XGYs6scej3dW/h/VWlnKUiLSG8IP7mfyDU7NirOlld+VtCKGj03XjetfliDMhIev7wcARo+YTU8KPFuVQP9E2DVXzY2BFo1NhE6qEmPfNDnm74eyl/7iFJ0EETm6XbYyz8IKBkAqPN/Spp3PZ2ulKg3QBSDxcVN4R5zRn7OsgLJ2CNTuWkd/h451lDCp+TtTuvnAEhcQckdsydFhTZCK5IiWrrTIC/d4qDXEd+GtOP4hPdoIuCaNzYfX3lLCwFENC6RZoTBYLrcKVVgbqyQZ7DnLqfLqvf3z0FVUWx9H21liGFpByzdnoxyFkue3NzrFtkRL37xkx9ITucepSYKzUVEfyBh+/3mtzKY26VIRkJFkpf8KVcCRNrTRQn47Wuq4gC7sSwT7eHCAydKSACcUMMdpPSvbvfOmIqeBNA83osX8FPFYUMZsjvYNEE3arbFiGsQlggBKgg1V3oN+5ni3Vjc5InHg/xv476LHDFnNdAJx448ph3DoAiJjr2g4ZTNynfSxdzA68qSuJY8UjyzgDjG0RIMv2h7DlQNjkAXv4k1BrPpfOiOqH67yIarNmkPIwrIV+W9TTV/yRyE1LEgOr4DK8uW2AUtHOPA2gn6P5sgFyi68w55MZBPepddfYTQ+E1N6R/hWnMYPt/i0xSUeMPekX47iucfpFBEv9Uh9zdGiEB+0P3LVMP+q+pbBU4o1NkKyY1V8wH1Wilr0a+q87kEnQ1LWYMMBhaP9yFseGSbYwdeLsX9uR1uPaN+u4woO2g8sw9Y5ze5XMgOVpFCZaut02I5k0U4WPyN5adQjG8sAzxsI3KsV04DEVymj224iqg2Lzz53Xz53Xz9yEy+7/85ILQpJ6llCyqpHLFyHq/kJxYPhDUF755WaHJEaFRPxUqbparNX+mCE9Xzy7Q/KTgAPiRS41FHXXv+7XSPp4cy9jli0BVnYf13Xsp28OGs/D8Nl3NgEn3/eUcMN80JRdsOrV62fnBVMBNf36+LbISdvsFAFr0xyuPGmlIETcFyxJkrGZnhHAxwzsvZ+Uwf8lffBfZFPRrNv+tgeeLpatVcHLHZGeTgWWml6tIHwWUqv2TVJeMkAEL5PPS4Gtbscau5HM+FEjtGS+KClfX1CNKvgYJl7mLDEf5ZYQv5kHaoQ6RcPaR6vUNn02zpq5/X3EPIgUKF0r/0ctmoT84B2J1BKfCbctdFY9br7JSJ6DvUxyde68jB+Il6qNcQwTFj4cNErk4x719Y42NoAnnQYC2/qfL/gAhJl8TKMvBt3Bno+va8ve8E0z8yEuMLUqe8OXLce6nCa+L5LYK1aBdb60BYbMeWk1qmG6Nk9OnYLhzDyrd9iHDd7X95OM6X5wiMVZRn5ebw4askTTc50xmrg4eic2U1w1JpSEjdH/u/hXrWKSMWAxaj34uQnMuWxPZEXoVxzGyuUbroXRfkhzpqmqqqOcypjsWPdq5BOUGL/Riwjm6yMI0x9kbO8+VoQ6RYfjAbxNriZ1cQ+AW1fqEgnRWXmjt4Z1M0ygUBi8w71bDML1YG6UHeC2cJ2CCCxSrfycKQhpSdI1QIuwd2eyIpd4LgwrMiY3xNWreAF+qobNxvE7ypKTISNrz0iYIhU0aKNlcGwYd0FXIRfKVBzSBe4MRK2pGLDNO6ytoHxvJweZ8h1XG8RWc4aB5gTnB7Tjiqym4b64lRdj1DPHJnzD4aqRixpXhzYzWVDN2kONCR5i2quYbnVFN4sSfLiKeOwKX4JdmzpYixNZXjLkG14seS6KR0Wl8Itp5IMIWFpnNokjRH76RYRZAcx0jP0V5/GfNNTi5QsEU98en0SiXHQGXnROiHpRUDXTl8FmJORjwXc0AjrEMuQ2FDJDmAIlKUSLhjbIiKw3iaqp5TVyXuz0ZMYBhnqhcwqULqtFSuIKpaW8FgF8QJfP2frADf4kKZG1bQ99MrRrb2A="

    private class MpdEntry(
        val name: String,
        val s1Url: String,
        val s1Key: String,
        val h1Url: String? = null,
        val h1Key: String? = null,
        val referer: String = "https://www.nowtv.it/",
        val origin: String = "https://www.nowtv.it"
    )

    // Flussi CMAF DASH (.mpd) con ClearKey per tutta la numerazione Sky Sport (inclusi 251..259, Mix, Legend) ed Eurosport
    private val NOW_MPD_CHANNELS = listOf(
        MpdEntry(
            "Sky Sport 251",
            "https://g003-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysport251)/master_2hr-aac.mpd",
            "11151be3428480868250f38fd1b7f372:43c8bf86dc2f6cba8778e7cdff0496cd",
            "https://g006-lin-it-cmaf-prd-ak.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysport251)/master_2hr-all.mpd",
            "11181407f455ebe812f1756485deb7ca:2245122344d8f4d87a780c6ccc3cac22"
        ),
        MpdEntry(
            "Sky Sport 252",
            "https://g003-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysport252)/master_2hr-aac.mpd",
            "1115ef7a71a700a8a00d028fd70dc4be:8742adddc304a5e3aa5376879b02888c",
            "https://g006-lin-it-cmaf-prd-ak.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysport252)/master_2hr-all.mpd",
            "11183e52b0f1b1da52b7fea82e390bfd:470395d6b7a0e7004cbecbf73052d55e"
        ),
        MpdEntry(
            "Sky Sport 253",
            "https://g003-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysport253)/master_2hr-aac.mpd",
            "11151848f262b43a21d7a61bc958c6aa:400f5a392c6c5893643b8df74a0e1eec",
            "https://g006-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysport253)/master_2hr-all.mpd",
            "1118fd92d668c4c91177ccdee292a362:c9eb2b63cf1a8a00dcad6a6359138a56"
        ),
        MpdEntry(
            "Sky Sport 254",
            "https://g003-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysport254)/master_2hr-aac.mpd",
            "11159862c693989cb235f5371dd11fba:25e15b3762a2cfc26d471324f197aff6",
            "https://g006-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysport254)/master_2hr-all.mpd",
            "1118bc79025d7afe4aade4026f773831:c99b86dd3c6abc3d388bb73372077152"
        ),
        MpdEntry(
            "Sky Sport 255",
            "https://g003-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysport255)/master_2hr-aac.mpd",
            "111545473a34f1fe05aae0e10700a7e3:fdee7c55cb25797ea9918644f3db4ef5",
            "https://g006-lin-it-cmaf-prd-ak.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysport255)/master_2hr-all.mpd",
            "11185f7f78307d91e36803f72663b517:d09f471173d6c7a82105abf216cd919c"
        ),
        MpdEntry(
            "Sky Sport 256",
            "https://g003-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysport256)/master_2hr-aac.mpd",
            "1115e30272b48b327a666414728f89c0:e17fd69cf15cd8e9a57b3b2127f7e0bc",
            "https://g006-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysport256)/master_2hr-all.mpd",
            "1118ffed75c4bff557566129f32bf4b3:1337caf53cedb8ca483b9a06fdce7611"
        ),
        MpdEntry(
            "Sky Sport 257",
            "https://g003-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysport257)/master_2hr-aac.mpd",
            "111550e7ba986b365bcc67dd01cdd27e:c292fe7e970841aa795e8832414c5b2e",
            "https://g006-lin-it-cmaf-prd-ak.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysport257)/master_2hr-all.mpd",
            "11182387e418b26faf1cb29e34059998:c3094e97055c3f2542d58ccb6257f820"
        ),
        MpdEntry(
            "Sky Sport 258",
            "https://g003-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysport258)/master_2hr-aac.mpd",
            "1115bb0040c6d04b822da9ae1af8977d:c80ba56fdee7782313a33691e9cc2146",
            "https://g006-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysport258)/master_2hr-all.mpd",
            "1118ff2d07a864e2026664f5b1aeb906:a1368f1b5a3e67d403d3e2b2bf5473d3"
        ),
        MpdEntry(
            "Sky Sport 259",
            "https://g003-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysport259)/master_2hr-aac.mpd",
            "11158627294fcbd5e724e2b521c87aeb:c26bc6e33e8f39dc748f66885935a63f",
            "https://g006-lin-it-cmaf-prd-ak.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysport259)/master_2hr-all.mpd",
            "1118b48f56f299784a495d1c8ffe75d6:d7ee1d1d1d75ce18e379a2751d89bc1f"
        ),
        MpdEntry(
            "Sky Sport Mix",
            "https://g003-lin-it-cmaf-prd-ak.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysportmix)/master_2hr-aac.mpd",
            "111551e04765ca479505e4ea0447b66e:eb88a080518c6b45f0c921cfbc9f92a4"
        ),
        MpdEntry(
            "Sky Sport Legend",
            "https://g003-lin-it-cmaf-prd-ak.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysportlegend)/master_2hr-aac.mpd",
            "1115a083aeeeabbbebe91e83538cf918:c19005a34b2c544cecc54998779af2fd"
        ),
        MpdEntry(
            "Sky Sport Uno",
            "https://g003-lin-it-cmaf-prd-ak.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysportuno)/master_2hr-aac.mpd",
            "1115ca7bae09bbf4364eba27291b88a1:a38624a3b373a79e810f3b20cc295096",
            "https://g003-lin-it-cmaf-prd-ak.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysportuno)/master_2hr-aac.mpd",
            "1118b95f9878e87a0176a2a663bf8fb6:518c6f17f6e37f2d3a0e407a180bf01a"
        ),
        MpdEntry(
            "Sky Sport Calcio",
            "https://g003-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysportcalcio)/master_2hr-aac.mpd",
            "11158d8e6031888c16efac25c601f64b:0f3bfab5a64037ac8711e58aac24a292",
            "https://g006-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysportcalcio)/master_2hr-all.mpd",
            "11185d4276f70e6931dd5809956345ca:2c11ba43fab9050c1226b2f96a660f77"
        ),
        MpdEntry(
            "Sky Sport F1",
            "https://g003-lin-it-cmaf-prd-ak.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysportf1)/master_2hr-aac.mpd",
            "11155522b79e825f8cdfd7967a31da4c:549190d194a655561f5099427a809812",
            "https://g003-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysportf1)/master_2hr-aac.mpd",
            "111897d510ac0fed0d5d050ef54b5bd6:7da85f724587b9d8a85095653421ee10"
        ),
        MpdEntry(
            "Sky Sport MotoGP",
            "https://g003-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysportmotogp)/master_2hr-aac.mpd",
            "11153aff5496be1a206a178ab3c1c078:806eb5923c951bd205257282de834740",
            "https://g003-lin-it-cmaf-prd-ak.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysportmotogp)/master_2hr-aac.mpd",
            "1118a7ba746ae114cee71040247fc26b:d67e3a575afe81ab04733890537e8ab9"
        ),
        MpdEntry(
            "Sky Sport Tennis",
            "https://g003-lin-it-cmaf-prd-ak.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysporttennis)/master_2hr-aac.mpd",
            "111587fedaf11c03f2140b3e53ff5a5b:4192c4108bb887cbd33dc69afabc8096",
            "https://g003-lin-it-cmaf-prd-ak.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysporttennis)/master_2hr-aac.mpd",
            "111862d27c1073698d6ec5bbc328fc4c:777ec90fc3cdc6cec1b963ef14760ef1"
        ),
        MpdEntry(
            "Sky Sport Arena",
            "https://g003-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysportarena)/master_2hr-aac.mpd",
            "1115f90a6831fa7ca058a30383cb0652:6c086c2a0a8de8c1f413e4128246abdd",
            "https://g003-lin-it-cmaf-prd-ak.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysportarena)/master_2hr-aac.mpd",
            "111894dfaecfc64acf114b026b1b06ff:d1a149879a17f13f2d58b7ade5d8a066"
        ),
        MpdEntry(
            "Sky Sport Max",
            "https://g003-lin-it-cmaf-prd-ak.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysportmax)/master_2hr-aac.mpd",
            "11155656191f1c60af13deb6a20f0354:95448b624c83d9a9719342cf1ab3e157",
            "https://g006-lin-it-cmaf-prd-ak.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysportmax)/master_2hr-all.mpd",
            "111821aee8a222fc0fe61a76d62e219d:cf75ce47142284bd131c9c00ba686a7a"
        ),
        MpdEntry(
            "Sky Sport Basket",
            "https://g003-lin-it-cmaf-prd-ak.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysportnba)/master_2hr-aac.mpd",
            "111509ad5d16918190d8ceb1afa3c2c2:5369df3ee38ad965da555846bfa61a37",
            "https://g006-lin-it-cmaf-prd-ak.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysportnba)/master_2hr-all.mpd",
            "111841df5d2386a96b8647e865ee9ed9:e265fe7a61d5e658e62a5b26b37358b9"
        ),
        MpdEntry(
            "Sky Sport Golf",
            "https://g006-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysportgolf)/master_2hr-all.mpd",
            "1118a5b39b61b26e1b5fea40e5b719ad:139dea5a427cecf6ec9d0e989c7c66ac"
        ),
        MpdEntry(
            "Sky Sport 24",
            "https://g003-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_S1/Live/channel(skysport24)/master_2hr-aac.mpd",
            "11151447a65b31d6cb78dd022cb67e8d:1d6be453be1e607c133a28826d509a86",
            "https://g003-lin-it-cmaf-prd-cf.pcdn07.cssott02.com/nowitlin1/Content/CMAF_CTR_H1/Live/channel(skysport24)/master_2hr-all.mpd",
            "1118a5e9911b2b299d939e0d5c0f43b2:11273954891470cedcb840783171dcc1"
        ),
        MpdEntry(
            "Eurosport 1",
            "https://timlivetu0.cb.ticdn.it/Content/DASH/Live/channel%28eurosport1%29/manifest.mpd",
            "46e559c2f9f645ce8d80c7fa20446bdb:597d214d66342ee042bccffeb165e750",
            referer = "https://www.timvision.it/",
            origin = "https://www.timvision.it"
        ),
        MpdEntry(
            "Eurosport 2",
            "https://timlivetu0.cb.ticdn.it/Content/DASH/Live/channel%28eurosport2%29/manifest.mpd",
            "e8372283a523444c84646c4172b1bc0f:969a90e1a1a6424a68fe4b5d776368ec",
            referer = "https://www.timvision.it/",
            origin = "https://www.timvision.it"
        )
    )

    // Canali sportivi e nazionali DaddyLive di riserva, subito disponibili anche senza rete
    private val DADDY_CHANNELS = listOf(
        "Sky Sport Uno" to "461",
        "Sky Sport Calcio" to "870",
        "Sky Sport F1" to "577",
        "Sky Sport MotoGP" to "575",
        "Sky Sport Tennis" to "576",
        "Sky Sport Arena" to "462",
        "Sky Sport Max" to "460",
        "Sky Sport Basket" to "875",
        "Sky Sport Golf" to "574",
        "Sky Sport 24" to "869",
        "Sky Sport 251" to "871",
        "Sky Sport 252" to "872",
        "Sky Sport 253" to "873",
        "Sky Sport 254" to "874",
        "Sky Sport 255" to "875",
        "Sky Sport 256" to "876",
        "DAZN 1" to "877",
        "Eurosport 1" to "878",
        "Eurosport 2" to "879",
        "Rai Sport" to "882",
        "Rai 1" to "850",
        "Rai 2" to "851",
        "Rai 3" to "852",
        "Canale 5" to "853",
        "Italia 1" to "854",
        "LA7" to "855",
        "20 Mediaset" to "857",
        "Sky Uno" to "881"
    )

    private val refreshLock = Any()
    private val sigLock = Any()
    @Volatile private var memCatalog: List<Channel>? = null
    @Volatile private var memFetchedAt = 0L
    @Volatile private var vavooSig: String? = null
    @Volatile private var vavooSigAt = 0L
    private val premiumTokens = ConcurrentHashMap<String, Pair<String, Long>>()

    private val serverSuffixRe = Regex("""\s*\.[a-z0-9]{1,3}$""", RegexOption.IGNORE_CASE)
    private val trailingItRe = Regex("""\s+\b(IT|ITALY)\b$""", RegexOption.IGNORE_CASE)
    private val kodiTagRe = Regex("""\[/?(?:COLOR[^\]]*|B|I|LIGHT|UPPERCASE|LOWERCASE)]""", RegexOption.IGNORE_CASE)
    private val parenTagRe = Regex("""\s*\((?:MPD|D|V|K|BKP|BACKUP|TEST|NEW|2)\)\s*""", RegexOption.IGNORE_CASE)
    private val skyCalcioNumRe = Regex("""\bSKY\s+CALCIO\s+(25[1-9]|[1-9])\b""", RegexOption.IGNORE_CASE)
    private val skyCalcioPlainRe = Regex("""^SKY\s+CALCIO$""", RegexOption.IGNORE_CASE)
    private val plusShiftRe = Regex("""\+(\d{1,2})\b""")
    private val nonAlnumRe = Regex("""[^a-z0-9]+""")
    private val tokRe = Regex("""\d+|\D+""")
    private val expParamRe = Regex("""[?&]exp=(\d{9,12})\b""")
    private val qualityTok = setOf("fhd", "hd", "sd", "uhd", "4k", "hevc", "h265", "h264", "1080p", "720p")
    private val numWords = mapOf("uno" to "1", "due" to "2", "tre" to "3")

    private val italianKoolRe = Regex(
        """\b(RAI|MEDIASET|CANALE ?5|ITALIA ?1|RETE ?4|LA ?7|TV ?8|NOVE|CIELO|DMAX|SPORT ?ITALIA|""" +
            """SUPER ?TENNIS|SKY ?SPORT|SKY ?CINEMA|SKY ?UNO|SKY ?TG|SKY ?ARTE|DAZN|ZONA ?DAZN|EUROSPORT|""" +
            """MOTOR ?TREND|REAL ?TIME|GIALLO|TOP ?CRIME|IRIS|FOCUS|BOING|K2|FRISBEE|CARTOONITO|CINE ?34)\b""",
        RegexOption.IGNORE_CASE
    )
    private val foreignKoolRe = Regex(
        """\b(DE|DEUTSCHLAND|AUSTRIA|BUNDESLIGA|PREMIER|UK|FR|FRANCE|ES|SPAIN|ESPANA|PT|PL|TR|ARAB)\b""",
        RegexOption.IGNORE_CASE
    )

    /** true se l'URL va risolto al volo da [resolve] prima di passarlo a ExoPlayer. */
    fun isDynamic(url: String): Boolean = url.startsWith(SCHEME)

    // ---------- Catalogo (cache su disco + aggiornamento in background) ----------

    /** Restituisce subito i canali già noti (memoria -> disco -> tabella integrata), senza rete. */
    fun cached(cacheDir: File): List<Channel> {
        memCatalog?.let { return it }
        val f = File(cacheDir, CACHE_FILE)
        if (f.exists()) {
            try {
                val parsed = fromCacheJson(JSONArray(f.readText(Charsets.UTF_8)))
                if (parsed.isNotEmpty()) {
                    memCatalog = parsed
                    return parsed
                }
            } catch (_: Exception) {
            }
        }
        return mergeByChannel(builtinFreeDtt() + builtinNowMpd() + builtinDaddy())
    }

    /** Scarica e unisce i cataloghi automatici (al massimo ogni 15 minuti). Da chiamare fuori dal thread UI. */
    fun refresh(cacheDir: File): List<Channel> {
        val now = System.currentTimeMillis()
        memCatalog?.let { if (now - memFetchedAt < REFRESH_MS) return it }
        synchronized(refreshLock) {
            val now2 = System.currentTimeMillis()
            memCatalog?.let { if (now2 - memFetchedAt < REFRESH_MS) return it }

            val pool = Executors.newFixedThreadPool(4)
            val fDtt = pool.submit(Callable { fetchFreeDtt() })
            val fAmstaff = pool.submit(Callable { fetchAmstaffItaly() })
            val fVavoo = pool.submit(Callable { fetchVavooItaly() })
            val fKool = pool.submit(Callable { fetchKoolItaly() })
            val dtt = try { fDtt.get() } catch (_: Exception) { emptyList() }
            val amstaff = try { fAmstaff.get() } catch (_: Exception) { emptyList() }
            val vavoo = try { fVavoo.get() } catch (_: Exception) { emptyList() }
            val kool = try { fKool.get() } catch (_: Exception) { emptyList() }
            pool.shutdown()

            Log.d(
                TAG,
                "Catalogo automatico: DTT=${dtt.size}, Amstaff=${amstaff.size}, Vavoo=${vavoo.size}, Kool=${kool.size}"
            )
            val remoteOk = dtt.isNotEmpty() || amstaff.isNotEmpty() || vavoo.isNotEmpty() || kool.isNotEmpty()
            if (!remoteOk) {
                return cached(cacheDir)
            }
            val merged = mergeByChannel(
                builtinFreeDtt() + dtt + amstaff + vavoo + kool + builtinNowMpd() + builtinDaddy()
            )
            memCatalog = merged
            memFetchedAt = System.currentTimeMillis()
            try {
                File(cacheDir, CACHE_FILE).writeText(toCacheJson(merged).toString(), Charsets.UTF_8)
            } catch (_: Exception) {
            }
            return merged
        }
    }

    private fun builtinFreeDtt(): List<Channel> = listOf(
        Categorizer.build("TV8 HD", "Canali TV", "", "${SCHEME}skytv/7", sourceRank = 0),
        Categorizer.build("Cielo HD", "Canali TV", "", "${SCHEME}skytv/2", sourceRank = 0),
        Categorizer.build("Sky TG24 HD", "Notizie", "", "${SCHEME}skytv/1", sourceRank = 0),
        Categorizer.build(
            "Canale 5 HD", "Canali TV", "",
            "https://live02-seg.msf.cdn.mediaset.net/live/ch-c5/c5-clr.isml/index.m3u8", sourceRank = 0
        ),
        Categorizer.build(
            "Italia 1 HD", "Canali TV", "",
            "https://live02-seg.msf.cdn.mediaset.net/live/ch-i1/i1-clr.isml/index.m3u8", sourceRank = 0
        ),
        Categorizer.build(
            "Rete 4 HD", "Canali TV", "",
            "https://live02-seg.msf.cdn.mediaset.net/live/ch-r4/r4-clr.isml/index.m3u8", sourceRank = 0
        ),
        Categorizer.build(
            "20 Mediaset HD", "Canali TV", "",
            "https://live02-seg.msf.cdn.mediaset.net/live/ch-lb/lb-clr.isml/index.m3u8", sourceRank = 0
        )
    )

    private fun swapNowCdnMirror(url: String): String? {
        return when {
            "-cf.pcdn07.cssott02.com" in url -> url.replace("-cf.pcdn07.cssott02.com", "-ak.pcdn07.cssott02.com")
            "-ak.pcdn07.cssott02.com" in url -> url.replace("-ak.pcdn07.cssott02.com", "-cf.pcdn07.cssott02.com")
            else -> null
        }
    }

    private fun encodeMpdStream(url: String, clearKey: String, referer: String, origin: String): String {
        return if (clearKey.isNotEmpty()) {
            withHeaders(
                url,
                "User-Agent" to NOW_UA,
                "Referer" to referer,
                "Origin" to origin,
                "X-ClearKey" to clearKey
            )
        } else {
            withHeaders(
                url,
                "User-Agent" to NOW_UA,
                "Referer" to referer,
                "Origin" to origin
            )
        }
    }

    private fun builtinNowMpd(): List<Channel> {
        val out = ArrayList<Channel>(NOW_MPD_CHANNELS.size)
        for (e in NOW_MPD_CHANNELS) {
            val combinedKey = listOfNotNull(e.s1Key.takeIf { it.isNotEmpty() }, e.h1Key?.takeIf { it.isNotEmpty() })
                .distinct()
                .joinToString(",")
            val urls = ArrayList<String>(4)
            urls.add(encodeMpdStream(e.s1Url, combinedKey, e.referer, e.origin))
            swapNowCdnMirror(e.s1Url)?.let { mirror ->
                urls.add(encodeMpdStream(mirror, combinedKey, e.referer, e.origin))
            }
            if (!e.h1Url.isNullOrEmpty() && !e.h1Key.isNullOrEmpty()) {
                urls.add(encodeMpdStream(e.h1Url, combinedKey, e.referer, e.origin))
                swapNowCdnMirror(e.h1Url)?.let { mirror ->
                    urls.add(encodeMpdStream(mirror, combinedKey, e.referer, e.origin))
                }
            }
            val ch = Categorizer.build("${e.name} FHD", "Sport", "", urls.first(), sourceRank = 2)
            out.add(ch.copy(fallbacks = urls.drop(1)))
        }
        return out
    }

    private fun builtinDaddy(): List<Channel> =
        DADDY_CHANNELS.map { (name, code) ->
            Categorizer.build("$name HD", "Sport", "", "${SCHEME}daddy/$code", sourceRank = 2)
        }

    private fun fetchFreeDtt(): List<Channel> {
        val text = httpGet(URL_DTT_M3U, mapOf("User-Agent" to CHROME_UA), 10_000) ?: return emptyList()
        val nowSec = System.currentTimeMillis() / 1000L
        return M3U.parse(text, sourceRank = 0).filter { ch ->
            val exp = expParamRe.find(ch.url)?.groupValues?.get(1)?.toLongOrNull()
            exp == null || exp > nowSec + 60L
        }
    }

    private fun cleanCatalogName(raw: String): String {
        var s = kodiTagRe.replace(raw, " ")
        s = parenTagRe.replace(s, " ")
        s = serverSuffixRe.replace(s.trim(), "").trim()
        if (!s.startsWith("BBC", ignoreCase = true)) {
            s = trailingItRe.replace(s, "").trim()
        }
        s = skyCalcioNumRe.replace(s) { m ->
            val n = m.groupValues[1]
            "Sky Sport " + if (n.length == 1) "25$n" else n
        }
        if (skyCalcioPlainRe.matches(s)) {
            s = "Sky Sport Calcio"
        }
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    private fun fetchAmstaffItaly(): List<Channel> {
        val resp = httpGet(URL_AMSTAFF_FEED, mapOf("User-Agent" to MANDRA_FEED_UA), 10_000) ?: return emptyList()
        val out = ArrayList<Channel>()
        try {
            val trimmed = resp.trim()
            if (trimmed.startsWith("{")) collectAmstaffNodes(JSONObject(trimmed), out)
            else if (trimmed.startsWith("[")) collectAmstaffArray(JSONArray(trimmed), out)
        } catch (_: Exception) {
        }
        return out
    }

    private fun collectAmstaffNodes(obj: JSONObject, out: MutableList<Channel>) {
        val title = obj.optString("title", "").trim()
        val myRes = obj.optString("myresolve", "").trim()
        if (title.isNotEmpty() && myRes.startsWith("amstaff@@")) {
            val payload = myRes.removePrefix("amstaff@@").trim()
            val cleaned = cleanCatalogName(title)
            if (
                payload.isNotEmpty() && cleaned.isNotEmpty() &&
                !cleaned.contains("===") && !cleaned.contains("---") &&
                !cleaned.startsWith("INFO", ignoreCase = true) &&
                !cleaned.startsWith("AGGIORNA", ignoreCase = true)
            ) {
                val logo = obj.optString("thumbnail", "").trim().takeIf { it.startsWith("http") }.orEmpty()
                val enc = Base64.encodeToString(
                    payload.toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                )
                out.add(Categorizer.build(cleaned, "", logo, "${SCHEME}amstaff/$enc", sourceRank = 2))
            }
        }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k)
            when (v) {
                is JSONObject -> collectAmstaffNodes(v, out)
                is JSONArray -> collectAmstaffArray(v, out)
            }
        }
    }

    private fun collectAmstaffArray(arr: JSONArray, out: MutableList<Channel>) {
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            when (v) {
                is JSONObject -> collectAmstaffNodes(v, out)
                is JSONArray -> collectAmstaffArray(v, out)
            }
        }
    }

    private fun fetchVavooItaly(): List<Channel> {
        val sig = getVavooSignature(force = false)
        val out = fetchVavooCatalogWithSig(sig)
        if (out.isNotEmpty()) return out
        // Se la firma era scaduta o non accettata, riprova una volta senza firma (Origin/Referer vavoo.to)
        return fetchVavooCatalogWithSig(null)
    }

    private fun fetchVavooCatalogWithSig(sig: String?): List<Channel> {
        val out = ArrayList<Channel>()
        var cursor: Any? = 0
        for (page in 0 until 20) {
            val body = JSONObject()
                .put("language", "de")
                .put("region", "AT")
                .put("catalogId", "iptv")
                .put("id", "iptv")
                .put("adult", false)
                .put("search", "")
                .put("sort", "name")
                .put("filter", JSONObject().put("group", "Italy"))
                .put("cursor", cursor ?: JSONObject.NULL)
                .put("clientVersion", "3.0.2")
                .toString()
            val headers = LinkedHashMap<String, String>().apply {
                put("User-Agent", if (sig != null) "okhttp/4.11.0" else CHROME_UA)
                put("Accept", "application/json")
                put("Origin", "https://vavoo.to")
                put("Referer", "https://vavoo.to/")
                if (!sig.isNullOrEmpty()) put("mediahubmx-signature", sig)
            }
            val resp = httpPostJson(URL_VAVOO_CATALOG, body, headers, 12_000) ?: break
            val json = try { JSONObject(resp) } catch (_: Exception) { break }
            val items = json.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val rawName = it.optString("name", "").trim()
                val rawUrl = it.optString("url", "").trim()
                if (rawName.isEmpty() || rawUrl.isEmpty()) continue
                val cleaned = cleanCatalogName(rawName)
                if (cleaned.isEmpty()) continue
                val enc = Base64.encodeToString(
                    rawUrl.toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                )
                out.add(Categorizer.build(cleaned, "", "", "${SCHEME}vavoo/$enc", sourceRank = 2))
            }
            if (json.isNull("nextCursor")) break
            val next = json.opt("nextCursor")
            val nextStr = next?.toString()?.trim().orEmpty()
            if (next == null || nextStr.isEmpty() || nextStr == "false" || nextStr == "0" || nextStr == "null") break
            cursor = next
        }
        return out
    }

    private fun fetchKoolItaly(): List<Channel> {
        val resp = httpGet(URL_KOOL_CHANNELS, mapOf("User-Agent" to "Mozilla/5.0"), 10_000) ?: return emptyList()
        val arr = try { JSONObject(resp).optJSONArray("items") } catch (_: Exception) { null } ?: return emptyList()
        val out = ArrayList<Channel>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val id = it.optString("id", "").trim()
            val rawName = it.optString("name", "").trim()
            if (id.isEmpty() || rawName.isEmpty()) continue
            val country = (it.optString("country", "") + " " + it.optString("group", "")).trim()
            val isIt = if (country.isNotEmpty()) {
                country.equals("IT", true) || country.contains("ital", true)
            } else {
                !foreignKoolRe.containsMatchIn(rawName) &&
                    (trailingItRe.containsMatchIn(rawName) || italianKoolRe.containsMatchIn(rawName))
            }
            if (!isIt) continue
            val cleaned = cleanCatalogName(rawName)
            if (cleaned.isEmpty()) continue
            out.add(Categorizer.build(cleaned, "", "", "${SCHEME}kool/$id", sourceRank = 2))
        }
        return out
    }

    // ---------- Unione dei doppioni (un solo canale in lista, tutti i server nei fallbacks) ----------

    private fun mergeKey(name: String): String {
        val shifted = plusShiftRe.replace(name) { " plus${it.groupValues[1]} " }
        val tokens = nonAlnumRe.replace(FixtureParsers.fold(shifted), " ").trim().split(' ')
            .filter { it.isNotEmpty() && it !in qualityTok }
            .flatMap { w -> tokRe.findAll(w).map { it.value }.toList() }
            .filter { it.isNotEmpty() }
            .map { numWords[it] ?: it }
        val compact = tokens.joinToString("")
        return if (compact == "20mediaset") "20" else compact
    }

    private fun qualityOrder(q: String) = when (q.uppercase()) {
        "FHD" -> 0; "HD" -> 1; "4K" -> 2; "" -> 3; else -> 4
    }

    private fun mergeByChannel(list: List<Channel>): List<Channel> {
        val groups = LinkedHashMap<String, MutableList<Channel>>()
        for (ch in list) {
            val k = mergeKey(ch.name)
            if (k.isEmpty()) continue
            groups.getOrPut(k) { ArrayList() }.add(ch)
        }
        val out = ArrayList<Channel>(groups.size)
        for ((_, chs) in groups) {
            val sorted = chs.sortedWith(compareBy<Channel>({ it.sourceRank }, { qualityOrder(it.quality) }))
            val best = sorted.first()
            val allUrls = sorted.flatMap { listOf(it.url) + it.fallbacks }.filter { it.isNotEmpty() }.distinct()
            val allLogos = sorted.flatMap { it.logos }.filter { it.isNotEmpty() }.distinct()
            out.add(
                best.copy(
                    logos = allLogos,
                    fallbacks = allUrls.filter { it != best.url }
                )
            )
        }
        return Categorizer.sortChannels(out)
    }

    private fun toCacheJson(list: List<Channel>): JSONArray {
        val arr = JSONArray()
        for (ch in list) {
            arr.put(
                JSONObject()
                    .put("n", ch.name)
                    .put("rn", ch.rawName)
                    .put("q", ch.quality)
                    .put("c", ch.category)
                    .put("u", ch.url)
                    .put("sr", ch.sourceRank)
                    .put("l", JSONArray(ch.logos))
                    .put("fb", JSONArray(ch.fallbacks))
            )
        }
        return arr
    }

    private fun fromCacheJson(arr: JSONArray): List<Channel> {
        val out = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("n", "")
            val u = o.optString("u", "")
            if (n.isEmpty() || u.isEmpty()) continue
            val lg = o.optJSONArray("l") ?: JSONArray()
            val fb = o.optJSONArray("fb") ?: JSONArray()
            out.add(
                Channel(
                    name = n,
                    rawName = o.optString("rn", n),
                    quality = o.optString("q", ""),
                    category = o.optString("c", Categorizer.ALTRI),
                    url = u,
                    logos = List(lg.length()) { lg.optString(it, "") }.filter { it.isNotEmpty() },
                    fallbacks = List(fb.length()) { fb.optString(it, "") }.filter { it.isNotEmpty() },
                    sourceRank = o.optInt("sr", 2)
                )
            )
        }
        return out
    }

    // ---------- Risoluzione dinamica al momento del Play ----------

    /**
     * Risolve un indirizzo `mandra://...` in uno o più flussi HTTP diretti (con intestazioni `|Key=Val`).
     * Da chiamare su un thread in background.
     */
    fun resolve(url: String): List<String> {
        if (!isDynamic(url)) return listOf(url)
        val rest = url.removePrefix(SCHEME)
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash >= rest.length - 1) return emptyList()
        val kind = rest.substring(0, slash)
        val arg = rest.substring(slash + 1)
        return try {
            when (kind) {
                "skytv" -> resolveSkyTv(arg)
                "amstaff" -> resolveAmstaff(arg)
                "vavoo" -> {
                    val rawUrl = String(
                        Base64.decode(arg, Base64.URL_SAFE),
                        Charsets.UTF_8
                    )
                    resolveVavoo(rawUrl)
                }
                "kool" -> resolveKool(arg)
                "daddy" -> resolveDaddy(arg)
                else -> emptyList()
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Errore risoluzione $kind: ${e.message}")
            emptyList()
        }
    }

    private fun withHeaders(baseUrl: String, vararg pairs: Pair<String, String>): String {
        if (pairs.isEmpty()) return baseUrl
        val encoded = pairs.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8")
        }
        return "$baseUrl|$encoded"
    }

    // --- 0) Amstaff Resolver (port di amstaff / amstaffTest da myResolver.py di MandraKodi per MPD ClearKey) ---

    private fun padBase64(s: String): String {
        val clean = s.trim()
        val rem = clean.length % 4
        return if (rem == 0) clean else clean + "=".repeat(4 - rem)
    }

    private fun parseAmstaffClearKey(rawKey: String): String {
        val k = rawKey.trim()
        if (k.isEmpty() || k == "0000" || k == "0:0") return ""
        if (':' in k) {
            return k.replace("{", "").replace("}", "").replace("\"", "").replace(" ", "")
        }
        return try {
            val dec = String(Base64.decode(padBase64(k), Base64.DEFAULT), Charsets.UTF_8)
                .replace("{", "").replace("}", "").replace("\"", "").replace(" ", "").trim()
            if (':' in dec) dec else ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun resolveAmstaff(arg: String): List<String> {
        val payload = String(Base64.decode(arg, Base64.URL_SAFE), Charsets.UTF_8).trim()
        val decoded = if (payload.startsWith("http", ignoreCase = true)) {
            payload
        } else {
            try {
                String(Base64.decode(padBase64(payload), Base64.DEFAULT), Charsets.UTF_8).trim()
            } catch (_: Exception) {
                ""
            }
        }
        if (!decoded.startsWith("http", ignoreCase = true)) return emptyList()
        val parts = decoded.split('|')
        val mpdUrl = parts[0].trim()
        if (!mpdUrl.startsWith("http", ignoreCase = true)) return emptyList()
        val clearKey = parseAmstaffClearKey(parts.getOrNull(1).orEmpty())
        val lower = mpdUrl.lowercase()
        val (referer, origin) = when {
            "ticdn.it" in lower || "timvision" in lower ->
                "https://www.timvision.it/" to "https://www.timvision.it"
            "dazn" in lower ->
                "https://www.dazn.com/" to "https://www.dazn.com"
            "lbatv" in lower || "endeavor" in lower ->
                "https://www.lbatv.com/" to "https://www.lbatv.com"
            "discovery" in lower || "eurosport" in lower ->
                "https://www.discoveryplus.com/" to "https://www.discoveryplus.com"
            else ->
                "https://www.nowtv.it/" to "https://www.nowtv.it"
        }
        val out = ArrayList<String>(2)
        out.add(encodeMpdStream(mpdUrl, clearKey, referer, origin))
        swapNowCdnMirror(mpdUrl)?.let { mirror ->
            out.add(encodeMpdStream(mirror, clearKey, referer, origin))
        }
        return out
    }

    // --- 1) Sky Free API (TV8 = 7, Cielo = 2, Sky TG24 = 1) ---

    private fun resolveSkyTv(id: String): List<String> {
        val out = ArrayList<String>(2)
        val endpoints = listOf(
            "https://apid.sky.it/vdp/v1/getLivestream?id=$id&isMobile=false",
            "https://video.sky.it/api/v1/getLivestream?id=$id&isMobile=false"
        )
        for (ep in endpoints) {
            val resp = httpGet(ep, mapOf("User-Agent" to CHROME_UA), 7_000) ?: continue
            val u = try { JSONObject(resp).optString("streaming_url", "").trim() } catch (_: Exception) { "" }
            if (u.startsWith("http") && u !in out) out.add(u)
        }
        return out
    }

    // --- 2) Vavoo Resolver (identico a VavooResolver di MandraKodi) ---

    private fun getVavooSignature(force: Boolean): String? {
        val now = System.currentTimeMillis()
        if (!force) {
            vavooSig?.let { if (now - vavooSigAt < SIG_TTL_MS) return it }
        }
        synchronized(sigLock) {
            val now2 = System.currentTimeMillis()
            if (!force) {
                vavooSig?.let { if (now2 - vavooSigAt < SIG_TTL_MS) return it }
            }
            val uid = UUID.randomUUID().toString()
            val body = JSONObject()
                .put("token", "")
                .put("reason", "app-focus")
                .put("locale", "de")
                .put("theme", "dark")
                .put(
                    "metadata",
                    JSONObject()
                        .put("device", JSONObject().put("type", "desktop").put("uniqueId", uid))
                        .put(
                            "os",
                            JSONObject().put("name", "win32").put("version", "Windows 10 Pro")
                                .put("abis", JSONArray().put("x64")).put("host", "Lenovo")
                        )
                        .put("app", JSONObject().put("platform", "electron"))
                        .put(
                            "version",
                            JSONObject().put("package", "tv.vavoo.app").put("binary", "1.3.1").put("js", "1.3.1")
                        )
                )
                .put("appFocusTime", 0)
                .put("playerActive", false)
                .put("playDuration", 0)
                .put("devMode", false)
                .put("hasAddon", true)
                .put("castConnected", false)
                .put("package", "tv.vavoo.app")
                .put("version", "1.3.1")
                .put("process", "app")
                .put("firstAppStart", now2 - 86_400_000L)
                .put("lastAppStart", now2)
                .put("ipLocation", JSONObject.NULL)
                .put("adblockEnabled", true)
                .put("migrationApplied", false)
                .put(
                    "proxy",
                    JSONObject().put("supported", JSONArray().put("ss")).put("engine", "Mu")
                        .put("enabled", false).put("autoServer", true)
                )
                .put("iap", JSONObject().put("supported", false).put("error", ""))
                .toString()
            val headers = mapOf(
                "User-Agent" to VAVOO_PING_UA,
                "Accept" to "application/json",
                "Accept-Language" to "de"
            )
            val resp = httpPostJson(URL_VAVOO_PING, body, headers, 8_000) ?: return vavooSig
            val sig = try { JSONObject(resp).optString("addonSig", "").trim() } catch (_: Exception) { "" }
            if (sig.isNotEmpty()) {
                vavooSig = sig
                vavooSigAt = System.currentTimeMillis()
                return sig
            }
            return vavooSig
        }
    }

    private fun resolveVavooOnce(playUrl: String, sig: String): String? {
        val body = JSONObject()
            .put("language", "de")
            .put("region", "AT")
            .put("url", playUrl)
            .put("clientVersion", "3.0.2")
            .toString()
        val headers = mapOf(
            "User-Agent" to "MediaHubMX/2",
            "Accept" to "application/json",
            "mediahubmx-signature" to sig
        )
        val resp = httpPostJson(URL_VAVOO_RESOLVE, body, headers, 8_000)?.trim() ?: return null
        return try {
            if (resp.startsWith("[")) {
                val arr = JSONArray(resp)
                if (arr.length() > 0) arr.optJSONObject(0)?.optString("url", "")?.takeIf { it.startsWith("http") }
                else null
            } else {
                JSONObject(resp).optString("url", "").takeIf { it.startsWith("http") }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveVavooTsFallback(playUrl: String): String? {
        if (playUrl.length <= 12 || !playUrl.contains("vavoo-iptv")) return null
        val form = "vec=" + URLEncoder.encode(LOKKE_VEC, "UTF-8")
        val resp = httpPostForm(URL_LOKKE_PING, form, mapOf("User-Agent" to "MediaHubMX/2"), 7_000) ?: return null
        val signed = try {
            JSONObject(resp).optJSONObject("response")?.optString("signed", "")?.trim()
        } catch (_: Exception) {
            null
        }
        if (signed.isNullOrEmpty()) return null
        val base = playUrl.replace("vavoo-iptv", "live2").dropLast(12)
        return withHeaders("$base.ts?n=1&b=5&vavoo_auth=$signed", "User-Agent" to "VAVOO/2.6")
    }

    private fun resolveVavoo(playUrl: String): List<String> {
        val out = ArrayList<String>(4)
        var resolved: String? = null
        val sig = getVavooSignature(force = false)
        if (!sig.isNullOrEmpty()) {
            resolved = resolveVavooOnce(playUrl, sig)
            if (resolved == null) {
                val freshSig = getVavooSignature(force = true)
                if (!freshSig.isNullOrEmpty() && freshSig != sig) {
                    resolved = resolveVavooOnce(playUrl, freshSig)
                }
            }
        }
        if (resolved != null) {
            out.add(
                withHeaders(
                    resolved,
                    "User-Agent" to "VAVOO/2.6",
                    "Referer" to "https://vavoo.to/",
                    "Origin" to "https://vavoo.to"
                )
            )
            out.add(
                withHeaders(
                    resolved,
                    "User-Agent" to "iPad",
                    "Referer" to "https://vavoo.to/",
                    "Origin" to "https://vavoo.to"
                )
            )
            out.add(resolved)
        } else {
            resolveVavooTsFallback(playUrl)?.let { out.add(it) }
        }
        out.add(
            withHeaders(
                "${playUrl.trimEnd('/')}/index.m3u8",
                "Referer" to "https://vavoo.to/",
                "Origin" to "https://vavoo.to",
                "User-Agent" to CHROME_UA
            )
        )
        return out.distinct()
    }

    // --- 3) Kool.to Resolver ---

    private fun resolveKool(id: String): List<String> {
        val out = ArrayList<String>(3)
        val resp = httpGet(URL_KOOL_RESOLVE + id, mapOf("User-Agent" to "Mozilla/5.0"), 8_000)
        if (resp != null) {
            val u = try { JSONObject(resp).optString("url", "").trim() } catch (_: Exception) { "" }
            if (u.startsWith("http")) {
                out.add(
                    withHeaders(
                        u,
                        "Referer" to "https://www.kool.to/",
                        "Origin" to "https://www.kool.to",
                        "User-Agent" to "ipad"
                    )
                )
                out.add(u)
            }
        }
        out.add(
            withHeaders(
                "https://www.kool.to/play/$id/index.m3u8",
                "Referer" to "https://www.kool.to/",
                "Origin" to "https://www.kool.to",
                "User-Agent" to "ipad"
            )
        )
        out.add(
            withHeaders(
                "https://oha.to/play/$id/index.m3u8",
                "Referer" to "https://oha.to/",
                "Origin" to "https://oha.to",
                "User-Agent" to "ipad"
            )
        )
        return out.distinct()
    }

    // --- 4) DaddyLive Resolver (port di daddyCode + daddyPremium da myResolver.py di MandraKodi) ---

    private val econfigRe = Regex("""window\._econfig\s*=\s*['"]([^'"]{100,})['"]""")
    private val atobRe = Regex("""window\.atob\(['"]([^'"]+)['"]\)""")
    private val srcVarRe = Regex("""(?:const|let|var)\s+SRC\s*=\s*['"](https?://[^'"]+?)['"]""")
    private val m3u8UrlRe = Regex("""https?://[^\s'"<>\\]+\.m3u8[^\s'"<>\\]*""")
    private val iframeRe = Regex("""<iframe[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val premiumEdgeRe = Regex("""^(https?://[^/]+)/(premium\d+)/index\.m3u8$""")

    private fun decodeBarecropEconfig(enc: String): String? {
        return try {
            val s = String(Base64.decode(enc, Base64.DEFAULT), Charsets.ISO_8859_1)
            val n = 4
            val order = intArrayOf(2, 0, 3, 1)
            val chunkLen = (s.length + n - 1) / n
            val arr = Array(n) { "" }
            for (i in 0 until n) {
                val start = minOf(i * chunkLen, s.length)
                val end = minOf((i + 1) * chunkLen, s.length)
                val part = s.substring(start, end)
                if (part.length < 4) return null
                val stripped = part.substring(0, 3) + part.substring(4)
                arr[order[i]] = String(Base64.decode(stripped, Base64.DEFAULT), Charsets.ISO_8859_1)
            }
            val finalJson = String(Base64.decode(arr.joinToString(""), Base64.DEFAULT), Charsets.UTF_8)
            val cfg = JSONObject(finalJson)
            cfg.optString("stream_url", "").ifEmpty { cfg.optString("stream_url_nop2p", "") }
                .trim().takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeDaddyPlayerPage(html: String): String? {
        econfigRe.find(html)?.groupValues?.get(1)?.let { enc ->
            decodeBarecropEconfig(enc)?.let { return it }
        }
        atobRe.find(html)?.groupValues?.get(1)?.let { b64 ->
            try {
                val u = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).trim()
                if (u.startsWith("http")) return u
            } catch (_: Exception) {
            }
        }
        srcVarRe.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.startsWith("http") }?.let { return it }
        return m3u8UrlRe.find(html)?.value
    }

    private fun attachPremiumToken(playlistUrl: String): String {
        val clean = playlistUrl.substringBefore('?')
        val m = premiumEdgeRe.matchEntire(clean) ?: return playlistUrl
        val edge = m.groupValues[1]
        val slug = m.groupValues[2]
        val key = "$edge|$slug"
        val now = System.currentTimeMillis()
        val cached = premiumTokens[key]
        var tok = if (cached != null && cached.second > now) cached.first else null
        if (tok == null) {
            val hdrs = mapOf(
                "User-Agent" to DADDY_UA,
                "Accept" to "*/*",
                "Referer" to "https://dembed.st/",
                "Origin" to "https://dembed.st"
            )
            val resp = httpGet("$edge/api/playlist-token?slug=$slug", hdrs, 5_000)
            if (resp != null) {
                try {
                    val j = JSONObject(resp)
                    val t = j.optString("token", "").trim()
                    if (t.isNotEmpty()) {
                        val ttlSec = j.optDouble("ttl", 120.0).coerceAtLeast(20.0)
                        premiumTokens[key] = t to (now + (ttlSec * 500).toLong())
                        tok = t
                    }
                } catch (_: Exception) {
                }
            }
        }
        if (tok.isNullOrEmpty()) return playlistUrl
        val sep = if ('?' in playlistUrl) "&" else "?"
        return "$playlistUrl${sep}t=${URLEncoder.encode(tok, "UTF-8")}"
    }

    private fun resolveRelative(baseUrl: String, href: String): String = try {
        when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("//") -> URI(baseUrl).scheme + ":" + href
            else -> URI(baseUrl).resolve(href).toString()
        }
    } catch (_: Exception) {
        href
    }

    private fun originOf(url: String): String? = try {
        val u = URI(url)
        if (u.scheme != null && u.host != null) "${u.scheme}://${u.authority}" else null
    } catch (_: Exception) {
        null
    }

    private fun isDefacedManifest(body: String): Boolean {
        val low = body.take(8000).lowercase()
        return "hackerman" in low || "null367" in low || "tiktokcdn" in low
    }

    private fun resolveDaddy(codeIn: String): List<String> {
        val out = ArrayList<String>(3)
        val origins = listOf("https://thedaddy.li", "https://thedaddy.pk", "https://dlhd.st", "https://dlhd.pk")
        val players = if (codeIn == "861") listOf("stream", "cast", "watch", "plus", "player")
        else listOf("cast", "stream", "watch", "plus", "player")
        val seenIframes = HashSet<String>()

        outer@ for (origin in origins) {
            var originReachable = false
            for (pl in players) {
                val pUrl = "$origin/$pl/stream-$codeIn.php"
                val page = httpGet(
                    pUrl,
                    mapOf("User-Agent" to DADDY_UA, "Accept" to "*/*", "Referer" to "$origin/"),
                    5_000
                ) ?: continue
                originReachable = true
                val rawIframe = iframeRe.find(page)?.groupValues?.get(1) ?: continue
                var iframe = resolveRelative(pUrl, rawIframe)
                if (!seenIframes.add(iframe)) continue

                val iframeHtml = httpGet(
                    iframe,
                    mapOf("User-Agent" to DADDY_UA, "Accept" to "*/*", "Referer" to pUrl, "Origin" to origin),
                    5_000
                ) ?: continue
                var cand = decodeDaddyPlayerPage(iframeHtml)
                if (cand == null) {
                    val rawIframe2 = iframeRe.find(iframeHtml)?.groupValues?.get(1)
                    if (rawIframe2 != null) {
                        val iframe2 = resolveRelative(iframe, rawIframe2)
                        if (seenIframes.add(iframe2)) {
                            val html2 = httpGet(
                                iframe2,
                                mapOf("User-Agent" to DADDY_UA, "Accept" to "*/*", "Referer" to iframe),
                                5_000
                            )
                            if (html2 != null) {
                                cand = decodeDaddyPlayerPage(html2)
                                if (cand != null) iframe = iframe2
                            }
                        }
                    }
                }
                if (cand == null) continue
                val tokenized = attachPremiumToken(cand)
                val refOrigin = originOf(iframe) ?: origin
                val master = httpGet(
                    tokenized,
                    mapOf(
                        "User-Agent" to DADDY_UA,
                        "Accept" to "*/*",
                        "Referer" to "$refOrigin/",
                        "Origin" to refOrigin
                    ),
                    5_000
                )
                if (master != null && "#EXTM3U" in master.take(4096) && !isDefacedManifest(master)) {
                    out.add(
                        withHeaders(
                            tokenized,
                            "Referer" to "$refOrigin/",
                            "Origin" to refOrigin,
                            "User-Agent" to DADDY_UA
                        )
                    )
                    break@outer
                }
            }
            if (originReachable && out.isNotEmpty()) break
        }

        // Fallback daddyPremium (dokoplay server_lookup)
        val lookup = httpGet(
            "https://dokoplay.xyz/server_lookup.php?channel_id=premium$codeIn",
            mapOf("User-Agent" to CHROME_UA),
            5_000
        )
        if (lookup != null) {
            val server = try { JSONObject(lookup).optString("server_key", "").trim() } catch (_: Exception) { "" }
            if (server.isNotEmpty()) {
                val link = "https://${server}new.kiko2.ru/$server/premium$codeIn/mono.m3u8"
                out.add(
                    withHeaders(
                        link,
                        "Referer" to "https://dokoplay.xyz/",
                        "Origin" to "https://dokoplay.xyz",
                        "User-Agent" to CHROME_UA
                    )
                )
            }
        }
        return out.distinct()
    }

    // ---------- Utilità HTTP ----------

    private fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun httpPostJson(url: String, jsonBody: String, headers: Map<String, String>, timeoutMs: Int): String? =
        httpPost(url, jsonBody, "application/json; charset=utf-8", headers, timeoutMs)

    private fun httpPostForm(url: String, formBody: String, headers: Map<String, String>, timeoutMs: Int): String? =
        httpPost(url, formBody, "application/x-www-form-urlencoded", headers, timeoutMs)

    private fun httpPost(
        url: String, body: String, contentType: String,
        headers: Map<String, String>, timeoutMs: Int
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("Content-Type", contentType)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
