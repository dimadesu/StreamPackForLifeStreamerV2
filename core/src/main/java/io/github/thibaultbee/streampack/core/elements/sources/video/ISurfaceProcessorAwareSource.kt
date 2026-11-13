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
     * Whether the preview surface must be re-requested because the preview size no longer
     * matches the source (e.g. the aspect ratio changed after a new [VideoSourceConfig]).
     */
    fun needsPreviewRefresh(): Boolean = false

    /**
     * Marker interface for factories creating an [ISurfaceProcessorAwareSource].
     */
    @SubclassOptInRequired(InternalStreamPackApi::class)
    interface Factory : IVideoSource.Factory
}
