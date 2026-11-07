package `is`.xyz.mpv

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

data class MpvOptions(
    val gpuNext: Boolean = false,
    val hardwareDecoding: Boolean = true,
    val audioLang: String? = null,
    val subtitleLang: String? = null,
    val videoScale: String? = null,
    val videoDownscale: String? = null,
    val videoTscale: String? = null,
    val debanding: Debanding = Debanding.NONE,
    val videoSync: String = "audio",
    val interpolation: Boolean = false,
    val fastDecode: Boolean = false,
)

enum class Debanding { NONE, GRADFUN, GPU }

class MpvOptionsConfigurator(context: Context) {
    private val appContext = context.applicationContext

    fun configure(options: MpvOptions): String {
        setOption("profile", "fast")

        val vo = if (options.gpuNext) "gpu-next" else "gpu"
        setOption("vo", vo)
        setOption("hwdec", if (options.hardwareDecoding) HWDECS else "no")

        configureDisplayFps()

        options.audioLang?.let { setOption("alang", it) }
        options.subtitleLang?.let { setOption("slang", it) }
        options.videoScale?.let { setOption("scale", it) }
        options.videoDownscale?.let { setOption("dscale", it) }
        options.videoTscale?.let { setOption("tscale", it) }

        when (options.debanding) {
            Debanding.GRADFUN -> setOption("vf", "gradfun=radius=12")
            Debanding.GPU -> setOption("deband", "yes")
            Debanding.NONE -> {}
        }

        setOption("video-sync", options.videoSync)
        if (options.interpolation) setOption("interpolation", "yes")
        if (options.fastDecode) {
            setOption("vd-lavc-fast", "yes")
            setOption("vd-lavc-skiploopfilter", "nonkey")
        }

        setOption("gpu-context", "android")
        setOption("opengl-es", "yes")
        setOption("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        setOption("ao", "audiotrack,opensles")
        setOption("audio-buffer", "0.5")
        setOption("audio-set-media-role", "yes")
        setOption("tls-verify", "yes")
        setOption("tls-ca-file", "${appContext.filesDir.path}/cacert.pem")
        setOption("input-default-bindings", "yes")
        setOption("flatten-editions", "yes")
        configureDemuxerCache()

        return vo
    }

    fun configurePostInit() {
        setOption("save-position-on-quit", "no")
    }

    private fun configureDisplayFps() {
        try {
            val rate = ContextCompat.getDisplayOrDefault(appContext).mode.refreshRate
            if (rate > 0f) setOption("display-fps-override", rate.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read display refresh rate", e)
        }
    }

    private fun configureDemuxerCache() {
        val megs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) 64 else 32
        val bytes = (megs * 1024 * 1024).toString()
        setOption("demuxer-max-bytes", bytes)
        setOption("demuxer-max-back-bytes", bytes)
    }

    private fun setOption(name: String, value: String) {
        val result = MPVLib.setOptionString(name, value)
        if (result < 0) Log.e(TAG, "Failed to set MPV option: $name=$value (error=$result)")
    }

    companion object {
        private const val TAG = "MpvOptionsConfigurator"
        private const val HWDECS = "mediacodec,mediacodec-copy"
    }
}

