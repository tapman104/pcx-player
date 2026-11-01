package `is`.xyz.mpv

import android.content.Context

object MpvLifecycleGuard {
    // Lock-ordering rule: any caller that holds a MpvEngine.lock before entering
    // a @Synchronized method here must never acquire MpvEngine.lock again from
    // inside MpvLifecycleGuard. Current call paths (initialize, destroy) all
    // acquire MpvEngine.lock first, then this guard's intrinsic lock — maintain
    // this order in all future call sites to avoid deadlock.
    private var owner: MpvEngine? = null
    internal var destroyCallCount: Int = 0

    @Synchronized
    fun create(engine: MpvEngine, context: Context) {
        if (owner != null) {
            throw IllegalStateException("MPVLib is already owned")
        }
        owner = engine
        try {
            MPVLib.create(context)
        } catch (e: Throwable) {
            owner = null
            throw e
        }
    }

    @Synchronized
    fun destroy(engine: MpvEngine) {
        destroyCallCount++
        if (owner !== engine) {
            throw IllegalStateException("Caller is not the owner of MPVLib")
        }
        // Only release ownership after native destruction succeeds.
        // If destroy throws, ownership is intentionally retained to prevent
        // another engine from acquiring a potentially active native instance.
        MPVLib.destroy()
        owner = null
    }

    @Synchronized
    internal fun resetForTesting() {
        owner = null
        destroyCallCount = 0
    }
}
