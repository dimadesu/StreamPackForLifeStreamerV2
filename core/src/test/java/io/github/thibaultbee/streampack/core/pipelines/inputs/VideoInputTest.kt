package io.github.thibaultbee.streampack.core.pipelines.inputs

import android.content.Context
import android.util.Size
import androidx.test.core.app.ApplicationProvider
import io.github.thibaultbee.streampack.core.elements.processing.video.ISurfaceProcessor
import io.github.thibaultbee.streampack.core.elements.processing.video.ISurfaceProcessorInternal
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.IPreviewableSource
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import io.github.thibaultbee.streampack.core.elements.utils.StubLogger
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider
import io.github.thibaultbee.streampack.core.utils.InternalStreamPackApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(InternalStreamPackApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VideoInputTest {
    interface PreviewableVideoSource : IVideoSourceInternal, IPreviewableSource

    private lateinit var videoInput: VideoInput
    private lateinit var previewSurfaceProvider: IPreviewSurfaceProvider

    init {
        Logger.logger = StubLogger()
    }

    @Before
    fun setUp() {
        val processorFactory = mockk<ISurfaceProcessor.Factory> {
            every { create(any(), any(), any()) } returns mockk<ISurfaceProcessorInternal>(relaxed = true)
        }
        val dispatcherProvider = mockk<IVideoDispatcherProvider>(relaxed = true) {
            every { default } returns Dispatchers.Unconfined
        }
        videoInput = VideoInput(
            ApplicationProvider.getApplicationContext<Context>(),
            processorFactory,
            dispatcherProvider,
            onUpdateOutputSurface = { emptyList() }
        )
        previewSurfaceProvider = mockk(relaxed = true)
    }

    @After
    fun tearDown() = runTest {
        videoInput.release()
    }

    /**
     * Sets a previewable source, then the preview provider, so that the preview request made
     * while switching source is not counted by the tests.
     */
    private suspend fun setPreviewableSource(isPreviewStale: Boolean): PreviewableVideoSource {
        val source = mockk<PreviewableVideoSource>(relaxed = true) {
            every { infoProviderFlow } returns MutableStateFlow<ISourceInfoProvider>(mockk(relaxed = true))
            every { isStreamingFlow } returns MutableStateFlow(false)
            every { needsPreviewRefresh() } returns isPreviewStale
        }
        val factory = mockk<IVideoSource.Factory> {
            every { isSourceEquals(any()) } returns false
            coEvery { create(any(), any()) } returns source
        }
        videoInput.setSource(factory)
        videoInput.previewSurfaceProvider = previewSurfaceProvider
        return source
    }

    @Test
    fun `setSourceConfig re-requests the preview when the source reports a stale preview size`() =
        runTest {
            val source = setPreviewableSource(isPreviewStale = true)

            videoInput.setSourceConfig(VideoSourceConfig(resolution = Size(1440, 1080)))

            coVerify(exactly = 1) { previewSurfaceProvider.onPreparePreview(source) }
        }

    @Test
    fun `setSourceConfig keeps the preview when the source reports an up to date preview size`() =
        runTest {
            setPreviewableSource(isPreviewStale = false)

            videoInput.setSourceConfig(VideoSourceConfig(resolution = Size(1440, 1080)))

            coVerify(exactly = 0) { previewSurfaceProvider.onPreparePreview(any()) }
        }

    @Test
    fun `setSourceConfig still applies the configuration when the preview refresh fails`() =
        runTest {
            setPreviewableSource(isPreviewStale = true)
            coEvery { previewSurfaceProvider.onPreparePreview(any()) } throws
                IllegalStateException("No surface")
            val config = VideoSourceConfig(resolution = Size(1440, 1080))

            videoInput.setSourceConfig(config)

            coVerify(exactly = 1) { previewSurfaceProvider.onPreparePreview(any()) }
            assertEquals(config, videoInput.sourceConfigFlow.value)
        }
}
