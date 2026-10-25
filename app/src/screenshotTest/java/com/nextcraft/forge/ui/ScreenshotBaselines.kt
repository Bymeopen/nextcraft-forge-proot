package com.nextcraft.forge.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Reference images for the screenshot scenarios. The mode comes from `-PforgeScreenshotMode`:
 *  - render (default): only write the pictures to build/screenshots.
 *  - record: also write them to src/screenshotTest/baselines (commit those files to accept a new look).
 *  - compare: fail when a picture differs from its baseline by more than [ImageDiff.MAX_DIFF_RATIO] of its pixels,
 *    and write build/screenshots/diff-<name>.png with the changed pixels in red.
 * Only the sizes in `forge.screenshot.baselineLabels` are kept as baselines, so the repository stays small.
 */
internal object ScreenshotBaselines {
    private val mode = System.getProperty("forge.screenshot.mode") ?: "render"
    private val labels = (System.getProperty("forge.screenshot.baselineLabels") ?: "6.3in-412x923,land-932x430").split(',').map { it.trim() }
    private val baselineDir = File("src/screenshotTest/baselines")
    private val outputDir = File("build/screenshots")

    fun handle(fileName: String, label: String, bitmap: Bitmap) {
        if (label !in labels) return
        when (mode) {
            "record" -> {
                baselineDir.mkdirs()
                File(baselineDir, fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            "compare" -> compare(fileName, bitmap)
        }
    }

    private fun compare(fileName: String, actual: Bitmap) {
        val file = File(baselineDir, fileName)
        assertTrue("No baseline for $fileName. Run the Screenshots workflow with mode=record and commit the images.", file.isFile)
        val expected = BitmapFactory.decodeFile(file.path)
        assertEquals("Size of $fileName changed", expected.width to expected.height, actual.width to actual.height)
        val a = IntArray(actual.width * actual.height).also { actual.getPixels(it, 0, actual.width, 0, 0, actual.width, actual.height) }
        val e = IntArray(expected.width * expected.height).also { expected.getPixels(it, 0, expected.width, 0, 0, expected.width, expected.height) }
        val different = ImageDiff.countDifferences(a, e)
        val ratio = different.toDouble() / a.size
        if (ratio > ImageDiff.MAX_DIFF_RATIO) {
            val diff = actual.copy(Bitmap.Config.ARGB_8888, true)
            for (i in a.indices) if (ImageDiff.differs(a[i], e[i])) diff.setPixel(i % actual.width, i / actual.width, 0xFFFF0000.toInt())
            outputDir.mkdirs()
            File(outputDir, "diff-$fileName").outputStream().use { diff.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue(
            "%s differs from its baseline in %.2f%% of pixels (limit %.2f%%); see build/screenshots/diff-%s".format(
                fileName, ratio * 100, ImageDiff.MAX_DIFF_RATIO * 100, fileName,
            ),
            ratio <= ImageDiff.MAX_DIFF_RATIO,
        )
    }
}
