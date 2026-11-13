package io.github.thibaultbee.streampack.core.elements.processing.video

import android.util.Size
import android.view.Surface
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.mockk.mockk
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DefaultSurfaceProcessorSnapshotTest {
    private val infoProvider = object : ISourceInfoProvider {
        override val rotationDegrees: Int = 90
        override val isMirror: Boolean = true
        override fun getSurfaceSize(targetResolution: Size): Size = Size(1920, 1080)
    }

    private fun createSurfaceOutput(
        resolution: Size,
        needMirroring: Boolean
    ): SurfaceOutput {
        return SurfaceOutput(
            targetSurface = mockk<Surface>(relaxed = true),
            targetResolution = resolution,
            targetRotation = Surface.ROTATION_0,
            isStreaming = { true },
            sourceResolution = Size(1920, 1080),
            needMirroring = needMirroring,
            sourceInfoProvider = infoProvider
        )
    }

    @Test
    fun `selectSnapshotSurface prefers unmirrored encoder output over larger mirrored preview`() {
        val encoderOutput = createSurfaceOutput(Size(1280, 720), needMirroring = false)
        val mirroredPreviewOutput = createSurfaceOutput(Size(1080, 1920), needMirroring = true)

        val selected = selectSnapshotSurface(listOf(mirroredPreviewOutput, encoderOutput))

        assertSame(encoderOutput, selected)
    }

    @Test
    fun `selectSnapshotSurface picks largest unmirrored output when multiple exist`() {
        val smallerUnmirrored = createSurfaceOutput(Size(1280, 720), needMirroring = false)
        val largerUnmirrored = createSurfaceOutput(Size(1920, 1080), needMirroring = false)
        val mirroredPreview = createSurfaceOutput(Size(1440, 2560), needMirroring = true)

        val selected = selectSnapshotSurface(
            listOf(smallerUnmirrored, mirroredPreview, largerUnmirrored)
        )

        assertSame(largerUnmirrored, selected)
    }

    @Test
    fun `selectSnapshotSurface falls back to mirrored output when no unmirrored output exists`() {
        val mirroredPreview = createSurfaceOutput(Size(1080, 1920), needMirroring = true)

        val selected = selectSnapshotSurface(listOf(mirroredPreview))

        assertSame(mirroredPreview, selected)
    }
}

