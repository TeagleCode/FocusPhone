package com.teaglecode.focusphone.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Rasterised app icons, kept for the life of the process.
 *
 * getApplicationIcon costs a package-manager round trip and adaptive icons
 * then have to be drawn into a bitmap; doing either during composition is the
 * mistake [AppCatalog] already exists to avoid. Eight icons are loaded once,
 * off the main thread, and every later home press reads the map.
 */
object IconCache {

    /** Icons are drawn at roughly 40dp; 144px covers that on every density here. */
    private const val SIZE_PX = 144

    @Volatile
    private var icons: Map<String, ImageBitmap> = emptyMap()

    /** Whatever is already rasterised, for the first frame. */
    fun snapshot(): Map<String, ImageBitmap> = icons

    /**
     * Loads any of [packages] not already held. Returns the full map, so a
     * caller can assign the result without merging.
     */
    suspend fun load(context: Context, packages: List<String>): Map<String, ImageBitmap> {
        val missing = packages.filterNot { icons.containsKey(it) }
        if (missing.isEmpty()) return icons

        val loaded = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            missing.mapNotNull { pkg ->
                runCatching { pm.getApplicationIcon(pkg) }
                    .getOrNull()
                    ?.let { pkg to it.rasterise() }
            }.toMap()
        }

        // Rebuilt rather than mutated so readers on the main thread only ever
        // see a complete map.
        icons = icons + loaded
        return icons
    }

    /**
     * Adaptive icons report no intrinsic size and must be drawn; a plain
     * BitmapDrawable can be handed over as-is.
     */
    private fun Drawable.rasterise(): ImageBitmap {
        (this as? BitmapDrawable)?.bitmap?.let { return it.asImageBitmap() }
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap.asImageBitmap()
    }
}
