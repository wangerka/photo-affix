/*
 * Licensed under Apache-2.0
 *
 * Designed and developed by Aidan Follestad (@afollestad)
 */
package com.afollestad.photoaffix.engine.subengines

import android.graphics.Bitmap
import android.graphics.Bitmap.DENSITY_NONE
import android.graphics.Canvas
import android.graphics.Color.TRANSPARENT
import android.graphics.Paint
import android.graphics.Rect
import com.afollestad.photoaffix.engine.EngineOwner
import com.afollestad.photoaffix.engine.bitmaps.BitmapIterator
import com.afollestad.photoaffix.engine.bitmaps.BitmapManipulator
import com.afollestad.photoaffix.prefs.BgFillColor
import com.afollestad.photoaffix.prefs.ImageSpacingHorizontal
import com.afollestad.photoaffix.prefs.ImageSpacingVertical
import com.afollestad.photoaffix.prefs.ScalePriority
import com.afollestad.photoaffix.prefs.StackHorizontally
import com.afollestad.photoaffix.utilities.DpConverter
import com.afollestad.photoaffix.utilities.ext.safeRecycle
import com.afollestad.photoaffix.utilities.ext.toRoundedInt
import com.afollestad.photoaffix.utilities.qualifiers.IoDispatcher
import com.afollestad.photoaffix.utilities.qualifiers.MainDispatcher
import com.afollestad.rxkprefs.Pref
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

internal typealias CanvasCreator = (Bitmap) -> Canvas
internal typealias PaintCreator = () -> Paint
internal typealias RectCreator = (left: Int, top: Int, right: Int, bottom: Int) -> Rect

/** @author Aidan Follestad (afollestad) */
interface StitchEngine {

  fun setup(
    bitmapIterator: BitmapIterator,
    engineOwner: EngineOwner
  )

  suspend fun stitch(
    selectedScale: Double,
    resultWidth: Int,
    resultHeight: Int,
    format: Bitmap.CompressFormat,
    quality: Int
  ): ProcessingResult
}

class RealStitchEngine @Inject constructor(
  private val dpConverter: DpConverter,
  private val bitmapManipulator: BitmapManipulator,
  @StackHorizontally private val stackHorizontallyPref: Pref<Boolean>,
  @ScalePriority private val scalePriorityPref: Pref<Boolean>,
  @ImageSpacingVertical private val spacingVerticalPref: Pref<Int>,
  @ImageSpacingHorizontal private val spacingHorizontalPref: Pref<Int>,
  @BgFillColor private val bgFillColorPref: Pref<Int>,
  @MainDispatcher private val mainContext: CoroutineContext,
  @IoDispatcher private val ioContext: CoroutineContext
) : StitchEngine {

  private var canvasCreator: CanvasCreator = { Canvas(it) }
  private var paintCreator: PaintCreator = {
    Paint().apply {
      isFilterBitmap = true
      isAntiAlias = true
      isDither = true
    }
  }
  private var rectCreator: RectCreator = { l, t, r, b ->
    Rect(l, t, r, b)
  }

  private lateinit var bitmapIterator: BitmapIterator
  private lateinit var engineOwner: EngineOwner

  override fun setup(
    bitmapIterator: BitmapIterator,
    engineOwner: EngineOwner
  ) {
    this.bitmapIterator = bitmapIterator
    this.engineOwner = engineOwner
  }

  override suspend fun stitch(
    selectedScale: Double,
    resultWidth: Int,
    resultHeight: Int,
    format: Bitmap.CompressFormat,
    quality: Int
  ): ProcessingResult {
    val horizontalOrientation = stackHorizontallyPref.get()
    return if (horizontalOrientation) {
      stitchHorizontally(
          selectedScale,
          resultWidth,
          resultHeight,
          format,
          quality
      )
    } else {
      stitchVertically(
          selectedScale,
          resultWidth,
          resultHeight,
          format,
          quality
      )
    }
  }

  private suspend fun stitchHorizontally(
    selectedScale: Double,
    resultWidth: Int,
    resultHeight: Int,
    format: Bitmap.CompressFormat,
    quality: Int
  ): ProcessingResult {
    val result = bitmapManipulator.createEmptyBitmap(resultWidth, resultHeight)
    val spacingHorizontal = (spacingHorizontalPref.get().dp() *
        selectedScale).toInt()

    val resultCanvas = canvasCreator(result)
    val paint = paintCreator()

    val bgFillColor = bgFillColorPref.get()
    if (bgFillColor != TRANSPARENT) {
      // Fill the canvas (blank image) with the user's selected background fill color
      resultCanvas.drawColor(bgFillColor)
    }

    withContext(mainContext) { engineOwner.showContentLoading(true) }

    val processingResult = GlobalScope.async(ioContext) {
      // Used to set destination dimensions when drawn onto the canvas, e.g. when padding is used
      var processedCount = 0
      val scalingPriority = scalePriorityPref.get()

      var currentX = 0
      bitmapIterator.reset()

      try {
        for (options in bitmapIterator) {
          processedCount++

          val width = options.outWidth
          val height = options.outHeight
          val ratio = width.toFloat() / height.toFloat()

          var scaledWidth = (width * selectedScale).toRoundedInt()
          var scaledHeight = (height * selectedScale).toRoundedInt()

          if (scalingPriority) {
            // Scale up to largest height, fill total height
            if (scaledHeight < resultHeight) {
              scaledHeight = resultHeight
              scaledWidth = (scaledHeight.toFloat() * ratio).toRoundedInt()
            }
          } else {
            // Scale down to smallest height, fill total height
            if (scaledHeight > resultHeight) {
              scaledHeight = resultHeight
              scaledWidth = (scaledHeight.toFloat() * ratio).toRoundedInt()
            }
          }

          // Right is left plus width of the current image
          val right = currentX + scaledWidth
          val dstRect = rectCreator(
              currentX,
              0,
              right,
              scaledHeight
          )

          options.inJustDecodeBounds = false
          options.inSampleSize = (dstRect.bottom - dstRect.top) / options.outHeight

          val bm = bitmapIterator.currentBitmap()
          try {
            bm.density = DENSITY_NONE
            resultCanvas.drawBitmap(bm, null, dstRect, paint)
          } finally {
            bm.safeRecycle()
          }

          currentX = dstRect.right + spacingHorizontal
        }
      } catch (e: Exception) {
        withContext(mainContext) { engineOwner.showErrorDialog(e) }
        bitmapIterator.reset()
        return@async ProcessingResult(
            processedCount = 0
        )
      }

      ProcessingResult(
          processedCount = processedCount,
          output = result,
          format = format,
          quality = quality
      )
    }

    return processingResult.await()
  }

  private suspend fun stitchVertically(
    selectedScale: Double,
    resultWidth: Int,
    resultHeight: Int,
    format: Bitmap.CompressFormat,
    quality: Int
  ): ProcessingResult {
    val result = bitmapManipulator.createEmptyBitmap(resultWidth, resultHeight)
    val spacingVertical = (spacingVerticalPref.get().dp() *
        selectedScale).toInt()

    val resultCanvas = canvasCreator(result)
    val paint = paintCreator()

    val bgFillColor = bgFillColorPref.get()
    if (bgFillColor != TRANSPARENT) {
      // Fill the canvas (blank image) with the user's selected background fill color
      resultCanvas.drawColor(bgFillColor)
    }

    withContext(mainContext) { engineOwner.showContentLoading(true) }

    val processingResult = GlobalScope.async(ioContext) {
      // Used to set destination dimensions when drawn onto the canvas, e.g. when padding is used
      var processedCount = 0
      val scalingPriority = scalePriorityPref.get()

      var currentY = 0
      bitmapIterator.reset()

      try {
        for (options in bitmapIterator) {
          processedCount++

          val width = options.outWidth
          val height = options.outHeight
          val ratio = height.toFloat() / width.toFloat()

          var scaledWidth = (width * selectedScale).toRoundedInt()
          var scaledHeight = (height * selectedScale).toRoundedInt()

          if (scalingPriority) {
            // Scale up to largest width, fill total width
            if (scaledWidth < resultWidth) {
              scaledWidth = resultWidth
              scaledHeight = (scaledWidth.toFloat() * ratio).toRoundedInt()
            }
          } else {
            // Scale down to smallest width, fill total width
            if (scaledWidth > resultWidth) {
              scaledWidth = resultWidth
              scaledHeight = (scaledWidth.toFloat() * ratio).toRoundedInt()
            }
          }

          // Bottom is top plus height of the current image
          val bottom = currentY + scaledHeight
          val dstRect = rectCreator(0, currentY, scaledWidth, bottom)

          options.inJustDecodeBounds = false
          options.inSampleSize = (dstRect.right - dstRect.left) / options.outWidth

          val bm = bitmapIterator.currentBitmap()
          try {
            bm.density = DENSITY_NONE
            resultCanvas.drawBitmap(bm, null, dstRect, paint)
          } finally {
            bm.safeRecycle()
          }

          currentY = dstRect.bottom + spacingVertical
        }
      } catch (e: Exception) {
        withContext(mainContext) { engineOwner.showErrorDialog(e) }
        bitmapIterator.reset()
        return@async ProcessingResult(
            processedCount = 0
        )
      }

      ProcessingResult(
          processedCount = processedCount,
          output = result,
          format = format,
          quality = quality
      )
    }

    return processingResult.await()
  }

  @TestOnly internal fun setPaintCreator(creator: PaintCreator) {
    this.paintCreator = creator
  }

  @TestOnly internal fun setCanvasCreator(creator: CanvasCreator) {
    this.canvasCreator = creator
  }

  @TestOnly internal fun setRectCreator(creator: RectCreator) {
    this.rectCreator = creator
  }

  private fun Int.dp() = dpConverter.toDp(this).toInt()
}
