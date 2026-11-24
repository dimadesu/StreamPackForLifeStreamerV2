package io.github.thibaultbee.streampack.core.elements.sources.video

import io.github.thibaultbee.streampack.core.elements.processing.video.ISurfaceProcessorInternal
import io.github.thibaultbee.streampack.core.utils.InternalStreamPackApi

/**
 * Interface for video sources that require direct access to the [ISurfaceProcessorInternal]
 * (e.g., to allocate secondary input surfaces for concurrent multi-camera sources).
 */
interface ISurfaceProcessorAwareSource {
    /**
     * Supplies the surface processor to the video source.
     */
    fun setSurfaceProcessor(processor: ISurfaceProcessorInternal)

    /**
     * Releases the source while optionally keeping its preview output registered on the
     * shared [ISurfaceProcessorInternal] so the transition render loop can continue rendering
     * onto the preview surface until the incoming source replaces it in-place.
     */
    suspend fun releaseKeepingPreviewOutput() {}

    /**
     * Marker interface for factories creating an [ISurfaceProcessorAwareSource].
     */
    @SubclassOptInRequired(InternalStreamPackApi::class)
    interface Factory : IVideoSource.Factory
}
