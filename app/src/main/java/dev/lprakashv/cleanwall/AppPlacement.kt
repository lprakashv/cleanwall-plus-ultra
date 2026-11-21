package dev.lprakashv.cleanwall

internal object AppPlacement {
    const val PLAY_STORE = "com.android.vending"

    private val services = listOf(
        "com.netflix.ninja",
        "com.amazon.amazonvideo.livingroom",
        "com.google.android.youtube.tv",
        "in.startv.hotstar",
        "com.sonyliv",
        "com.graymatrix.did",
        "com.crunchyroll.crunchyroid",
        "com.apple.atve.androidtv.appletv",
        "com.mxtech.videoplayer.television",
        "com.heytap.tv.livetv"
    )

    fun belongsInWatch(id: String, videoCategory: Boolean, favorites: Set<String>, utilities: Set<String>): Boolean =
        id != PLAY_STORE && id !in utilities && (id in favorites || id in services || videoCategory)

    fun defaultRank(id: String): Int {
        val index = services.indexOf(id)
        return if (index < 0) Int.MAX_VALUE else index
    }
}
