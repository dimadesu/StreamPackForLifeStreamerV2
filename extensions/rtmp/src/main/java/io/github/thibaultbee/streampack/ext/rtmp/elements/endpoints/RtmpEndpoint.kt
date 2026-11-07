/*
 * Copyright (C) 2025 Thibault B.
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
package io.github.thibaultbee.streampack.ext.rtmp.elements.endpoints

import android.content.Context
import io.github.komedia.komuxer.amf.AmfVersion
import io.github.komedia.komuxer.flv.tags.FLVTag
import io.github.komedia.komuxer.flv.tags.audio.AudioData
import io.github.komedia.komuxer.flv.tags.video.VideoData
import io.github.komedia.komuxer.rtmp.RtmpConnectionBuilder
import io.github.komedia.komuxer.rtmp.client.RtmpClient
import io.github.komedia.komuxer.rtmp.client.RtmpClientSettings
import io.github.komedia.komuxer.rtmp.connect
import io.github.komedia.komuxer.rtmp.messages.command.StreamPublishType
import io.github.komedia.komuxer.rtmp.util.metrics.RtmpMetrics
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.elements.data.Frame
import io.github.thibaultbee.streampack.core.elements.encoders.CodecConfig
import io.github.thibaultbee.streampack.core.elements.endpoints.ClosedException
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpoint
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpointInternal
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.CompositeEndpoint.EndpointInfo
import io.github.thibaultbee.streampack.core.elements.metrics.WithEndpointMetrics
import io.github.thibaultbee.streampack.core.elements.utils.ChannelWithCloseableData
import io.github.thibaultbee.streampack.core.elements.utils.useConsumeEach
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.core.pipelines.IDispatcherProvider
import io.github.thibaultbee.streampack.core.utils.InternalStreamPackApi
import io.github.thibaultbee.streampack.ext.flv.elements.endpoints.composites.muxer.FlvMuxerInfo
import io.github.thibaultbee.streampack.ext.flv.elements.endpoints.composites.muxer.utils.FlvTagBuilder
import io.github.thibaultbee.streampack.ext.flv.elements.endpoints.composites.muxer.utils.close
import io.github.thibaultbee.streampack.ext.rtmp.configuration.mediadescriptor.RtmpMediaDescriptor
import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.EOFException
import java.io.IOException
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * An endpoint that send frame to an RTMP server.
 */
@OptIn(ExperimentalAtomicApi::class, InternalStreamPackApi::class)
class RtmpEndpoint internal constructor(
    defaultDispatcher: CoroutineDispatcher, val ioDispatcher: CoroutineDispatcher
) : IEndpointInternal, WithEndpointMetrics<RtmpRawMetrics> {
    private val coroutineScope = CoroutineScope(SupervisorJob() + defaultDispatcher)
    private val mutex = Mutex()

    private val metricsLock = Any()
    private var frameDropped = 0L
    private var audioFrameDropped = 0L
    private var videoFrameDropped = 0L

    private var payloadSendDroppedSize = 0L
    private var audioPayloadSendDroppedSize = 0L
    private var videoPayloadSendDroppedSize = 0L

    private val flvTagChannel = ChannelWithCloseableData<FLVTag>(
        FLV_TAG_CHANNEL_SIZE,
        BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { flvTag ->
            synchronized(metricsLock) {
                val payloadSize = flvTag.data.getSize(AmfVersion.AMF0)
                frameDropped++
                payloadSendDroppedSize += payloadSize
                if (flvTag.data is AudioData) {
                    audioFrameDropped++
                    audioPayloadSendDroppedSize += payloadSize
                } else if (flvTag.data is VideoData) {
                    videoFrameDropped++
                    videoPayloadSendDroppedSize += payloadSize
                }
            }
        }
    )
    private val flvTagBuilder = FlvTagBuilder(flvTagChannel)

    private val rtmpExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Logger.w(TAG, "Uncaught exception in RTMP socket scope (ignored to prevent crash): $throwable")
    }
    private val selectorManager = SelectorManager(ioDispatcher + rtmpExceptionHandler)
    private val connectionBuilder = RtmpConnectionBuilder(selectorManager)
    private var rtmpClient: RtmpClient? = null
    @Volatile
    private var isStarted = false

    private var startUpTimestamp = INVALID_TIMESTAMP
    private val timestampMutex = Mutex()

    private val syncMetrics: RtmpMetrics
        get() {
            val metrics =
                rtmpClient?.metrics ?: return RtmpMetrics.ZERO
            return synchronized(metricsLock) {
                metrics.copy(
                    messagesSendDropped = metrics.messagesSendDropped + frameDropped,
                    audioMessagesSendDropped = metrics.audioMessagesSendDropped + audioFrameDropped,
                    videoMessagesSendDropped = metrics.videoMessagesSendDropped + videoFrameDropped,
                    payloadSendDroppedSize = metrics.payloadSendDroppedSize + payloadSendDroppedSize,
                    audioPayloadSendDroppedSize = metrics.audioPayloadSendDroppedSize + audioPayloadSendDroppedSize,
                    videoPayloadSendDroppedSize = metrics.videoPayloadSendDroppedSize + videoPayloadSendDroppedSize
                )
            }
        }

    private val rtmpRawMetrics = RtmpRawMetrics(
        clientProvider = { rtmpClient },
        metricsProvider = { syncMetrics }
    )

    override val metrics: RtmpEndpointMetrics
        get() = RtmpEndpointMetrics(rtmpRawMetrics)

    private val _isOpenFlow = MutableStateFlow(false)
    override val isOpenFlow = _isOpenFlow.asStateFlow()

    override val info: IEndpoint.IEndpointInfo = EndpointInfo(FlvMuxerInfo)

    override fun getInfo(type: MediaDescriptor.Type) = EndpointInfo(FlvMuxerInfo)

    private val _throwableFlow = MutableStateFlow<Throwable?>(null)
    override val throwableFlow: StateFlow<Throwable?> = _throwableFlow.asStateFlow()

    init {
        coroutineScope.launch {
            flvTagChannel.useConsumeEach { flvTag ->
                write(flvTag)
            }
        }
    }

    private fun resetMetrics() {
        synchronized(metricsLock) {
            frameDropped = 0L
            audioFrameDropped = 0L
            videoFrameDropped = 0L

            payloadSendDroppedSize = 0L
            audioPayloadSendDroppedSize = 0L
            videoPayloadSendDroppedSize = 0L
        }
    }

    private suspend fun <T> safeClient(block: suspend (RtmpClient) -> T): T {
        val rtmpClient = requireNotNull(rtmpClient) { "Not opened" }
        require(!rtmpClient.isClosed) { "Connection closed" }
        return mutex.withLock { block(rtmpClient) }
    }

    override suspend fun open(descriptor: MediaDescriptor) =
        open(RtmpMediaDescriptor(descriptor))

    private suspend fun open(descriptor: RtmpMediaDescriptor) {
        withContext(ioDispatcher) {
            mutex.withLock {
                if (rtmpClient?.isClosed == false) {
                    Logger.w(TAG, "Already opened")
                    return@withContext
                }

                resetMetrics()
                val client = kotlinx.coroutines.withTimeoutOrNull(10000L) {
                    if (descriptor.connectInfo != null) {
                        connectionBuilder.connect(
                            descriptor.uri.toString(),
                            RtmpClientSettings(connectInfo = descriptor.connectInfo)
                        )
                    } else {
                        connectionBuilder.connect(descriptor.uri.toString())
                    }
                }

                if (client == null) {
                    throw IOException("RTMP connection timeout after 10 seconds")
                }

                rtmpClient = client.apply {
                    _isOpenFlow.emit(true)

                    socketContext.invokeOnCompletion { throwable ->
                        rtmpClient = null
                        isStarted = false

                        _isOpenFlow.tryEmit(false)
                        if (throwable != null) {
                            Logger.e(TAG, "RTMP connection closed with error: $throwable")
                            if (throwable !is EOFException) {
                                _throwableFlow.tryEmit(ClosedException(throwable))
                            } else {
                                _throwableFlow.tryEmit(
                                    ClosedException(
                                        "Connection lost",
                                        IOException(throwable)
                                    )
                                )
                            }
                        } else {
                            Logger.i(TAG, "RTMP connection closed")
                        }
                    }
                }
            }
        }
    }

    private suspend fun getStartUpTimestamp(ptsInUs: Long): Long = timestampMutex.withLock {
        // FLV timestamps start at 0
        if (startUpTimestamp == INVALID_TIMESTAMP) {
            startUpTimestamp = ptsInUs
        }
        startUpTimestamp
    }

    private suspend fun write(flvTag: FLVTag) {
        try {
            safeClient { rtmpClient ->
                rtmpClient.write(flvTag)
            }
        } catch (_: TimeoutCancellationException) {
            Logger.w(TAG, "Frame dropped due to timeout")
        } catch (t: Throwable) {
            Logger.w(TAG, "Error while writing FLV data: $t")
            // Socket is closed automatically on error
        }
        try {
            flvTag.data.close()
        } catch (t: Throwable) {
            Logger.e(TAG, "Error while closing FLVTag data: $t")
        }
    }

    override suspend fun write(
        frame: Frame, streamPid: Int
    ) {
        if (!_isOpenFlow.value || !isStarted) {
            frame.close()
            return
        }
        val startUpTimestamp = getStartUpTimestamp(frame.ptsInUs)
        val ts = (frame.ptsInUs - startUpTimestamp) / 1000
        flvTagBuilder.write(frame, ts.toInt(), streamPid)
    }

    override suspend fun addStreams(streamConfigs: List<CodecConfig>): Map<CodecConfig, Int> {
        require(streamConfigs.isNotEmpty()) { "At least one stream must be provided" }
        return flvTagBuilder.addStreams(streamConfigs)
    }

    override suspend fun addStream(streamConfig: CodecConfig) =
        flvTagBuilder.addStream(streamConfig)

    override suspend fun startStream() {
        kotlinx.coroutines.withTimeoutOrNull(10000L) {
            safeClient { rtmpClient ->
                rtmpClient.createStream()
                rtmpClient.publish(StreamPublishType.LIVE)

                rtmpClient.writeSetDataFrame(
                    flvTagBuilder.metadata
                )
                isStarted = true
            }
        } ?: throw IOException("RTMP startStream timeout after 10 seconds")
    }

    override suspend fun stopStream() {
        isStarted = false
        try {
            mutex.withLock {
                if (rtmpClient?.isClosed == false) {
                    val deleted = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                        rtmpClient?.deleteStream()
                        true
                    }
                    if (deleted == null) {
                        Logger.w(TAG, "RTMP deleteStream timeout after 3 seconds")
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "Error while stopping stream: $t")
        } finally {
            flvTagChannel.clear()
            flvTagBuilder.clearStreams()
            timestampMutex.withLock {
                startUpTimestamp = INVALID_TIMESTAMP
            }
        }
    }

    override suspend fun close() {
        isStarted = false
        mutex.withLock {
            try {
                val closed = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                    rtmpClient?.close()
                    true
                }
                if (closed == null) {
                    Logger.w(TAG, "RTMP socket close timeout after 2 seconds - forcing close")
                }
            } finally {
                rtmpClient = null
            }
        }
    }

    override suspend fun release() {
        try {
            kotlinx.coroutines.withTimeoutOrNull(2000L) {
                selectorManager.close()
            } ?: Logger.w(TAG, "SelectorManager close timeout after 2 seconds")
        } catch (t: Throwable) {
            Logger.w(TAG, "Error closing selector manager: $t")
        }
        coroutineScope.cancel()
    }

    companion object {
        private const val TAG = "RtmpEndpoint"

        private const val INVALID_TIMESTAMP = -1L

        private const val FLV_TAG_CHANNEL_SIZE =
            8 // Arbitrary buffer size. It's Audio output size + Video output size in the encoding output

        init {
            System.setProperty("kotlinx.io.pool.size.bytes", "4194304") // 4MB
            installSafeRtmpUncaughtExceptionHandler()
        }

        @Volatile
        private var handlerInstalled = false

        private fun installSafeRtmpUncaughtExceptionHandler() {
            if (handlerInstalled) return
            synchronized(this) {
                if (handlerInstalled) return
                val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    val isKomuxerCommandError = throwable.stackTrace.any {
                        it.className.startsWith("io.github.komedia.komuxer.rtmp.")
                    } && (throwable is IllegalArgumentException || throwable.cause is ClassCastException)
                    if (isKomuxerCommandError) {
                        Logger.w(
                            TAG,
                            "Ignored uncaught komuxer RTMP command parsing exception on thread ${thread.name}: $throwable"
                        )
                    } else {
                        previousHandler?.uncaughtException(thread, throwable)
                    }
                }
                handlerInstalled = true
            }
        }
    }
}

/**
 * A factory to build a [RtmpEndpoint].
 */
@OptIn(InternalStreamPackApi::class)
class RtmpEndpointFactory : IEndpoint.Factory {
    override fun create(
        context: Context, dispatcherProvider: IDispatcherProvider
    ): IEndpointInternal = RtmpEndpoint(dispatcherProvider.default, dispatcherProvider.io)
}
