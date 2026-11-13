package io.github.thibaultbee.streampack.core.elements.sources.video

import android.content.Context
import android.util.Size
import android.view.Surface
import io.github.thibaultbee.streampack.core.elements.processing.video.ISurfaceProcessorInternal
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.utils.extensions.displayRotation
import io.github.thibaultbee.streampack.core.elements.utils.extensions.rotate
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.core.utils.InternalStreamPackApi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Manages a preview [SurfaceOutput] attached to an [ISurfaceProcessorInternal], along with
 * preview size selection and aspect-ratio staleness checks.
 *
 * Used by sources whose preview is rendered through the GL processor rather than a dedicated
 * hardware output (e.g. single-camera and concurrent dual-camera sources).
 */
@InternalStreamPackApi
class SurfaceProcessorPreviewHelper(
    private val context: Context,
    private val infoProvider: () -> ISourceInfoProvider,
    private val isStreaming: () -> Boolean,
    private val needMirroring: () -> Boolean = { infoProvider().isMirror },
    private val maxPreviewDimension: Int = DEFAULT_MAX_PREVIEW_DIMENSION,
    private val configuredInputSize: () -> Size? = { null },
) {
    private val lock = Any()
    private var surfaceProcessor: ISurfaceProcessorInternal? = null
    private var previewSurface: Surface? = null
    private var previewSize: Size? = null
    private var attachment: PreviewAttachment? = null

    fun setSurfaceProcessor(processor: ISurfaceProcessorInternal) {
        synchronized(lock) {
            surfaceProcessor = processor
            attachLocked()
        }
    }

    fun hasPreview(): Boolean = synchronized(lock) { previewSurface != null }

    fun setPreview(
        surface: Surface,
        fallbackPreviewSize: () -> Size? = { computeUprightPreviewSize() }
    ) {
        synchronized(lock) {
            previewSurface = surface
            if (previewSize == null) {
                previewSize = fallbackPreviewSize()
            }
            attachLocked()
        }
    }

    /**
     * Detaches the preview output from the processor and clears the stored preview surface.
     */
    fun resetPreview() {
        synchronized(lock) {
            detachLocked()
            previewSurface = null
        }
    }

    /**
     * Detaches the preview output from the processor while keeping the preview surface intact
     * so a subsequent [setSurfaceProcessor] re-attaches it.
     */
    fun detachOutput() {
        synchronized(lock) {
            detachLocked()
        }
    }

    /**
     * Re-attaches the preview output if one is currently attached (e.g. after source config changes).
     */
    fun refreshIfAttached() {
        synchronized(lock) {
            if (attachment != null) {
                attachLocked()
            }
        }
    }

    /**
     * Attaches the preview output if processor, surface, and size are all ready.
     */
    fun attachIfReady() {
        synchronized(lock) {
            attachLocked()
        }
    }

    /**
     * Computes an upright preview size from [configuredInputSize] (or [fallbackBufferSize]),
     * scaled to [maxPreviewDimension] and [targetSize], and stores it for [setPreview].
     */
    fun selectUprightPreviewSize(
        targetSize: Size? = null,
        fallbackBufferSize: () -> Size? = { null }
    ): Size? {
        val selected = computeUprightPreviewSize(targetSize, fallbackBufferSize) ?: return null
        synchronized(lock) {
            previewSize = selected
        }
        return selected
    }

    /**
     * Scales an already-upright [uprightSize] to [maxPreviewDimension] (and optional [targetSize])
     * with even pixel dimensions, and stores it for [setPreview].
     */
    fun selectPreviewSize(uprightSize: Size, targetSize: Size? = null): Size {
        val selected = scalePreviewSize(uprightSize, targetSize)
        synchronized(lock) {
            previewSize = selected
        }
        return selected
    }

    /**
     * Whether the stored preview size no longer matches the aspect ratio of [configuredInputSize].
     */
    fun needsPreviewRefresh(): Boolean {
        synchronized(lock) {
            if (previewSurface == null) return false
            val size = previewSize ?: return false
            val inputSize = configuredInputSize() ?: return false
            return !hasSameAspectRatio(size, getUprightSize(inputSize))
        }
    }

    fun release() {
        synchronized(lock) {
            detachLocked()
            surfaceProcessor = null
            previewSurface = null
            previewSize = null
        }
    }

    private fun computeUprightPreviewSize(
        targetSize: Size? = null,
        fallbackBufferSize: () -> Size? = { null }
    ): Size? {
        val sourceBufferSize = configuredInputSize() ?: fallbackBufferSize() ?: return null
        return scalePreviewSize(getUprightSize(sourceBufferSize), targetSize)
    }

    private fun getUprightSize(bufferSize: Size): Size =
        bufferSize.rotate(
            infoProvider().getRelativeRotationDegrees(context.displayRotation, false)
        )

    private fun scalePreviewSize(uprightSize: Size, targetSize: Size? = null): Size {
        val capScale = maxPreviewDimension.toFloat() / maxOf(uprightSize.width, uprightSize.height)
        val scale = if (targetSize != null && targetSize.width > 0 && targetSize.height > 0) {
            val viewScale = maxOf(
                targetSize.width.toFloat() / uprightSize.width,
                targetSize.height.toFloat() / uprightSize.height
            )
            minOf(1f, capScale, viewScale)
        } else {
            minOf(1f, capScale)
        }
        return Size(
            (uprightSize.width * scale).roundToInt().coerceAtLeast(2) and 1.inv(),
            (uprightSize.height * scale).roundToInt().coerceAtLeast(2) and 1.inv()
        )
    }

    private fun hasSameAspectRatio(a: Size, b: Size): Boolean {
        val ratioA = a.width.toFloat() / a.height
        val ratioB = b.width.toFloat() / b.height
        // Tolerance absorbs the even-pixel rounding done in scalePreviewSize.
        return abs(ratioA - ratioB) <= ASPECT_RATIO_TOLERANCE * ratioB
    }

    private fun detachLocked() {
        attachment?.let { (processor, output, _) ->
            try {
                processor.removeOutputSurface(output)
            } catch (t: Throwable) {
                Logger.w(TAG, "Failed to detach preview output: ${t.message}")
            }
        }
        attachment = null
    }

    private fun attachLocked() {
        val processor = surfaceProcessor ?: return
        val surface = previewSurface ?: return
        val size = previewSize ?: return

        val rotation = context.displayRotation
        val baseProvider = infoProvider()
        val effectiveInputSize = configuredInputSize()
            ?: size.rotate(baseProvider.getRelativeRotationDegrees(rotation, false))
        val mirroring = needMirroring()

        if (attachment?.matches(
                processor,
                surface,
                size,
                rotation,
                effectiveInputSize,
                mirroring
            ) == true
        ) {
            return
        }

        detachLocked()
        try {
            // Keep source size aligned with the actual buffer (or rotated preview size)
            // so SurfaceOutput preserves aspect ratio without area-only lookup.
            val previewInfoProvider = object : ISourceInfoProvider by baseProvider {
                override fun getSurfaceSize(targetResolution: Size) = effectiveInputSize
            }
            val output = SurfaceOutput(
                targetSurface = surface,
                targetResolution = size,
                targetRotation = rotation,
                isStreaming = isStreaming,
                sourceResolution = effectiveInputSize,
                needMirroring = mirroring,
                sourceInfoProvider = previewInfoProvider
            )
            processor.addOutputSurface(output)
            attachment = PreviewAttachment(processor, output, effectiveInputSize)
        } catch (t: Throwable) {
            Logger.w(TAG, "Failed to attach preview output: ${t.message}")
        }
    }

    private data class PreviewAttachment(
        val processor: ISurfaceProcessorInternal,
        val output: SurfaceOutput,
        val inputSize: Size
    ) {
        fun matches(
            processor: ISurfaceProcessorInternal,
            surface: Surface,
            size: Size,
            rotation: Int,
            inputSize: Size,
            mirroring: Boolean
        ) = this.processor === processor &&
            output.targetSurface === surface &&
            output.targetResolution == size &&
            output.targetRotation == rotation &&
            output.needMirroring == mirroring &&
            this.inputSize == inputSize
    }

    companion object {
        private const val TAG = "SurfaceProcessorPreview"
        const val DEFAULT_MAX_PREVIEW_DIMENSION = 1920
        private const val ASPECT_RATIO_TOLERANCE = 0.01f
    }
}

