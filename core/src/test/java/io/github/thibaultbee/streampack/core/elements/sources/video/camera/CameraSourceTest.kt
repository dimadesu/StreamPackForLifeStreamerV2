package io.github.thibaultbee.streampack.core.elements.sources.video.camera

import android.Manifest
import android.app.Application
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import androidx.test.core.app.ApplicationProvider
import io.github.thibaultbee.streampack.core.elements.processing.video.ISurfaceProcessorInternal
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.ISurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.elements.sources.video.ISurfaceProcessorAwareSource
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.controllers.CameraController
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraDispatcherProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraSurface
import io.github.thibaultbee.streampack.core.elements.utils.StubLogger
import io.github.thibaultbee.streampack.core.elements.utils.av.video.DynamicRangeProfile
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.core.utils.InternalStreamPackApi
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.runs
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@OptIn(InternalStreamPackApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CameraSourceTest {
    private val outputs = linkedMapOf<String, CameraSurface>()
    private val activeTargets = linkedSetOf<String>()
    private lateinit var activeFlow: MutableStateFlow<Boolean>
    private lateinit var context: Context

    init {
        Logger.logger = StubLogger()
    }

    @Before
    fun setUp() {
        outputs.clear()
        activeTargets.clear()
        activeFlow = MutableStateFlow(false)

        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA)

        val backMap = mockk<StreamConfigurationMap>()
        val supportedSizes =
            arrayOf(Size(3840, 2160), Size(1920, 1080), Size(1440, 1080), Size(1280, 720))
        every { backMap.getOutputSizes(SurfaceTexture::class.java) } returns supportedSizes
        every { backMap.getOutputSizes(SurfaceHolder::class.java) } returns supportedSizes

        val backCharacteristics = mockCharacteristics(
            configMap = backMap,
            sensorOrientation = 90,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK
        )
        val frontCharacteristics = mockCharacteristics(
            configMap = backMap,
            sensorOrientation = 270,
            lensFacing = CameraCharacteristics.LENS_FACING_FRONT
        )

        val cameraManager = mockk<CameraManager>()
        every { cameraManager.cameraIdList } returns arrayOf("0", "1")
        every { cameraManager.getCameraCharacteristics("0") } returns backCharacteristics
        every { cameraManager.getCameraCharacteristics("1") } returns frontCharacteristics

        context = spyk(app)
        every { context.getSystemService(Context.CAMERA_SERVICE) } returns cameraManager

        mockkConstructor(CameraController::class)
        every { anyConstructed<CameraController>().isActiveFlow } returns activeFlow
        coEvery { anyConstructed<CameraController>().addOutput(any()) } answers {
            val surface = firstArg<CameraSurface>()
            outputs[surface.name] = surface
        }
        coEvery { anyConstructed<CameraController>().removeOutput(any()) } answers {
            val name = firstArg<String>()
            outputs.remove(name)
            activeTargets.remove(name)
            if (outputs.isEmpty()) {
                activeFlow.value = false
            }
        }
        coEvery { anyConstructed<CameraController>().hasOutput(any<String>()) } answers {
            outputs.containsKey(firstArg())
        }
        coEvery { anyConstructed<CameraController>().getOutput(any()) } answers {
            outputs[firstArg<String>()]
        }
        coEvery { anyConstructed<CameraController>().addTarget(any()) } answers {
            val name = firstArg<String>()
            require(outputs.containsKey(name)) { "Output $name not found" }
            activeTargets.add(name)
            activeFlow.value = true
        }
        coEvery { anyConstructed<CameraController>().removeTarget(any()) } answers {
            activeTargets.remove(firstArg<String>())
        }
        coJustRun { anyConstructed<CameraController>().setDynamicRangeProfile(any<DynamicRangeProfile>()) }
        coJustRun { anyConstructed<CameraController>().setFps(any()) }
        every { anyConstructed<CameraController>().muteVibrationAndSound() } just runs
        every { anyConstructed<CameraController>().unmuteVibrationAndSound() } just runs
        coJustRun { anyConstructed<CameraController>().release() }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun mockCharacteristics(
        configMap: StreamConfigurationMap,
        sensorOrientation: Int,
        lensFacing: Int
    ): CameraCharacteristics {
        val characteristics = mockk<CameraCharacteristics>()
        every { characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) } returns
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
        every { characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) } returns configMap
        every { characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) } returns sensorOrientation
        every { characteristics.get(CameraCharacteristics.LENS_FACING) } returns lensFacing
        every { characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) } returns
            arrayOf(Range(30, 30))
        return characteristics
    }

    private fun createCameraSource(cameraId: String = "0"): CameraSource {
        val dispatcherProvider = CameraDispatcherProvider(
            Dispatchers.Unconfined,
            { mockk(relaxed = true) },
            { mockk(relaxed = true) }
        )
        return CameraSource(context, cameraId, dispatcherProvider)
    }

    @Test
    fun `factory implements ISurfaceProcessorAwareSource Factory`() {
        val factory: Any = CameraSourceFactory("0")
        assertTrue(factory is ISurfaceProcessorAwareSource.Factory)
    }

    @Test
    fun `preview uses surface processor output without adding camera2 preview output`() = runTest {
        val source = createCameraSource("0")
        val processor = mockk<ISurfaceProcessorInternal>(relaxed = true)
        val inputSurface = mockk<Surface>(relaxed = true)
        val previewSurface = mockk<Surface>(relaxed = true)

        source.configure(VideoSourceConfig(resolution = Size(1920, 1080)))
        source.setSurfaceProcessor(processor)
        source.setOutput(inputSurface)

        source.getPreviewSize(Size(1080, 2200), SurfaceHolder::class.java)
        source.setPreview(previewSurface)
        source.startPreview()

        assertEquals(setOf("stream"), outputs.keys)
        assertTrue(activeTargets.contains("stream"))
        assertTrue(source.isPreviewingFlow.value)
        assertTrue(source.hasPreview())

        val addedOutputSlot = slot<ISurfaceOutput>()
        verify(exactly = 1) { processor.addOutputSurface(capture(addedOutputSlot)) }
        val previewOutput = addedOutputSlot.captured as SurfaceOutput
        assertEquals(previewSurface, previewOutput.targetSurface)
        assertFalse(previewOutput.needMirroring)
        assertTrue(previewOutput.isStreaming())

        source.release()
    }

    @Test
    fun `background while streaming detaches preview output without restarting or stopping camera2 stream`() = runTest {
        val source = createCameraSource("0")
        val processor = mockk<ISurfaceProcessorInternal>(relaxed = true)
        val inputSurface = mockk<Surface>(relaxed = true)
        val previewSurface1 = mockk<Surface>(relaxed = true)
        val previewSurface2 = mockk<Surface>(relaxed = true)

        source.configure(VideoSourceConfig(resolution = Size(1920, 1080)))
        source.setSurfaceProcessor(processor)
        source.setOutput(inputSurface)
        source.getPreviewSize(Size(1080, 2200), SurfaceHolder::class.java)
        source.setPreview(previewSurface1)
        source.startPreview()
        source.startStream()

        // Background: PreviewView calls stopPreview() then resetPreview()
        source.stopPreview()
        source.resetPreview()

        assertTrue(source.isStreamingFlow.value)
        assertFalse(source.isPreviewingFlow.value)
        assertFalse(source.hasPreview())
        assertTrue(activeTargets.contains("stream"))
        coVerify(exactly = 0) { anyConstructed<CameraController>().removeTarget(any()) }
        coVerify(exactly = 0) { anyConstructed<CameraController>().removeOutput(any()) }
        verify(exactly = 1) { processor.removeOutputSurface(any<ISurfaceOutput>()) }

        // Foreground resume: new surface attached without touching CameraController outputs
        source.setPreview(previewSurface2)
        source.startPreview()

        assertTrue(source.isStreamingFlow.value)
        assertTrue(source.isPreviewingFlow.value)
        assertTrue(source.hasPreview())
        verify(exactly = 2) { processor.addOutputSurface(any()) }
        coVerify(exactly = 1) { anyConstructed<CameraController>().addOutput(any()) }

        source.release()
    }

    @Test
    fun `background while not streaming stops repeating camera2 capture and resumes on foreground`() = runTest {
        val source = createCameraSource("0")
        val processor = mockk<ISurfaceProcessorInternal>(relaxed = true)
        val inputSurface = mockk<Surface>(relaxed = true)
        val previewSurface1 = mockk<Surface>(relaxed = true)
        val previewSurface2 = mockk<Surface>(relaxed = true)

        source.configure(VideoSourceConfig(resolution = Size(1920, 1080)))
        source.setSurfaceProcessor(processor)
        source.setOutput(inputSurface)
        source.getPreviewSize(Size(1080, 2200), SurfaceHolder::class.java)
        source.setPreview(previewSurface1)
        source.startPreview()

        assertTrue(activeTargets.contains("stream"))

        // Background while idle (not streaming)
        source.stopPreview()
        source.resetPreview()

        assertFalse(activeTargets.contains("stream"))
        assertFalse(source.isPreviewingFlow.value)
        assertEquals(setOf("stream"), outputs.keys)
        coVerify(exactly = 1) { anyConstructed<CameraController>().removeTarget("stream") }
        verify(exactly = 1) { processor.removeOutputSurface(any<ISurfaceOutput>()) }

        // Foreground resume re-adds target
        source.setPreview(previewSurface2)
        source.startPreview()

        assertTrue(activeTargets.contains("stream"))
        assertTrue(source.isPreviewingFlow.value)
        coVerify(exactly = 2) { anyConstructed<CameraController>().addTarget("stream") }

        source.release()
    }

    @Test
    fun `front camera preview mirrors output and getPreviewSize returns upright dimensions`() = runTest {
        val source = createCameraSource("1")
        val processor = mockk<ISurfaceProcessorInternal>(relaxed = true)
        val inputSurface = mockk<Surface>(relaxed = true)
        val previewSurface = mockk<Surface>(relaxed = true)

        source.configure(VideoSourceConfig(resolution = Size(3840, 2160)))
        source.setSurfaceProcessor(processor)
        source.setOutput(inputSurface)

        val selectedSize = source.getPreviewSize(Size(1080, 2200), SurfaceHolder::class.java)
        assertEquals(Size(1080, 1920), selectedSize)

        source.setPreview(previewSurface)
        val outputSlot = slot<ISurfaceOutput>()
        verify(exactly = 1) { processor.addOutputSurface(capture(outputSlot)) }
        val output = outputSlot.captured as SurfaceOutput
        assertTrue(output.needMirroring)
        assertEquals(Size(1080, 1920), output.targetResolution)

        source.release()
    }

    @Test
    fun `replacing surface processor migrates preview output without losing preview state`() = runTest {
        val source = createCameraSource("0")
        val processor1 = mockk<ISurfaceProcessorInternal>(relaxed = true)
        val processor2 = mockk<ISurfaceProcessorInternal>(relaxed = true)
        val inputSurface1 = mockk<Surface>(relaxed = true)
        val inputSurface2 = mockk<Surface>(relaxed = true)
        val previewSurface = mockk<Surface>(relaxed = true)

        source.configure(VideoSourceConfig(resolution = Size(1920, 1080)))
        source.setSurfaceProcessor(processor1)
        source.setOutput(inputSurface1)
        source.getPreviewSize(Size(1080, 2200), SurfaceHolder::class.java)
        source.setPreview(previewSurface)
        source.startPreview()

        // Simulate dynamic range / resolution reconfiguration in VideoInput
        source.resetOutput()
        source.configure(VideoSourceConfig(resolution = Size(1280, 720)))
        source.setSurfaceProcessor(processor2)
        source.setOutput(inputSurface2)

        assertTrue(source.isPreviewingFlow.value)
        assertTrue(source.hasPreview())
        assertTrue(activeTargets.contains("stream"))
        verify(exactly = 1) { processor1.removeOutputSurface(any<ISurfaceOutput>()) }
        verify(exactly = 1) { processor2.addOutputSurface(any()) }

        source.release()
    }

    @Test
    fun `early startPreview before setSurfaceProcessor and setOutput attaches and starts once output arrives`() = runTest {
        val source = createCameraSource("0")
        val processor = mockk<ISurfaceProcessorInternal>(relaxed = true)
        val inputSurface = mockk<Surface>(relaxed = true)
        val previewSurface = mockk<Surface>(relaxed = true)

        // Prepare preview before VideoInput configures source/processor
        source.getPreviewSize(Size(1080, 2200), SurfaceHolder::class.java)
        source.setPreview(previewSurface)
        source.startPreview()

        assertTrue(source.isPreviewingFlow.value)
        assertFalse(activeTargets.contains("stream"))
        coVerify(exactly = 0) { anyConstructed<CameraController>().addTarget(any()) }
        coVerify(exactly = 0) { anyConstructed<CameraController>().removeTarget(any()) }

        // Subsequent configure + setSurfaceProcessor + setOutput attaches preview and starts capture
        source.configure(VideoSourceConfig(resolution = Size(1920, 1080)))
        source.setSurfaceProcessor(processor)
        source.setOutput(inputSurface)

        verify(exactly = 1) { processor.addOutputSurface(any()) }
        assertTrue(activeTargets.contains("stream"))
        coVerify(exactly = 1) { anyConstructed<CameraController>().addTarget("stream") }

        source.release()
    }

    @Test
    fun `setOutput without preview or stream does not open targets prematurely`() = runTest {
        val source = createCameraSource("0")
        val inputSurface = mockk<Surface>(relaxed = true)

        source.configure(VideoSourceConfig(resolution = Size(1920, 1080)))
        source.setOutput(inputSurface)

        assertTrue(activeTargets.isEmpty())
        coVerify(exactly = 0) { anyConstructed<CameraController>().addTarget(any()) }
        coVerify(exactly = 0) { anyConstructed<CameraController>().removeTarget(any()) }

        source.release()
    }

    @Test
    fun `preview size from fallback before configure is reported stale when aspect differs`() = runTest {
        val source = createCameraSource("0")
        val processor = mockk<ISurfaceProcessorInternal>(relaxed = true)
        val previewSurface = mockk<Surface>(relaxed = true)

        // Before configure: fallback picks a 16:9 camera size for the view
        assertEquals(
            Size(1080, 1920),
            source.getPreviewSize(Size(1080, 2200), SurfaceHolder::class.java)
        )
        source.setPreview(previewSurface)
        assertFalse(source.needsPreviewRefresh())

        // Stream configured as 4:3: the 9:16 preview buffer no longer matches
        source.configure(VideoSourceConfig(resolution = Size(1440, 1080)))
        source.setSurfaceProcessor(processor)
        source.setOutput(mockk(relaxed = true))
        assertTrue(source.needsPreviewRefresh())

        // PreviewView re-requests: new size matches the configured buffer
        assertEquals(
            Size(1080, 1440),
            source.getPreviewSize(Size(1080, 2200), SurfaceHolder::class.java)
        )
        source.setPreview(mockk(relaxed = true))
        assertFalse(source.needsPreviewRefresh())

        source.release()
    }

    @Test
    fun `same aspect ratio resolution change does not require preview refresh`() = runTest {
        val source = createCameraSource("0")
        val previewSurface = mockk<Surface>(relaxed = true)

        source.configure(VideoSourceConfig(resolution = Size(1920, 1080)))
        source.getPreviewSize(Size(1080, 2200), SurfaceHolder::class.java)
        source.setPreview(previewSurface)
        assertFalse(source.needsPreviewRefresh())

        source.configure(VideoSourceConfig(resolution = Size(1280, 720)))
        assertFalse(source.needsPreviewRefresh())

        source.release()
    }

    @Test
    fun `no preview refresh needed without a preview surface`() = runTest {
        val source = createCameraSource("0")

        source.getPreviewSize(Size(1080, 2200), SurfaceHolder::class.java)
        source.configure(VideoSourceConfig(resolution = Size(1440, 1080)))

        assertFalse(source.needsPreviewRefresh())

        source.release()
    }

    @Test
    fun `configure skips preview reattach when effective input size matches and reattaches when it changes`() = runTest {
        val source = createCameraSource("0")
        val processor = mockk<ISurfaceProcessorInternal>(relaxed = true)
        val previewSurface = mockk<Surface>(relaxed = true)

        source.setSurfaceProcessor(processor)
        assertEquals(Size(1080, 1920), source.getPreviewSize(Size(1080, 2200), SurfaceHolder::class.java))
        source.setPreview(previewSurface)

        verify(exactly = 1) { processor.addOutputSurface(any()) }
        verify(exactly = 0) { processor.removeOutputSurface(any<ISurfaceOutput>()) }

        // Same 1920x1080 effective input size as pre-configure rotated preview size -> no churn
        source.configure(VideoSourceConfig(resolution = Size(1920, 1080)))
        verify(exactly = 1) { processor.addOutputSurface(any()) }
        verify(exactly = 0) { processor.removeOutputSurface(any<ISurfaceOutput>()) }

        // Different input size -> detaches old output and attaches updated one
        source.configure(VideoSourceConfig(resolution = Size(1440, 1080)))
        verify(exactly = 1) { processor.removeOutputSurface(any<ISurfaceOutput>()) }
        verify(exactly = 2) { processor.addOutputSurface(any()) }

        source.release()
    }

    @Test
    fun `setSurfaceProcessor directly detaches from previous processor when replaced`() = runTest {
        val source = createCameraSource("0")
        val processor1 = mockk<ISurfaceProcessorInternal>(relaxed = true)
        val processor2 = mockk<ISurfaceProcessorInternal>(relaxed = true)
        val previewSurface = mockk<Surface>(relaxed = true)

        source.configure(VideoSourceConfig(resolution = Size(1920, 1080)))
        source.setSurfaceProcessor(processor1)
        source.getPreviewSize(Size(1080, 2200), SurfaceHolder::class.java)
        source.setPreview(previewSurface)

        source.setSurfaceProcessor(processor2)

        verify(exactly = 1) { processor1.removeOutputSurface(any<ISurfaceOutput>()) }
        verify(exactly = 1) { processor2.addOutputSurface(any()) }

        source.release()
    }
}

