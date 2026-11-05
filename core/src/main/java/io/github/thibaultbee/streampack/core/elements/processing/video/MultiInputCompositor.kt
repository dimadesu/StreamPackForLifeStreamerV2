/*
 * Copyright (C) 2026 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thibaultbee.streampack.core.elements.processing.video

import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.Matrix
import android.util.Size
import android.view.Surface
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.logger.Logger
import kotlin.math.abs

/**
 * An input surface of the [DefaultSurfaceProcessor] with its GL texture and buffer size.
 */
internal data class SurfaceInput(
    val surface: Surface,
    val surfaceTexture: SurfaceTexture,
    val textureId: Int,
    val bufferSize: Size
)

/**
 * Draws several inputs (e.g. PiP dual camera) on each output of the [DefaultSurfaceProcessor].
 *
 * Kept out of the processor so that the single input path stays identical to upstream.
 * Must be used on the GL thread.
 */
internal class MultiInputCompositor(private val renderer: OpenGlRenderer) {
    private val lastLoggedGeometry = hashMapOf<Int, String>()

    // Logs only when the geometry of an input changes to avoid spamming every frame.
    private fun logInputGeometry(
        index: Int,
        input: SurfaceInput,
        infoProvider: ISourceInfoProvider,
        relativeRotation: Int,
        sourceWidth: Float,
        sourceHeight: Float,
        destWidth: Float,
        destHeight: Float
    ) {
        val message = "input#$index bufferSize=${input.bufferSize} " +
                "sensorRotation=${infoProvider.rotationDegrees} relativeRotation=$relativeRotation " +
                "mirror=${infoProvider.isMirror} displaySource=${sourceWidth}x$sourceHeight " +
                "(aspect=${sourceWidth / sourceHeight}) dest=${destWidth}x$destHeight " +
                "(aspect=${destWidth / destHeight})"
        if (lastLoggedGeometry[index] != message) {
            lastLoggedGeometry[index] = message
            Logger.i(TAG, "PiP geometry: $message")
        }
    }

    private fun applyAspectCrop(
        matrix: FloatArray,
        sourceWidth: Float,
        sourceHeight: Float,
        destWidth: Float,
        destHeight: Float
    ) {
        if (sourceWidth <= 0f || sourceHeight <= 0f || destWidth <= 0f || destHeight <= 0f) return

        val sourceAspect = sourceWidth / sourceHeight
        val destAspect = destWidth / destHeight

        if (abs(sourceAspect - destAspect) < 0.001f) return

        val scaleX: Float
        val scaleY: Float
        if (sourceAspect > destAspect) {
            scaleX = destAspect / sourceAspect
            scaleY = 1.0f
        } else {
            scaleX = 1.0f
            scaleY = sourceAspect / destAspect
        }

        Matrix.translateM(matrix, 0, 0.5f, 0.5f, 0f)
        Matrix.scaleM(matrix, 0, scaleX, scaleY, 1.0f)
        Matrix.translateM(matrix, 0, -0.5f, -0.5f, 0f)
    }

    /**
     * @param surfaceOutputMatrix receives the matrix used for snapshots
     */
    fun render(
        renderTimestampNs: Long,
        surfaceInputs: List<SurfaceInput>,
        surfaceInputsToMatrixMap: Map<SurfaceTexture, FloatArray>,
        outputs: List<SurfaceOutput>,
        resolver: IInputResolver?,
        compositionMode: CompositionMode,
        isMuted: Boolean,
        surfaceOutputMatrix: FloatArray
    ) {
        // Rects are ordered primary first, secondary (drawn on top) last.
        val orderedInputs = if (resolver != null) {
            surfaceInputs.sortedBy { if (resolver.isSecondaryInput(it.surface)) 1 else 0 }
        } else {
            surfaceInputs
        }
        val targetRects = compositionMode.getNormalizedRects(orderedInputs.size)
        val hasUnmirroredOutput =
            outputs.any { !it.needMirroring }

        outputs.forEach { output ->
            try {
                val transformedInputs = orderedInputs.mapIndexed { index, input ->
                    val rawTransform = surfaceInputsToMatrixMap[input.surfaceTexture] ?: FloatArray(16)
                    val rectNorm = targetRects.getOrElse(index) { RectF(0f, 0f, 1f, 1f) }

                    // Match the provider and buffer size to the actual input surface: the input
                    // order is not guaranteed to match the camera order after a camera switch.
                    val infoProvider = resolver?.getInfoProvider(input.surface)
                        ?: output.sourceInfoProvider

                    val outputMatrix = FloatArray(16)
                    output.updateTransformMatrix(outputMatrix, rawTransform, infoProvider)

                    // Rects are relative to the output viewport, which may be letterboxed.
                    val viewport = output.viewportRect
                    val destWidth = rectNorm.width() * viewport.width()
                    val destHeight = rectNorm.height() * viewport.height()

                    val sourceSize = if (resolver != null) {
                        input.bufferSize
                    } else {
                        infoProvider.getSurfaceSize(output.targetResolution)
                    }
                    val rot = infoProvider.getRelativeRotationDegrees(output.targetRotation, infoProvider.isMirror)
                    val isRotated = (rot % 180) != 0
                    val sourceWidth = if (isRotated) sourceSize.height.toFloat() else sourceSize.width.toFloat()
                    val sourceHeight = if (isRotated) sourceSize.width.toFloat() else sourceSize.height.toFloat()

                    logInputGeometry(
                        index, input, infoProvider, rot, sourceWidth, sourceHeight,
                        destWidth, destHeight
                    )
                    applyAspectCrop(outputMatrix, sourceWidth, sourceHeight, destWidth, destHeight)

                    // Snapshots follow the stream (not mirrored), not the mirrored preview output.
                    if (index == 0 && (!output.needMirroring || !hasUnmirroredOutput)) {
                        outputMatrix.copyInto(surfaceOutputMatrix)
                    }

                    OpenGlRenderer.RenderInput(
                        textureId = input.textureId,
                        textureTransform = outputMatrix,
                        targetRectNorm = rectNorm
                    )
                }

                if (output.isStreaming()) {
                    renderer.renderInputs(
                        renderTimestampNs,
                        transformedInputs,
                        output.targetSurface,
                        isMuted
                    )
                }
            } catch (t: Throwable) {
                Logger.e(TAG, "Error while rendering frame", t)
            }
        }
    }

    private companion object {
        const val TAG = "MultiInputCompositor"
    }
}
