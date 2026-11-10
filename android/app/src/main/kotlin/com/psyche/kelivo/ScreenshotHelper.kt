package com.psyche.kelivo

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Screen capture for phone control. The image is written to a single
 * overwrite-in-place file in the cache directory and removed on teardown,
 * matching the rule that screen content is never persisted.
 */
object ScreenshotHelper {
    private const val DIR = "phone_control"
    private const val NAME = "shot.jpg"
    private const val MIN_INTERVAL_MS = 350L

    private var lastCapture = 0L
    private val worker = Executors.newSingleThreadExecutor()

    private fun error(code: String, message: String) = JSONObject()
        .put("error", code)
        .put("message", message)

    fun clear(service: AccessibilityService) {
        runCatching { File(service.cacheDir, DIR).deleteRecursively() }
    }

    fun capture(
        service: AccessibilityService,
        args: JSONObject,
        complete: (JSONObject) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            complete(error("SCREENSHOT_UNSUPPORTED", "Screen capture needs Android 11 or later. Use read_screen instead."))
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastCapture < MIN_INTERVAL_MS) {
            complete(error("SCREENSHOT_THROTTLED", "Screen capture is rate limited. Wait a moment and retry."))
            return
        }
        lastCapture = now

        val maxEdge = args.optInt("max_edge", 1024).coerceIn(256, 4096)
        val quality = args.optInt("quality", 70).coerceIn(30, 100)

        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    worker.execute {
                        val reply = try {
                            encode(service, result, maxEdge, quality)
                        } catch (e: Throwable) {
                            clear(service)
                            error("SCREENSHOT_FAILED", e.message ?: "Screen capture failed.")
                        }
                        complete(reply)
                    }
                }

                override fun onFailure(failureCode: Int) {
                    // 3 = taken too recently, 5 = FLAG_SECURE window, others are internal.
                    val reply = when (failureCode) {
                        5 -> error("SCREENSHOT_SECURE_WINDOW", "This screen is protected from capture. Use read_screen and the node tree instead.")
                        3 -> error("SCREENSHOT_THROTTLED", "Screen capture is rate limited. Wait a moment and retry.")
                        else -> error("SCREENSHOT_FAILED", "Screen capture failed (code $failureCode). Use read_screen instead.")
                    }
                    complete(reply)
                }
            },
        )
    }

    private fun encode(
        service: AccessibilityService,
        result: AccessibilityService.ScreenshotResult,
        maxEdge: Int,
        quality: Int,
    ): JSONObject {
        val buffer = result.hardwareBuffer
        val full = try {
            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                ?: return error("SCREENSHOT_FAILED", "Screen capture returned no bitmap.")
        } finally {
            buffer.close()
        }

        val sourceWidth = full.width
        val sourceHeight = full.height
        val longest = maxOf(sourceWidth, sourceHeight)
        val ratio = if (longest > maxEdge) maxEdge.toFloat() / longest else 1f
        val targetWidth = (sourceWidth * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (sourceHeight * ratio).toInt().coerceAtLeast(1)

        val scaled = if (ratio < 1f) {
            Bitmap.createScaledBitmap(full, targetWidth, targetHeight, true).also { full.recycle() }
        } else {
            full
        }

        val directory = File(service.cacheDir, DIR).apply { mkdirs() }
        val file = File(directory, NAME)
        FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        val imageWidth = scaled.width
        val imageHeight = scaled.height
        scaled.recycle()

        return JSONObject()
            .put("path", file.absolutePath)
            .put("width", sourceWidth)
            .put("height", sourceHeight)
            .put("image_width", imageWidth)
            .put("image_height", imageHeight)
            .put("scale", sourceWidth.toFloat() / imageWidth)
            .put("bytes", file.length())
            .put("format", "jpeg")
    }
}
