package io.github.thibaultbee.streampack.core.elements.processing.video

import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiInputCompositorTest {
    @Test
    fun `render skips inputs without a frame`() {
        val renderer = mockk<OpenGlRenderer>(relaxed = true)
        val compositor = MultiInputCompositor(renderer)

        val primarySurface = mockk<Surface>()
        val primaryTexture = mockk<SurfaceTexture>()
        val primaryInput = SurfaceInput(primarySurface, primaryTexture, 1, Size(1920, 1080), hasFrame = true)

        val secondarySurface = mockk<Surface>()
        val secondaryTexture = mockk<SurfaceTexture>()
        val secondaryInput = SurfaceInput(secondarySurface, secondaryTexture, 2, Size(1280, 720), hasFrame = false)

        val resolver = mockk<IInputResolver>()
        val infoProvider = mockk<ISourceInfoProvider>(relaxed = true)
        every { infoProvider.getRelativeRotationDegrees(any(), any()) } returns 0
        every { resolver.getInfoProvider(any()) } returns infoProvider
        every { resolver.isSecondaryInput(primarySurface) } returns false
        every { resolver.isSecondaryInput(secondarySurface) } returns true
        every { resolver.getTargetRect(primarySurface) } returns RectF(0f, 0f, 1f, 1f)
        every { resolver.getTargetRect(secondarySurface) } returns RectF(0.65f, 0.05f, 0.95f, 0.35f)

        val output = mockk<SurfaceOutput>(relaxed = true)
        val targetSurface = mockk<Surface>()
        every { output.targetSurface } returns targetSurface
        every { output.isStreaming() } returns true
        every { output.viewportRect } returns Rect(0, 0, 1920, 1080)
        every { output.needMirroring } returns false

        val inputsSlot = slot<List<OpenGlRenderer.RenderInput>>()
        every { renderer.renderInputs(any(), capture(inputsSlot), targetSurface, false) } returns Unit

        compositor.render(
            renderTimestampNs = 1000L,
            surfaceInputs = listOf(primaryInput, secondaryInput),
            surfaceInputsToMatrixMap = emptyMap(),
            outputs = listOf(output),
            resolver = resolver,
            isMuted = false,
            surfaceOutputMatrix = FloatArray(16)
        )

        verify(exactly = 1) { renderer.renderInputs(1000L, any(), targetSurface, false) }
        assertEquals(1, inputsSlot.captured.size)
        assertEquals(1, inputsSlot.captured[0].textureId)
    }

    @Test
    fun `render includes secondary input once it has a frame`() {
        val renderer = mockk<OpenGlRenderer>(relaxed = true)
        val compositor = MultiInputCompositor(renderer)

        val primarySurface = mockk<Surface>()
        val primaryTexture = mockk<SurfaceTexture>()
        val primaryInput = SurfaceInput(primarySurface, primaryTexture, 1, Size(1920, 1080), hasFrame = true)

        val secondarySurface = mockk<Surface>()
        val secondaryTexture = mockk<SurfaceTexture>()
        val secondaryInput = SurfaceInput(secondarySurface, secondaryTexture, 2, Size(1280, 720), hasFrame = true)

        val resolver = mockk<IInputResolver>()
        val infoProvider = mockk<ISourceInfoProvider>(relaxed = true)
        every { infoProvider.getRelativeRotationDegrees(any(), any()) } returns 0
        every { resolver.getInfoProvider(any()) } returns infoProvider
        every { resolver.isSecondaryInput(primarySurface) } returns false
        every { resolver.isSecondaryInput(secondarySurface) } returns true
        every { resolver.getTargetRect(primarySurface) } returns RectF(0f, 0f, 1f, 1f)
        every { resolver.getTargetRect(secondarySurface) } returns RectF(0.65f, 0.05f, 0.95f, 0.35f)

        val output = mockk<SurfaceOutput>(relaxed = true)
        val targetSurface = mockk<Surface>()
        every { output.targetSurface } returns targetSurface
        every { output.isStreaming() } returns true
        every { output.viewportRect } returns Rect(0, 0, 1920, 1080)
        every { output.needMirroring } returns false

        val inputsSlot = slot<List<OpenGlRenderer.RenderInput>>()
        every { renderer.renderInputs(any(), capture(inputsSlot), targetSurface, false) } returns Unit

        compositor.render(
            renderTimestampNs = 1000L,
            surfaceInputs = listOf(primaryInput, secondaryInput),
            surfaceInputsToMatrixMap = emptyMap(),
            outputs = listOf(output),
            resolver = resolver,
            isMuted = false,
            surfaceOutputMatrix = FloatArray(16)
        )

        verify(exactly = 1) { renderer.renderInputs(1000L, any(), targetSurface, false) }
        assertEquals(2, inputsSlot.captured.size)
        assertEquals(1, inputsSlot.captured[0].textureId)
        assertEquals(2, inputsSlot.captured[1].textureId)
    }
}

