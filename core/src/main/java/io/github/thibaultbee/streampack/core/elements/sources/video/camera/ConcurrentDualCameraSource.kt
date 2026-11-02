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
import io.github.thibaultbee.streampack.core.elements.processing.video.ISurfaceProcessorInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.AbstractPreviewableSource
import io.github.thibaultbee.streampack.core.elements.sources.video.ISurfaceProcessorAwareSource
import io.github.thibaultbee.streampack.core.elements.sources.video.ISurfaceSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraDispatcherProvider
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.core.pipelines.DispatcherProvider.Companion.THREAD_NAME_CAMERA
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A video source that captures video from two cameras concurrently (e.g. front and rear)
 * and composites them into a single frame using OpenGL.
 */
class ConcurrentDualCameraSource internal constructor(
    context: Context,
    internal val primaryCameraSource: CameraSource,
    internal val secondaryCameraSource: CameraSource,
    var compositionMode: CompositionMode = CompositionMode.PIP_TOP_RIGHT
) : ICameraSource, ICameraSourceInternal, ISurfaceSourceInternal, ISurfaceProcessorAwareSource, AbstractPreviewableSource() {

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

    private var surfaceProcessor: ISurfaceProcessorInternal? = null
    private var primarySurface: Surface? = null
    private var secondarySurface: Surface? = null

    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow: StateFlow<Boolean> = _isStreamingFlow.asStateFlow()

    private val _isPreviewingFlow = MutableStateFlow(false)
    override val isPreviewingFlow: StateFlow<Boolean> = _isPreviewingFlow.asStateFlow()

    override fun setSurfaceProcessor(processor: ISurfaceProcessorInternal) {
        this.surfaceProcessor = processor
        processor.compositionMode = compositionMode
    }

    override suspend fun getOutput(): Surface? = primarySurface

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun setOutput(surface: Surface) {
        primarySurface = surface
        primaryCameraSource.setOutput(surface)

        val processor = surfaceProcessor
        if (processor != null) {
            val secondarySize = Size(1280, 720)
            val secSurface = processor.createInputSurface(secondarySize, secondaryCameraSource.timebase)
            this.secondarySurface = secSurface
            secondaryCameraSource.setOutput(secSurface)
        } else {
            Logger.w(TAG, "SurfaceProcessor not set on ConcurrentDualCameraSource when setting output")
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun resetOutputImpl() {
        primaryCameraSource.resetOutput()
        secondaryCameraSource.resetOutput()
        secondarySurface?.let {
            surfaceProcessor?.removeInputSurface(it)
            secondarySurface = null
        }
        primarySurface = null
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun configure(config: VideoSourceConfig) {
        primaryCameraSource.configure(config)
        val secResolution = if (config.resolution.width > 1280) Size(1280, 720) else config.resolution
        secondaryCameraSource.configure(config.copy(resolution = secResolution))
    }

    override fun <T> getPreviewSize(targetSize: Size, targetClass: Class<T>): Size {
        return primaryCameraSource.getPreviewSize(targetSize, targetClass)
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun startStream() {
        primaryCameraSource.startStream()
        try {
            secondaryCameraSource.startStream()
        } catch (t: Throwable) {
            Logger.e(TAG, "Failed to start secondary camera stream in concurrent mode", t)
        }
        _isStreamingFlow.value = true
    }

    override suspend fun stopStream() {
        primaryCameraSource.stopStream()
        secondaryCameraSource.stopStream()
        _isStreamingFlow.value = false
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun startPreview() {
        primaryCameraSource.startPreview()
        try {
            secondaryCameraSource.startPreview()
        } catch (t: Throwable) {
            Logger.w(TAG, "Failed to start secondary camera preview: ${t.message}")
        }
        _isPreviewingFlow.value = true
    }

    override suspend fun stopPreview() {
        primaryCameraSource.stopPreview()
        secondaryCameraSource.stopPreview()
        _isPreviewingFlow.value = false
    }

    override suspend fun hasPreview(): Boolean {
        return primaryCameraSource.hasPreview()
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun setPreview(surface: Surface) {
        primaryCameraSource.setPreview(surface)
    }

    @SuppressLint("MissingPermission")
    override suspend fun resetPreviewImpl() {
        primaryCameraSource.resetPreview()
    }

    override suspend fun release() {
        primaryCameraSource.release()
        secondaryCameraSource.release()
    }

    companion object {
        private const val TAG = "ConcurrentDualCameraSource"
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
