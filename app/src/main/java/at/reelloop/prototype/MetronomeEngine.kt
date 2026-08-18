package at.reelloop.prototype

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

class MetronomeEngine(context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private var normalId: Int = 0
    private var accentId: Int = 0

    @Volatile
    private var normalLoaded = false

    @Volatile
    private var accentLoaded = false

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                if (sampleId == normalId) normalLoaded = true
                if (sampleId == accentId) accentLoaded = true
            }
        }
        normalId = soundPool.load(context, R.raw.metro_click, 1)
        accentId = soundPool.load(context, R.raw.metro_accent, 1)
    }

    fun click(accent: Boolean) {
        val id = if (accent) accentId else normalId
        val ready = if (accent) accentLoaded else normalLoaded
        if (!ready) return
        soundPool.play(id, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        soundPool.release()
    }
}
