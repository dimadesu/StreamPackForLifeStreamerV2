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
package io.github.thibaultbee.streampack.core.elements.sources.video.mediaprojection

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.Size
import android.view.Surface
import io.github.thibaultbee.streampack.core.elements.processing.video.source.DefaultSourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.processing.video.DefaultSurfaceProcessorFactory
import io.github.thibaultbee.streampack.core.elements.processing.video.ISurfaceProcessorInternal
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.elements.sources.IMediaProjectionSource
import io.github.thibaultbee.streampack.core.elements.sources.video.ISurfaceSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import io.github.thibaultbee.streampack.core.elements.utils.RotationValue
import io.github.thibaultbee.streampack.core.elements.utils.extensions.densityDpi
import io.github.thibaultbee.streampack.core.elements.utils.extensions.isRotationPortrait
import io.github.thibaultbee.streampack.core.elements.utils.extensions.landscapize
import io.github.thibaultbee.streampack.core.elements.utils.extensions.portraitize
import io.github.thibaultbee.streampack.core.elements.utils.extensions.screenRect
import io.github.thibaultbee.streampack.core.elements.utils.extensions.size
import io.github.thibaultbee.streampack.core.elements.utils.time.Timebase
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.core.pipelines.DispatcherProvider.Companion.THREAD_NAME_VIRTUAL_DISPLAY
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider
import io.github.thibaultbee.streampack.core.pipelines.utils.HandlerThreadExecutor
import io.github.thibaultbee.streampack.core.utils.InternalStreamPackApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.WeakHashMap

@OptIn(InternalStreamPackApi::class)
internal class MediaProjectionVideoSource(
    private val context: Context,
    override val mediaProjection: MediaProjection,
    private val dispatcherProvider: IVideoDispatcherProvider,
    private val handlerThreadExecutor: HandlerThreadExecutor,
    private val cfrFps: Int = 0,
    @RotationValue private val overrideRotation: Int? = null,
) : IVideoSourceInternal, ISurfaceSourceInternal, IMediaProjectionSource {
    override val timebase = Timebase.UPTIME
    override val infoProviderFlow =
        MutableStateFlow(
            FullScreenInfoProvider(
                context,
                overrideRotation
            ) as ISourceInfoProvider
        ).asStateFlow()

    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    private var outputSurface: Surface? = null
    private var inputSurface: Surface? = null
    private var surfaceProcessor: ISurfaceProcessorInternal? = null
    /**
     * Screen size used the last time surfaces were attached.
     * Compared in [isSameAs] so a source is recreated after orientation changes.
     */
    private var configuredSurfaceSize: Size? = null

    private var virtualDisplay: VirtualDisplay? = null

    private val virtualDisplayHandler = handlerThreadExecutor.handler
    private val virtualDisplayCallback = object : VirtualDisplay.Callback() {
        override fun onPaused() {
            super.onPaused()
            Logger.i(TAG, "onPaused")
        }

        override fun onStopped() {
            super.onStopped()
            Logger.i(TAG, "onStopped")

            runBlocking {
                stopStream()
            }
        }
    }

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Logger.i(TAG, "onStop")

            runBlocking {
                stopStream()
            }
        }
    }

    private var outputSurfaceOutput: SurfaceOutput? = null

    override suspend fun getOutput() = outputSurface
    override suspend fun setOutput(surface: Surface) {
        outputSurface = surface
        val screenSize = getMediaProjectionSurfaceSize()
        configuredSurfaceSize = screenSize
        if (_isStreamingFlow.value) {
            setupProcessorAndSurfaces(surface, screenSize)
            // Re-create virtual display if already streaming
            virtualDisplay?.surface = inputSurface
        }
    }

    private fun setupProcessorAndSurfaces(surface: Surface, screenSize: Size) {
        if (cfrFps > 0) {
            val processor = surfaceProcessor ?: DefaultSurfaceProcessorFactory(cfrFps).create(
                context,
                io.github.thibaultbee.streampack.core.elements.utils.av.video.DynamicRangeProfile.sdr,
                dispatcherProvider
            ).also { surfaceProcessor = it }

            inputSurface?.let { processor.removeInputSurface(it) }
            outputSurfaceOutput?.let { processor.removeOutputSurface(it) }

            inputSurface = processor.createInputSurface(screenSize, timebase)
            
            outputSurfaceOutput = SurfaceOutput(
                targetSurface = surface,
                targetResolution = screenSize,
                targetRotation = 0,
                isStreaming = { _isStreamingFlow.value },
                sourceResolution = screenSize,
                needMirroring = false,
                sourceInfoProvider = infoProviderFlow.value
            )
            processor.addOutputSurface(outputSurfaceOutput!!)
        } else {
            inputSurface = surface
        }
    }

    override suspend fun resetOutput() {
        stopStream()
        
        surfaceProcessor?.let {
            outputSurfaceOutput?.let { output ->
                it.removeOutputSurface(output)
            }
            inputSurface?.let { input -> it.removeInputSurface(input) }
            it.release()
        }
        
        surfaceProcessor = null
        outputSurfaceOutput = null
        inputSurface = null
        outputSurface = null
        configuredSurfaceSize = null
    }

    override suspend fun configure(config: VideoSourceConfig) = Unit

    override suspend fun startStream() {
        val screenSize = getMediaProjectionSurfaceSize()
        configuredSurfaceSize = screenSize

        outputSurface?.let { setupProcessorAndSurfaces(it, screenSize) }

        mediaProjection.registerCallback(mediaProjectionCallback, virtualDisplayHandler)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            screenSize.width,
            screenSize.height,
            context.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            virtualDisplayCallback,
            virtualDisplayHandler
        )
        _isStreamingFlow.emit(true)
    }

    override suspend fun stopStream() {
        val hadVirtualDisplay = virtualDisplay != null
        virtualDisplay?.release()
        virtualDisplay = null
        if (hadVirtualDisplay) {
            // Android invalidates the token for createVirtualDisplay once its virtual display
            // is released; remember that so callers don't try to reuse it for video capture.
            markProjectionExhausted(mediaProjection)
        }

        surfaceProcessor?.let {
            outputSurfaceOutput?.let { output ->
                it.removeOutputSurface(output)
            }
            inputSurface?.let { input -> it.removeInputSurface(input) }
            it.release()
        }
        surfaceProcessor = null
        outputSurfaceOutput = null
        inputSurface = null

        try {
            mediaProjection.unregisterCallback(mediaProjectionCallback)
        } catch (_: Exception) {
        }
        _isStreamingFlow.emit(false)
    }

    override suspend fun release() {
        handlerThreadExecutor.quit()
    }

    private fun getMediaProjectionSurfaceSize(): Size {
        val screenSize = context.screenRect.size
        return if (overrideRotation != null) {
            if (context.isRotationPortrait(overrideRotation)) {
                screenSize.portraitize
            } else {
                screenSize.landscapize
            }
        } else {
            screenSize
        }
    }

    /**
     * Whether this source matches factory parameters and is still sized for the current screen.
     *
     * Returning true lets VideoInput skip setSource. That does not rebuild VideoInput's
     * global SurfaceProcessor; it only skips creating a new source and a new processor input
     * surface. After an orientation change the stored surface size no longer matches, so the
     * source is recreated and VideoInput attaches a new input surface at the current screen
     * dimensions.
     */
    internal fun isSameAs(
        mediaProjection: MediaProjection,
        cfrFps: Int,
        overrideRotation: Int?
    ): Boolean {
        if (this.mediaProjection != mediaProjection) return false
        if (this.cfrFps != cfrFps) return false
        if (this.overrideRotation != overrideRotation) return false
        val configured = configuredSurfaceSize ?: return true
        return configured == getMediaProjectionSurfaceSize()
    }

    private inner class FullScreenInfoProvider(
        private val context: Context,
        @RotationValue private val overrideRotation: Int? = null,
    ) :
        DefaultSourceInfoProvider() {
        override fun getSurfaceSize(targetResolution: Size): Size {
            return getMediaProjectionSurfaceSize()
        }
    }

    companion object {
        private const val TAG = "MediaProjectionVideo"

        private const val VIRTUAL_DISPLAY_NAME = "StreamPackScreenSource"

        /**
         * [MediaProjection] instances whose virtual display has already been created and released.
         * Android forbids calling [MediaProjection.createVirtualDisplay] again on such an instance,
         * so callers must request a new token instead of reusing it for video capture.
         */
        private val exhaustedProjections =
            Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<MediaProjection, Boolean>()))

        private fun markProjectionExhausted(mediaProjection: MediaProjection) {
            exhaustedProjections.add(mediaProjection)
        }

        /**
         * Whether [mediaProjection] has already had a virtual display created and released, and
         * therefore can no longer be used to start a new screen video capture.
         */
        fun isProjectionExhaustedForVideo(mediaProjection: MediaProjection): Boolean =
            exhaustedProjections.contains(mediaProjection)
    }
}

/**
 * A factory to create a [MediaProjectionVideoSource].
 *
 * @param mediaProjection The media projection
 * @param cfrFps Constant frame rate for screen capture. 0 keeps event-driven rendering.
 * @param overrideRotation The override rotation. If null, the rotation is taken from the device orientation. Use this to force a specific rotation of the media projection surface.
 */
@OptIn(InternalStreamPackApi::class)
class MediaProjectionVideoSourceFactory(
    private val mediaProjection: MediaProjection,
    private val cfrFps: Int = 0,
    @RotationValue private val overrideRotation: Int? = null
) :
    IVideoSource.Factory {
    override suspend fun create(
        context: Context,
        dispatcherProvider: IVideoDispatcherProvider
    ): IVideoSourceInternal {
        val source = MediaProjectionVideoSource(
            context,
            mediaProjection,
            dispatcherProvider,
            dispatcherProvider.createVideoHandlerExecutor(THREAD_NAME_VIRTUAL_DISPLAY),
            cfrFps,
            overrideRotation
        )
        return source
    }

    /**
     * True when [source] is already a MediaProjection source with the same token, fps, rotation,
     * and current screen size.
     *
     * VideoInput.setSource skips recreation when this returns true. That does not rebuild the
     * global SurfaceProcessor; it only skips creating a new source and a new processor input
     * surface. After orientation changes, [MediaProjectionVideoSource.isSameAs] returns false
     * so VideoInput creates a new source and attaches a new input surface at the current size.
     */
    override fun isSourceEquals(source: IVideoSourceInternal?): Boolean {
        return source is MediaProjectionVideoSource &&
                source.isSameAs(mediaProjection, cfrFps, overrideRotation)
    }

    override fun toString(): String {
        return "MediaProjectionVideoSourceFactory(mediaProjection=$mediaProjection, cfrFps=$cfrFps, overrideRotation=$overrideRotation)"
    }

    companion object {
        /**
         * Whether [mediaProjection] has already been used to create a virtual display that was
         * later stopped. Android forbids reusing such a token for another [MediaProjection.createVirtualDisplay]
         * call, so callers should request a new token instead of building a source from it.
         */
        fun isProjectionExhaustedForVideo(mediaProjection: MediaProjection): Boolean =
            MediaProjectionVideoSource.isProjectionExhaustedForVideo(mediaProjection)
    }
}
