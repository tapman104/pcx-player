package `is`.xyz.mpv

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

class MpvSurfaceView(
    context: Context,
    attrs: AttributeSet?,
    private val engine: MpvEngine
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    /** Secondary constructor for use from Compose [AndroidView] — no AttributeSet available. */
    constructor(context: Context, engine: MpvEngine) : this(context, null, engine)

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceCreated: surface=${holder.surface}")
        engine.onSurfaceCreated(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        engine.onSurfaceChanged(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed")
        engine.onSurfaceDestroyed()
    }

    /**
     * Called when Compose permanently releases this view.
     *
     * Detach from MPV before removing the callback so MPV cannot retain a surface
     * that is no longer managed by this view. This is safe if surfaceDestroyed()
     * has already run because MpvEngine ignores an already-detached surface.
     */
    fun release() {
        Log.d(TAG, "release: notifying engine and removing SurfaceHolder callback")
        engine.onSurfaceDestroyed()
        holder.removeCallback(this)
    }

    companion object {
        private const val TAG = "MpvSurfaceView"
    }
}
