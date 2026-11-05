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
package io.github.thibaultbee.streampack.core.elements.sources.video.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import io.github.thibaultbee.streampack.core.elements.processing.video.CompositionMode
import io.github.thibaultbee.streampack.core.elements.processing.video.IInputResolver
import io.github.thibaultbee.streampack.core.elements.processing.video.ISurfaceProcessorInternal
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.AbstractPreviewableSource
import io.github.thibaultbee.streampack.core.elements.sources.video.ISurfaceProcessorAwareSource
import io.github.thibaultbee.streampack.core.elements.sources.video.ISurfaceSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraDispatcherProvider
import io.github.thibaultbee.streampack.core.elements.utils.extensions.displayRotation
import io.github.thibaultbee.streampack.core.elements.utils.extensions.rotate
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.core.pipelines.DispatcherProvider.Companion.THREAD_NAME_CAMERA
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A video source that captures video from two cameras concurrently (e.g. front and rear)
 * and composites them into a single frame using OpenGL.
 */
class ConcurrentDualCameraSource internal constructor(
    private val context: Context,
    internal val primaryCameraSource: CameraSource,
    internal val secondaryCameraSource: CameraSource,
    var compositionMode: CompositionMode = CompositionMode.PIP_TOP_RIGHT
) : ICameraSource, ICameraSourceInternal, ISurfaceSourceInternal, ISurfaceProcessorAwareSource, IInputResolver, AbstractPreviewableSource() {

    override val cameraId: String
        get() = primaryCameraSource.cameraId

    val primaryCameraId: String
        get() = primaryCameraSource.cameraId

    val secondaryCameraId: String
        get() = secondaryCameraSource.cameraId

    override val settings: CameraSettings
        get() = primaryCameraSource.settings

    val secondarySettings: CameraSettings
        get() = secondaryCameraSource.settings

    override val timebase
        get() = primaryCameraSource.timebase

    override val infoProviderFlow
        get() = primaryCameraSource.infoProviderFlow

    /**
     * Returns the info provider of the camera feeding [inputSurface].
     */
    override fun getInfoProvider(inputSurface: Surface): ISourceInfoProvider {
        return if (isSecondaryInput(inputSurface)) {
            secondaryCameraSource.infoProviderFlow.value
        } else {
            primaryCameraSource.infoProviderFlow.value
        }
    }

    /**
     * Whether [inputSurface] is the processor input fed by the secondary (PiP) camera.
     */
    override fun isSecondaryInput(inputSurface: Surface) = inputSurface == secondarySurface

    private var surfaceProcessor: ISurfaceProcessorInternal? = null
    private var primarySurface: Surface? = null
    private var secondarySurface: Surface? = null

    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow: StateFlow<Boolean> = _isStreamingFlow.asStateFlow()

    private val _isPreviewingFlow = MutableStateFlow(false)
    override val isPreviewingFlow: StateFlow<Boolean> = _isPreviewingFlow.asStateFlow()

    private var savedConfig: VideoSourceConfig? = null

    /**
     * The preview shows the composited frame rendered by the surface processor, not the raw
     * primary camera frames. It is attached to the processor as an additional output.
     */
    private var previewSurface: Surface? = null
    private var previewOutput: SurfaceOutput? = null
    private var previewSize: Size? = null

    // Cameras must feed the processor while previewing, even when not streaming.
    private val camerasMutex = Mutex()
    private var isStreamRequested = false
    private var isPreviewRequested = false
    private var areCamerasActive = false

    private fun computeSecondaryResolution(primaryResolution: Size, mode: CompositionMode): Size {
        val desired = when (mode) {
            CompositionMode.SIDE_BY_SIDE, CompositionMode.TOP_BOTTOM -> {
                if (primaryResolution.width > 1280) Size(1280, 720) else primaryResolution
            }
            else -> {
                if (primaryResolution.width >= 1920) Size(1280, 720) else Size(854, 480)
            }
        }
        return closestSupportedSize(secondaryCameraSource.cameraId, desired)
    }

    /**
     * Returns [desired] if the camera supports it, otherwise the supported size with the same
     * aspect ratio and the closest area. The camera would otherwise pick an arbitrary size with
     * another aspect ratio and the frame would be distorted.
     */
    private fun closestSupportedSize(cameraId: String, desired: Size): Size {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val sizes = manager.getCameraCharacteristics(cameraId)
                .get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                ?.toList()
                .orEmpty()
            if (sizes.isEmpty() || sizes.contains(desired)) {
                return desired
            }
            val desiredRatio = desired.width.toFloat() / desired.height
            val desiredArea = desired.width * desired.height
            sizes.minWith(
                compareBy<Size> { kotlin.math.abs(it.width.toFloat() / it.height - desiredRatio) }
                    .thenBy { kotlin.math.abs(it.width * it.height - desiredArea) }
            )
        } catch (t: Throwable) {
            Logger.w(TAG, "Failed to query sizes for camera $cameraId: ${t.message}")
            desired
        }
    }

    // The camera may silently pick another stream size if the requested one is unsupported.
    private fun logSupportedSize(name: String, cameraId: String, requested: Size) {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val sizes = manager.getCameraCharacteristics(cameraId)
                .get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            Logger.i(
                TAG,
                "PiP $name camera $cameraId requested=$requested supported=${sizes?.contains(requested)} " +
                        "sizes=${sizes?.joinToString { "${it.width}x${it.height}" }}"
            )
        } catch (t: Throwable) {
            Logger.w(TAG, "Failed to query sizes for camera $cameraId: ${t.message}")
        }
    }

    override fun setSurfaceProcessor(processor: ISurfaceProcessorInternal) {
        this.surfaceProcessor = processor
        processor.compositionMode = compositionMode
        processor.inputResolver = this
        attachPreviewOutput()
    }

    private fun detachPreviewOutput() {
        previewOutput?.let { surfaceProcessor?.removeOutputSurface(it) }
        previewOutput = null
    }

    private fun attachPreviewOutput() {
        val processor = surfaceProcessor ?: return
        val surface = previewSurface ?: return
        val size = previewSize ?: return

        val rotation = context.displayRotation
        if (previewOutput != null &&
            previewOutput?.targetResolution == size &&
            previewOutput?.targetRotation == rotation
        ) {
            return
        }

        // The processor drops its outputs when the input resolution changes: always re-add.
        detachPreviewOutput()
        try {
            val baseProvider = infoProviderFlow.value
            // The composited frame is already upright and sized as the preview: no letterboxing.
            val previewInfoProvider = object : ISourceInfoProvider by baseProvider {
                override fun getSurfaceSize(targetResolution: Size) =
                    targetResolution.rotate(baseProvider.getRelativeRotationDegrees(rotation, false))
            }
            val output = SurfaceOutput(
                targetSurface = surface,
                targetResolution = size,
                targetRotation = rotation,
                isStreaming = { true },
                sourceResolution = size,
                needMirroring = false,
                sourceInfoProvider = previewInfoProvider
            )
            processor.addOutputSurface(output)
            previewOutput = output
        } catch (t: Throwable) {
            Logger.w(TAG, "Failed to attach preview output: ${t.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun updateCamerasState() = camerasMutex.withLock {
        val isNeeded = isStreamRequested || isPreviewRequested
        if (isNeeded && !areCamerasActive) {
            primaryCameraSource.startStream()
            try {
                secondaryCameraSource.startStream()
            } catch (t: Throwable) {
                Logger.e(TAG, "Failed to start secondary camera stream in concurrent mode", t)
            }
            areCamerasActive = true
        } else if (!isNeeded && areCamerasActive) {
            primaryCameraSource.stopStream()
            secondaryCameraSource.stopStream()
            areCamerasActive = false
        }
    }

    override suspend fun getOutput(): Surface? = primarySurface

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun setOutput(surface: Surface) {
        primarySurface = surface
        primaryCameraSource.setOutput(surface)

        val processor = surfaceProcessor
        if (processor != null) {
            processor.inputResolver = this
            val primaryRes = savedConfig?.resolution ?: Size(1920, 1080)
            val secondarySize = computeSecondaryResolution(primaryRes, compositionMode)
            logSupportedSize("primary", primaryCameraSource.cameraId, primaryRes)
            logSupportedSize("secondary", secondaryCameraSource.cameraId, secondarySize)
            val secSurface = processor.createInputSurface(secondarySize, secondaryCameraSource.timebase)
            this.secondarySurface = secSurface
            secondaryCameraSource.setOutput(secSurface)
        } else {
            Logger.w(TAG, "SurfaceProcessor not set on ConcurrentDualCameraSource when setting output")
        }
        attachPreviewOutput()
    }

    @SuppressLint("MissingPermission")
    override suspend fun resetOutputImpl() {
        detachPreviewOutput()
        primaryCameraSource.resetOutput()
        secondaryCameraSource.resetOutput()
        secondarySurface?.let {
            surfaceProcessor?.removeInputSurface(it)
            secondarySurface = null
        }
        primarySurface = null
        clearProcessorReference()
    }

    // The processor outlives this source: it must not keep pointing at a released dual source.
    private fun clearProcessorReference() {
        surfaceProcessor?.let {
            if (it.inputResolver === this) it.inputResolver = null
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun configure(config: VideoSourceConfig) {
        this.savedConfig = config
        primaryCameraSource.configure(config)
        val secResolution = computeSecondaryResolution(config.resolution, compositionMode)
        secondaryCameraSource.configure(config.copy(resolution = secResolution))
    }

    /**
     * The preview is the composited frame, already rotated upright: returns a size with the
     * orientation of [targetSize] instead of a sensor size.
     */
    override fun <T> getPreviewSize(targetSize: Size, targetClass: Class<T>): Size {
        val scale = minOf(1f, MAX_PREVIEW_DIMENSION.toFloat() / max(targetSize.width, targetSize.height))
        val size = Size(
            (targetSize.width * scale).roundToInt().coerceAtLeast(2) and 1.inv(),
            (targetSize.height * scale).roundToInt().coerceAtLeast(2) and 1.inv()
        )
        previewSize = size
        attachPreviewOutput()
        return size
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun startStream() {
        isStreamRequested = true
        updateCamerasState()
        _isStreamingFlow.value = true
    }

    override suspend fun stopStream() {
        isStreamRequested = false
        updateCamerasState()
        _isStreamingFlow.value = false
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun startPreview() {
        isPreviewRequested = true
        try {
            updateCamerasState()
        } catch (t: Throwable) {
            Logger.w(TAG, "Failed to start cameras for preview: ${t.message}")
        }
        _isPreviewingFlow.value = true
    }

    override suspend fun stopPreview() {
        isPreviewRequested = false
        try {
            updateCamerasState()
        } catch (t: Throwable) {
            Logger.w(TAG, "Failed to stop cameras after preview: ${t.message}")
        }
        _isPreviewingFlow.value = false
    }

    override suspend fun hasPreview(): Boolean = previewSurface != null

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun setPreview(surface: Surface) {
        previewSurface = surface
        if (previewSize == null) {
            previewSize = savedConfig?.resolution
        }
        attachPreviewOutput()
    }

    @SuppressLint("MissingPermission")
    override suspend fun resetPreviewImpl() {
        detachPreviewOutput()
        previewSurface = null
    }

    override suspend fun release() {
        isStreamRequested = false
        isPreviewRequested = false
        areCamerasActive = false
        try {
            primaryCameraSource.stopStream()
        } catch (_: Throwable) {}
        try {
            secondaryCameraSource.stopStream()
        } catch (_: Throwable) {}

        resetOutputImpl()

        try {
            primaryCameraSource.release()
        } catch (_: Throwable) {}
        try {
            secondaryCameraSource.release()
        } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "ConcurrentDualCameraSource"
        private const val MAX_PREVIEW_DIMENSION = 1280
    }
}

/**
 * A factory to create a [ConcurrentDualCameraSource].
 *
 * @param primaryCameraId the primary camera ID (e.g. rear)
 * @param secondaryCameraId the secondary camera ID (e.g. front)
 * @param compositionMode the initial composition layout mode
 */
class ConcurrentDualCameraSourceFactory(
    val primaryCameraId: String,
    val secondaryCameraId: String,
    val compositionMode: CompositionMode = CompositionMode.PIP_TOP_RIGHT
) : IVideoSourceInternal.Factory {
    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun create(
        context: Context,
        dispatcherProvider: IVideoDispatcherProvider
    ): IVideoSourceInternal {
        val primaryDispatcherProvider = CameraDispatcherProvider(
            dispatcherProvider.default,
            { dispatcherProvider.createVideoHandlerExecutor("${THREAD_NAME_CAMERA}_pri") },
            { dispatcherProvider.createVideoExecutor(1, "${THREAD_NAME_CAMERA}_pri") }
        )
        val secondaryDispatcherProvider = CameraDispatcherProvider(
            dispatcherProvider.default,
            { dispatcherProvider.createVideoHandlerExecutor("${THREAD_NAME_CAMERA}_sec") },
            { dispatcherProvider.createVideoExecutor(1, "${THREAD_NAME_CAMERA}_sec") }
        )

        val primaryCameraSource = CameraSource(context, primaryCameraId, primaryDispatcherProvider)
        val secondaryCameraSource = CameraSource(context, secondaryCameraId, secondaryDispatcherProvider)

        return ConcurrentDualCameraSource(
            context,
            primaryCameraSource,
            secondaryCameraSource,
            compositionMode
        )
    }

    override fun isSourceEquals(source: IVideoSourceInternal?): Boolean {
        return source is ConcurrentDualCameraSource &&
                source.primaryCameraId == primaryCameraId &&
                source.secondaryCameraId == secondaryCameraId
    }

    override fun toString(): String {
        return "ConcurrentDualCameraSourceFactory(primary=$primaryCameraId, secondary=$secondaryCameraId, mode=$compositionMode)"
    }
}
