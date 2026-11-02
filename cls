[1mdiff --git a/app/src/main/java/is/xyz/mpv/MPVLib.kt b/app/src/main/java/is/xyz/mpv/MPVLib.kt[m
[1mindex e821dfe..deefad9 100644[m
[1m--- a/app/src/main/java/is/xyz/mpv/MPVLib.kt[m
[1m+++ b/app/src/main/java/is/xyz/mpv/MPVLib.kt[m
[36m@@ -22,14 +22,10 @@[m [mobject MPVLib {[m
     external fun detachSurface()[m
 [m
     external fun command(cmd: Array<out String>)[m
[31m-    external fun commandNode(cmd: Array<String>): MPVNode?[m
 [m
     external fun setOptionString(name: String, value: String): Int[m
 [m
     external fun grabThumbnail(dimension: Int): Bitmap?[m
[31m-    external fun grabThumbnailFast(file: String, pos: Double, dimension: Int, accurate: Boolean): Bitmap?[m
[31m-    external fun setThumbnailJavaVM(appctx: Context)[m
[31m-    external fun clearThumbnailCache()[m
 [m
     external fun getPropertyInt(property: String): Int?[m
     external fun setPropertyInt(property: String, value: Int)[m
[36m@@ -39,8 +35,6 @@[m [mobject MPVLib {[m
     external fun setPropertyBoolean(property: String, value: Boolean)[m
     external fun getPropertyString(property: String): String?[m
     external fun setPropertyString(property: String, value: String)[m
[31m-    external fun getPropertyNode(property: String): MPVNode?[m
[31m-    external fun setPropertyNode(property: String, node: MPVNode)[m
 [m
     external fun observeProperty(property: String, format: Int)[m
 [m
[36m@@ -60,68 +54,52 @@[m [mobject MPVLib {[m
         }[m
     }[m
 [m
[31m-    // FIX #4: Snapshot observers before iterating to avoid holding lock over callbacks[m
     @JvmStatic[m
     fun eventProperty(property: String, value: Long) {[m
[31m-        val snapshot = synchronized(observers) { observers.toList() }[m
[31m-        for (o in snapshot)[m
[31m-            o.eventProperty(property, value)[m
[32m+[m[32m        synchronized(observers) {[m
[32m+[m[32m            for (o in observers)[m
[32m+[m[32m                o.eventProperty(property, value)[m
[32m+[m[32m        }[m
     }[m
 [m
[31m-    // FIX #4: Snapshot observers before iterating to avoid holding lock over callbacks[m
     @JvmStatic[m
     fun eventProperty(property: String, value: Boolean) {[m
[31m-        val snapshot = synchronized(observers) { observers.toList() }[m
[31m-        for (o in snapshot)[m
[31m-            o.eventProperty(property, value)[m
[32m+[m[32m        synchronized(observers) {[m
[32m+[m[32m            for (o in observers)[m
[32m+[m[32m                o.eventProperty(property, value)[m
[32m+[m[32m        }[m
     }[m
 [m
[31m-    // FIX #4: Snapshot observers before iterating to avoid holding lock over callbacks[m
     @JvmStatic[m
     fun eventProperty(property: String, value: Double) {[m
[31m-        val snapshot = synchronized(observers) { observers.toList() }[m
[31m-        for (o in snapshot)[m
[31m-            o.eventProperty(property, value)[m
[32m+[m[32m        synchronized(observers) {[m
[32m+[m[32m            for (o in observers)[m
[32m+[m[32m                o.eventProperty(property, value)[m
[32m+[m[32m        }[m
     }[m
 [m
[31m-    // FIX #4: Snapshot observers before iterating to avoid holding lock over callbacks[m
     @JvmStatic[m
     fun eventProperty(property: String, value: String) {[m
[31m-        val snapshot = synchronized(observers) { observers.toList() }[m
[31m-        for (o in snapshot)[m
[31m-            o.eventProperty(property, value)[m
[31m-    }[m
[31m-[m
[31m-    // FIX #4: Snapshot observers before iterating to avoid holding lock over callbacks[m
[31m-    @JvmStatic[m
[31m-    fun eventProperty(property: String, value: MPVNode) {[m
[31m-        val snapshot = synchronized(observers) { observers.toList() }[m
[31m-        for (o in snapshot)[m
[31m-            o.eventProperty(property, value)[m
[32m+[m[32m        synchronized(observers) {[m
[32m+[m[32m            for (o in observers)[m
[32m+[m[32m                o.eventProperty(property, value)[m
[32m+[m[32m        }[m
     }[m
 [m
[31m-    // FIX #4: Snapshot observers before iterating to avoid holding lock over callbacks[m
     @JvmStatic[m
     fun eventProperty(property: String) {[m
[31m-        val snapshot = synchronized(observers) { observers.toList() }[m
[31m-        for (o in snapshot)[m
[31m-            o.eventProperty(property)[m
[31m-    }[m
[31m-[m
[31m-    // FIX #4: Snapshot observers before iterating to avoid holding lock over callbacks[m
[31m-    @JvmStatic[m
[31m-    fun event(eventId: Int, data: MPVNode) {[m
[31m-        val snapshot = synchronized(observers) { observers.toList() }[m
[31m-        for (o in snapshot)[m
[31m-            o.event(eventId, data)[m
[32m+[m[32m        synchronized(observers) {[m
[32m+[m[32m            for (o in observers)[m
[32m+[m[32m                o.eventProperty(property)[m
[32m+[m[32m        }[m
     }[m
 [m
[31m-    // FIX #4: Snapshot observers before iterating to avoid holding lock over callbacks[m
     @JvmStatic[m
     fun event(eventId: Int) {[m
[31m-        val snapshot = synchronized(observers) { observers.toList() }[m
[31m-        for (o in snapshot)[m
[31m-            o.event(eventId)[m
[32m+[m[32m        synchronized(observers) {[m
[32m+[m[32m            for (o in observers)[m
[32m+[m[32m                o.event(eventId)[m
[32m+[m[32m        }[m
     }[m
 [m
     private val log_observers = mutableListOf<LogObserver>()[m
[36m@@ -140,25 +118,21 @@[m [mobject MPVLib {[m
         }[m
     }[m
 [m
[31m-    // FIX #4: Snapshot log_observers before iterating to avoid holding lock over callbacks[m
     @JvmStatic[m
     fun logMessage(prefix: String, level: Int, text: String) {[m
[31m-        val snapshot = synchronized(log_observers) { log_observers.toList() }[m
[31m-        for (o in snapshot)[m
[31m-            o.logMessage(prefix, level, text)[m
[32m+[m[32m        synchronized(log_observers) {[m
[32m+[m[32m            for (o in log_observers)[m
[32m+[m[32m                o.logMessage(prefix, level, text)[m
[32m+[m[32m        }[m
     }[m
 [m
     interface EventObserver {[m
[31m-        fun eventProperty(property: String) {}[m
[31m-        fun eventProperty(property: String, value: Long) {}[m
[31m-        fun eventProperty(property: String, value: Boolean) {}[m
[31m-        fun eventProperty(property: String, value: String) {}[m
[31m-        fun eventProperty(property: String, value: Double) {}[m
[31m-        fun eventProperty(property: String, value: MPVNode) {}[m
[31m-        fun event(eventId: Int) {}[m
[31m-        fun event(eventId: Int, data: MPVNode) {[m
[31m-            event(eventId)[m
[31m-        }[m
[32m+[m[32m        fun eventProperty(property: String)[m
[32m+[m[32m        fun eventProperty(property: String, value: Long)[m
[32m+[m[32m        fun eventProperty(property: String, value: Boolean)[m
[32m+[m[32m        fun eventProperty(property: String, value: String)[m
[32m+[m[32m        fun eventProperty(property: String, value: Double)[m
[32m+[m[32m        fun event(eventId: Int)[m
     }[m
 [m
     interface LogObserver {[m
