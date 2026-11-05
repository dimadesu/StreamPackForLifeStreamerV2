package io.github.thibaultbee.streampack.core.elements.sources.video

import io.github.thibaultbee.streampack.core.elements.processing.video.ISurfaceProcessorInternal

/**
 * Interface for video sources that require direct access to the [ISurfaceProcessorInternal]
 * (e.g., to allocate secondary input surfaces for concurrent multi-camera sources).
 */
interface ISurfaceProcessorAwareSource {
    /**
     * Supplies the surface processor to the video source.
     */
    fun setSurfaceProcessor(processor: ISurfaceProcessorInternal)
}
