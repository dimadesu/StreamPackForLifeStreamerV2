/*
 * Copyright (C) 2021 Thibault B.
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
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import io.github.thibaultbee.streampack.core.elements.processing.video.ISurfaceProcessorInternal
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.AbstractPreviewableSource
import io.github.thibaultbee.streampack.core.elements.sources.video.ISurfaceProcessorAwareSource
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.controllers.CameraController
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.cameraManager
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.cameras
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isFpsSupported
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraDispatcherProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraSizes
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraSurface
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CaptureRequestWithTargetsBuilder
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CaptureResultListener
import io.github.thibaultbee.streampack.core.elements.utils.extensions.displayRotation
import io.github.thibaultbee.streampack.core.elements.utils.extensions.rotate
import io.github.thibaultbee.streampack.core.elements.utils.time.Timebase
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.core.utils.InternalStreamPackApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.reflect.KMutableProperty0

/**
 * Camera source implementation.
 *
 * Based on Camera2 API.
 */
@OptIn(InternalStreamPackApi::class)
internal class CameraSource(
    private val context: Context,
    override val cameraId: String,
    dispatcherProvider: CameraDispatcherProvider,
) : ICameraSourceInternal, ICameraSource, ISurfaceProcessorAwareSource, AbstractPreviewableSource() {
    private val defaultDispatcher = dispatcherProvider.default
    private val coroutineScope = CoroutineScope(defaultDispatcher)

    private val manager = context.cameraManager
    private val characteristics = manager.getCameraCharacteristics(cameraId)

    private val controller = CameraController(
        manager,
        characteristics,
        dispatcherProvider,
        cameraId,
        onCaptureRequestBuilder = {
            defaultCaptureRequest(this)
        }
    )

    override val settings by lazy {
        CameraSettings(
            coroutineScope,
            characteristics,
            controller
        )
    }

    override val timebase =
        if (characteristics[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE] == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
            Timebase.REALTIME
        } else {
            Timebase.UPTIME
        }


    override val infoProviderFlow = MutableStateFlow(CameraInfoProvider(manager, cameraId))

    // States
    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    private val _isPreviewingFlow = MutableStateFlow(false)
    override val isPreviewingFlow = _isPreviewingFlow.asStateFlow()

    @Volatile
    private var surfaceProcessor: ISurfaceProcessorInternal? = null
    private var attachedProcessor: ISurfaceProcessorInternal? = null
    private var attachedInputResolution: Size? = null

    private val previewLock = Any()
    private var previewSurface: Surface? = null
    private var previewSize: Size? = null
    private var previewOutput: SurfaceOutput? = null

    @Volatile
    private var savedConfig: VideoSourceConfig? = null

    // Protected by streamMutex
    private var isStreamRequested = false
    private var isPreviewRequested = false
    private var isExpectingSessionClose = false
    private val streamMutex = Mutex()

    init {
        val deviceCameras = manager.cameras
        require(deviceCameras.contains(cameraId)) {
            "Camera $cameraId is not available. Available cameras: ${
                deviceCameras.joinToString(", ")
            }"
        }

        coroutineScope.launch {
            var wasActive = false
            controller.isActiveFlow.collect { isAvailable ->
                streamMutex.withLock {
                    if (isAvailable) {
                        isExpectingSessionClose = false
                    } else if (wasActive) {
                        if (isExpectingSessionClose) {
                            isExpectingSessionClose = false
                        } else {
                            isStreamRequested = false
                            isPreviewRequested = false
                            _isStreamingFlow.emit(false)
                            _isPreviewingFlow.emit(false)
                        }
                    }
                    wasActive = isAvailable
                }
            }
        }
    }

    private fun defaultCaptureRequest(
        captureRequest: CaptureRequestWithTargetsBuilder
    ) {
        if (settings.focus.availableAutoModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            captureRequest.set(
                CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
        }
    }

    override fun setSurfaceProcessor(processor: ISurfaceProcessorInternal) {
        synchronized(previewLock) {
            if (attachedProcessor != null && attachedProcessor !== processor) {
                detachPreviewOutputLocked()
            }
            surfaceProcessor = processor
            attachPreviewOutputLocked()
        }
    }

    private fun detachPreviewOutputLocked() {
        previewOutput?.let { output ->
            try {
                (attachedProcessor ?: surfaceProcessor)?.removeOutputSurface(output)
            } catch (t: Throwable) {
                Logger.w(TAG, "Failed to detach preview output: ${t.message}")
            }
        }
        previewOutput = null
        attachedProcessor = null
        attachedInputResolution = null
    }

    private fun attachPreviewOutputLocked() {
        val processor = surfaceProcessor ?: return
        val surface = previewSurface ?: return
        val size = previewSize ?: return

        val rotation = context.displayRotation
        val baseProvider = infoProviderFlow.value
        val inputSize = getConfiguredInputSize()
        val effectiveInputSize = inputSize
            ?: size.rotate(baseProvider.getRelativeRotationDegrees(rotation, false))

        if (previewOutput != null &&
            attachedProcessor === processor &&
            previewOutput?.targetSurface == surface &&
            previewOutput?.targetResolution == size &&
            previewOutput?.targetRotation == rotation &&
            attachedInputResolution == inputSize
        ) {
            return
        }

        detachPreviewOutputLocked()
        try {
            // Keep source size aligned with the actual camera buffer (or rotated preview size
            // before configure) so SurfaceOutput preserves aspect ratio without area-only lookup.
            val previewInfoProvider = object : ISourceInfoProvider by baseProvider {
                override fun getSurfaceSize(targetResolution: Size) = effectiveInputSize
            }
            val output = SurfaceOutput(
                targetSurface = surface,
                targetResolution = size,
                targetRotation = rotation,
                isStreaming = { _isPreviewingFlow.value },
                sourceResolution = effectiveInputSize,
                // Mirror front camera for selfie preview only; the encoded stream stays unmirrored.
                needMirroring = baseProvider.isMirror,
                sourceInfoProvider = previewInfoProvider
            )
            processor.addOutputSurface(output)
            previewOutput = output
            attachedProcessor = processor
            attachedInputResolution = inputSize
        } catch (t: Throwable) {
            Logger.w(TAG, "Failed to attach preview output: ${t.message}")
        }
    }

    private fun getConfiguredInputSize(): Size? =
        savedConfig?.let { infoProviderFlow.value.getSurfaceSize(it.resolution) }

    private inline fun computePreviewSize(
        targetSize: Size? = null,
        fallbackBufferSize: () -> Size? = { null }
    ): Size? {
        val baseProvider = infoProviderFlow.value
        val relRot = baseProvider.getRelativeRotationDegrees(context.displayRotation, false)
        val sourceBufferSize = getConfiguredInputSize() ?: fallbackBufferSize() ?: return null
        return scalePreviewSize(sourceBufferSize.rotate(relRot), targetSize)
    }

    private fun scalePreviewSize(uprightSize: Size, targetSize: Size? = null): Size {
        val capScale = MAX_PREVIEW_DIMENSION.toFloat() / maxOf(uprightSize.width, uprightSize.height)
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

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun syncStreamTargetUnsafe() {
        val isNeeded = isStreamRequested || isPreviewRequested
        if (isNeeded) {
            if (controller.hasOutput(STREAM_NAME)) {
                controller.addTarget(STREAM_NAME)
            }
        } else {
            executeIfCameraPermission {
                controller.removeTarget(STREAM_NAME)
            }
        }
    }

    override suspend fun hasPreview() = withContext(defaultDispatcher) {
        synchronized(previewLock) {
            previewSurface != null
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun setPreview(surface: Surface) {
        withContext(defaultDispatcher) {
            if (isPreviewingFlow.value) {
                Logger.w(TAG, "Trying to set preview while previewing")
            }
            synchronized(previewLock) {
                previewSurface = surface
                if (previewSize == null) {
                    previewSize = computePreviewSize()
                }
                attachPreviewOutputLocked()
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun resetPreviewImpl() {
        withContext(defaultDispatcher) {
            synchronized(previewLock) {
                detachPreviewOutputLocked()
                previewSurface = null
            }
        }
    }

    override suspend fun getOutput() = controller.getOutput(STREAM_NAME)?.surface

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun setOutput(surface: Surface) = withContext(defaultDispatcher) {
        streamMutex.withLock {
            if (isStreamingFlow.value) {
                Logger.w(TAG, "Trying to set output while streaming")
            }
            controller.addOutput(CameraSurface(STREAM_NAME, surface))
            if (isStreamRequested || isPreviewRequested) {
                syncStreamTargetUnsafe()
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun resetOutputImpl() = withContext(defaultDispatcher) {
        // Keep previewSurface intact so StreamPack's resolution reconfiguration
        // (resetOutput -> configure -> setSurfaceProcessor -> setOutput) re-attaches it.
        synchronized(previewLock) {
            detachPreviewOutputLocked()
        }
        streamMutex.withLock {
            isExpectingSessionClose = controller.isActiveFlow.value
            executeIfCameraPermission {
                controller.removeOutput(STREAM_NAME)
            }
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun configure(config: VideoSourceConfig) {
        savedConfig = config
        val targetFps = config.cameraFps ?: config.fps
        if (!characteristics.isFpsSupported(targetFps)) {
            Logger.w(TAG, "Camera $cameraId does not support $targetFps fps")
        }

        try {
            controller.setDynamicRangeProfile(config.dynamicRangeProfile)
        } catch (t: Throwable) {
            Logger.w(TAG, "Failed to set dynamic range profile: ${t.message}")
        }
        try {
            controller.setFps(targetFps)
        } catch (t: Throwable) {
            Logger.w(TAG, "Failed to set fps: ${t.message}")
        }
        synchronized(previewLock) {
            if (previewOutput != null) {
                attachPreviewOutputLocked()
            }
        }
    }


    override fun <T> getPreviewSize(targetSize: Size, targetClass: Class<T>): Size {
        val selectedSize = computePreviewSize(targetSize) {
            CameraSizes.getPreviewOutputSize(characteristics, targetSize, targetClass)
        }!!
        synchronized(previewLock) {
            previewSize = selectedSize
        }
        return selectedSize
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun startCaptureMode(
        name: String,
        flow: MutableStateFlow<Boolean>,
        requested: KMutableProperty0<Boolean>,
        onStarted: () -> Unit = {}
    ) = withContext(defaultDispatcher) {
        streamMutex.withLock {
            if (flow.value) {
                Logger.w(TAG, "Camera is already ${name}ing")
                return@withLock
            }
            requested.set(true)
            try {
                syncStreamTargetUnsafe()
                flow.emit(true)
                onStarted()
            } catch (t: Throwable) {
                requested.set(false)
                throw t
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun stopCaptureMode(
        name: String,
        flow: MutableStateFlow<Boolean>,
        requested: KMutableProperty0<Boolean>,
        onStopping: () -> Unit = {}
    ) = withContext(defaultDispatcher) {
        streamMutex.withLock {
            if (!flow.value && !requested.get()) {
                Logger.w(TAG, "Camera is not ${name}ing")
                return@withLock
            }
            requested.set(false)
            try {
                onStopping()
                syncStreamTargetUnsafe()
            } catch (t: Throwable) {
                Logger.w(TAG, "Failed to stop $name: $t")
            } finally {
                flow.emit(false)
            }
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun startPreview() =
        startCaptureMode("preview", _isPreviewingFlow, ::isPreviewRequested)

    override suspend fun setOnFirstFrameListener(listener: () -> Unit) {
        controller.addCaptureCallbackListener(object : CaptureResultListener {
            override fun onCaptureResult(result: TotalCaptureResult): Boolean {
                listener()
                return true
            }
        })
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopPreview() =
        stopCaptureMode("preview", _isPreviewingFlow, ::isPreviewRequested)

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun startStream() =
        startCaptureMode("stream", _isStreamingFlow, ::isStreamRequested) {
            controller.muteVibrationAndSound()
        }

    @SuppressLint("MissingPermission")
    override suspend fun stopStream() =
        stopCaptureMode("stream", _isStreamingFlow, ::isStreamRequested) {
            controller.unmuteVibrationAndSound()
        }

    override suspend fun release() {
        synchronized(previewLock) {
            detachPreviewOutputLocked()
            previewSurface = null
            previewSize = null
        }
        streamMutex.withLock {
            isStreamRequested = false
            isPreviewRequested = false
            isExpectingSessionClose = false
            controller.unmuteVibrationAndSound()
            controller.release()
            _isStreamingFlow.emit(false)
            _isPreviewingFlow.emit(false)
        }
    }

    private suspend fun executeIfCameraPermission(block: suspend () -> Unit) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            block()
        }
    }

    override fun toString(): String {
        return "CameraSource(id=$cameraId)"
    }

    companion object {
        private const val TAG = "CameraSource"

        private const val STREAM_NAME = "stream"
        private const val MAX_PREVIEW_DIMENSION = 1920
    }
}
