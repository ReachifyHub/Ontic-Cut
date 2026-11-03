package com.boostlyte.vydra.utils

import android.content.Context
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe cache for transition preview drawable resource IDs and animations.
 * Resolves all identifiers once in background to eliminate UI thread hitching
 * and getIdentifier reflection loops.
 */
object TransitionPreviewCache {

    // Transition type to IntArray of [frame1Res, frame2Res, frame3Res]
    private val frameResCache = ConcurrentHashMap<String, IntArray>()

    // Alias map fixing asset-name mismatches between dialog keys, engine keys, and asset names
    val ALIAS_MAP = mapOf(
        "smoothleft" to listOf("smoothleft", "coverleft"),
        "coverleft" to listOf("coverleft", "smoothleft"),
        "smoothright" to listOf("smoothright", "coverright"),
        "coverright" to listOf("coverright", "smoothright"),
        "distance" to listOf("distance", "zoomin"),
        "zoomin" to listOf("zoomin", "distance"),
        "rectcrop" to listOf("rectcrop", "box"),
        "box" to listOf("box", "rectcrop"),
        "circlecrop" to listOf("circlecrop", "circle"),
        "circle" to listOf("circle", "circlecrop")
    )

    val ALL_KNOWN_TYPES = listOf(
        "fade", "fadeblack", "fadewhite", "dissolve",
        "wipeleft", "wiperight", "wipeup", "wipedown",
        "slideleft", "slideright", "slideup", "slidedown",
        "coverleft", "coverright", "smoothleft", "smoothright",
        "circlecrop", "rectcrop", "zoomin", "distance",
        "pixelize", "hlslice", "vuslice"
    )

    @Volatile
    var isInitialized: Boolean = false
        private set

    /**
     * Resolves ALL preview drawable identifiers once on a background thread.
     */
    fun resolveAll(context: Context): Map<String, IntArray> {
        if (isInitialized) return frameResCache
        synchronized(this) {
            if (isInitialized) return frameResCache
            val res = context.resources
            val pkg = context.packageName

            for (type in ALL_KNOWN_TYPES) {
                val candidates = mutableListOf(type)
                ALIAS_MAP[type]?.let { aliases ->
                    for (alias in aliases) {
                        if (!candidates.contains(alias)) {
                            candidates.add(alias)
                        }
                    }
                }

                for (cand in candidates) {
                    val f1 = res.getIdentifier("trans_preview_${cand}_1", "drawable", pkg)
                    val f2 = res.getIdentifier("trans_preview_${cand}_2", "drawable", pkg)
                    val f3 = res.getIdentifier("trans_preview_${cand}_3", "drawable", pkg)

                    if (f1 != 0 && f2 != 0 && f3 != 0) {
                        val ids = intArrayOf(f1, f2, f3)
                        frameResCache[type] = ids
                        if (cand != type && !frameResCache.containsKey(cand)) {
                            frameResCache[cand] = ids
                        }
                        break
                    }
                }
            }
            isInitialized = true
        }
        return frameResCache
    }

    /**
     * Gets resolved frame resource IDs for a given transition type, checking aliases.
     */
    fun getFrameResIds(type: String): IntArray? {
        val cached = frameResCache[type]
        if (cached != null) return cached

        val aliases = ALIAS_MAP[type] ?: emptyList()
        for (alias in aliases) {
            val aliasCached = frameResCache[alias]
            if (aliasCached != null) {
                frameResCache[type] = aliasCached
                return aliasCached
            }
        }
        return null
    }

    /**
     * Directly inserts pre-resolved frame IDs (useful for unit testing or pre-registration).
     */
    fun registerFrameIds(type: String, frameIds: IntArray) {
        frameResCache[type] = frameIds
    }

    /**
     * Returns true if frame resource IDs are registered or resolved for this type.
     */
    fun hasFrames(type: String): Boolean {
        return getFrameResIds(type) != null
    }

    /**
     * Builds an AnimationDrawable for the specified transition frames.
     */
    fun createAnimation(
        context: Context,
        frameResIds: IntArray,
        startDrawable: Drawable?,
        endDrawable: Drawable?
    ): AnimationDrawable? {
        if (frameResIds.size < 3 || startDrawable == null || endDrawable == null) return null
        val f1 = ContextCompat.getDrawable(context, frameResIds[0]) ?: return null
        val f2 = ContextCompat.getDrawable(context, frameResIds[1]) ?: return null
        val f3 = ContextCompat.getDrawable(context, frameResIds[2]) ?: return null

        return AnimationDrawable().apply {
            isOneShot = false
            addFrame(startDrawable, 600)
            addFrame(f1, 100)
            addFrame(f2, 100)
            addFrame(f3, 100)
            addFrame(endDrawable, 600)
        }
    }

    /**
     * Clears cached IDs (for tests).
     */
    fun clearCacheForTesting() {
        frameResCache.clear()
        isInitialized = false
    }
}
