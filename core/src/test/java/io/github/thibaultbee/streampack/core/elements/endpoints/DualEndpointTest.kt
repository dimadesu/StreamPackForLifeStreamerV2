package io.github.thibaultbee.streampack.core.elements.endpoints

import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.elements.data.Frame
import io.github.thibaultbee.streampack.core.elements.encoders.AudioCodecConfig
import io.github.thibaultbee.streampack.core.elements.encoders.CodecConfig
import io.github.thibaultbee.streampack.core.elements.utils.DescriptorUtils
import io.github.thibaultbee.streampack.core.elements.utils.FakeFrameFactory
import io.github.thibaultbee.streampack.core.utils.InternalStreamPackApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(InternalStreamPackApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DualEndpointTest {

    private class FakeEndpoint : IEndpointInternal {
        private val _isOpenFlow = MutableStateFlow(false)
        override val isOpenFlow: StateFlow<Boolean> = _isOpenFlow.asStateFlow()

        private val _throwableFlow = MutableStateFlow<Throwable?>(null)
        override val throwableFlow: StateFlow<Throwable?> = _throwableFlow.asStateFlow()

        override val info: IEndpoint.IEndpointInfo = MediaMuxerEndpoint.Mp4EndpointInfo
        override fun getInfo(type: MediaDescriptor.Type): IEndpoint.IEndpointInfo = info

        var writtenFrames = 0
        var started = false
        var writeException: Throwable? = null
        private var nextStreamId = 0

        override suspend fun open(descriptor: MediaDescriptor) {
            _throwableFlow.value = null
            _isOpenFlow.value = true
        }

        override suspend fun close() {
            started = false
            _isOpenFlow.value = false
        }

        override suspend fun addStreams(streamConfigs: List<CodecConfig>): Map<CodecConfig, Int> {
            return streamConfigs.associateWith { nextStreamId++ }
        }

        override suspend fun addStream(streamConfig: CodecConfig): Int = nextStreamId++

        override suspend fun startStream() {
            started = true
        }

        override suspend fun stopStream() {
            started = false
        }

        override suspend fun write(frame: Frame, streamPid: Int) {
            try {
                writeException?.let { throw it }
                writtenFrames++
            } finally {
                frame.close()
            }
        }

        override suspend fun release() {
            close()
        }
    }

    @Test
    fun `offline-first mainEndpoint starts first and secondEndpoint attaches later`() = runTest {
        val main = FakeEndpoint()
        val second = FakeEndpoint()
        val dual = DualEndpoint(main, second, Dispatchers.Unconfined)

        try {
            dual.open(DescriptorUtils.createFileDescriptor("local.ts"))
            val streamIds = dual.addStreams(listOf(AudioCodecConfig()))
            val audioPid = streamIds.values.first()
            dual.startStream()

            // Frame written while offline only goes to mainEndpoint
            dual.write(FakeFrameFactory.create(MediaFormat.MIMETYPE_AUDIO_AAC), audioPid)
            assertEquals(1, main.writtenFrames)
            assertEquals(0, second.writtenFrames)

            // Remote attaches in-flight
            dual.openSecond(DescriptorUtils.createFileDescriptor("remote.ts"))
            dual.startStreamSecond()

            dual.write(FakeFrameFactory.create(MediaFormat.MIMETYPE_AUDIO_AAC), audioPid)
            assertEquals(2, main.writtenFrames)
            assertEquals(1, second.writtenFrames)

            // Remote disconnects — mainEndpoint keeps writing
            dual.closeSecond()
            dual.write(FakeFrameFactory.create(MediaFormat.MIMETYPE_AUDIO_AAC), audioPid)
            assertEquals(3, main.writtenFrames)
            assertEquals(1, second.writtenFrames)
            assertTrue(dual.isOpenFlow.value)
        } finally {
            dual.stopStream()
            dual.close()
            dual.release()
        }
    }

    @Test
    fun `mainEndpoint write error routes to mainThrowableFlow without disrupting secondEndpoint`() = runTest {
        val main = FakeEndpoint()
        val second = FakeEndpoint()
        val dual = DualEndpoint(main, second, Dispatchers.Unconfined)

        try {
            dual.open(DescriptorUtils.createFileDescriptor("local.ts"))
            dual.openSecond(DescriptorUtils.createFileDescriptor("remote.ts"))
            val audioPid = dual.addStreams(listOf(AudioCodecConfig())).values.first()
            dual.startStream()

            main.writeException = IOException("No space left on device")
            dual.write(FakeFrameFactory.create(MediaFormat.MIMETYPE_AUDIO_AAC), audioPid)

            // Error is isolated to mainThrowableFlow and does NOT emit to remote throwableFlow
            assertTrue(dual.mainThrowableFlow.value is IOException)
            assertNull(dual.throwableFlow.value)

            // secondEndpoint keeps receiving frames
            assertEquals(1, second.writtenFrames)
            dual.write(FakeFrameFactory.create(MediaFormat.MIMETYPE_AUDIO_AAC), audioPid)
            assertEquals(2, second.writtenFrames)

            // Closing main leaves second open and streaming
            dual.stopStreamMain()
            dual.closeMain()
            assertFalse(main.isOpenFlow.value)
            assertTrue(second.isOpenFlow.value)
            assertTrue(second.started)
        } finally {
            dual.stopStream()
            dual.close()
            dual.release()
        }
    }

    @Test
    fun `secondEndpoint write error routes to throwableFlow while mainEndpoint keeps writing`() = runTest {
        val main = FakeEndpoint()
        val second = FakeEndpoint()
        val dual = DualEndpoint(main, second, Dispatchers.Unconfined)

        try {
            dual.open(DescriptorUtils.createFileDescriptor("local.ts"))
            dual.openSecond(DescriptorUtils.createFileDescriptor("remote.ts"))
            val audioPid = dual.addStreams(listOf(AudioCodecConfig())).values.first()
            dual.startStream()

            second.writeException = IOException("Broken pipe")
            dual.write(FakeFrameFactory.create(MediaFormat.MIMETYPE_AUDIO_AAC), audioPid)

            assertNotNull(dual.throwableFlow.value)
            assertNull(dual.mainThrowableFlow.value)
            assertEquals(1, main.writtenFrames)

            dual.write(FakeFrameFactory.create(MediaFormat.MIMETYPE_AUDIO_AAC), audioPid)
            assertEquals(2, main.writtenFrames)
        } finally {
            dual.stopStream()
            dual.close()
            dual.release()
        }
    }
}
