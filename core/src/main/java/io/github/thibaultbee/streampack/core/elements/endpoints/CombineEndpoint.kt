/*
 * Copyright (C) 2024 Thibault B.
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
package io.github.thibaultbee.streampack.core.elements.endpoints

import android.content.Context
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.elements.data.Frame
import io.github.thibaultbee.streampack.core.elements.data.copy
import io.github.thibaultbee.streampack.core.elements.encoders.CodecConfig
import io.github.thibaultbee.streampack.core.elements.utils.extensions.intersect
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.core.pipelines.IDispatcherProvider
import io.github.thibaultbee.streampack.core.pipelines.utils.MultiThrowable
import io.github.thibaultbee.streampack.core.utils.InternalStreamPackApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Combines multiple endpoints into one.
 *
 * @param endpoints Endpoints to combine
 * @param coroutineDispatcher Coroutine dispatcher to use for frame writing
 */
fun CombineEndpoint(vararg endpoints: IEndpointInternal, coroutineDispatcher: CoroutineDispatcher) =
    CombineEndpoint(endpoints.toList(), coroutineDispatcher)

/**
 * Combines multiple endpoints into one.
 * This endpoint will write frames to all endpoints if they are opened.
 *
 * For example, you can combine a local endpoint and a remote endpoint to record and stream at the
 * same time.
 *
 * For specific behavior like reconnecting your remote endpoint, you can create a custom endpoint that
 * inherits from [CombineEndpoint] and override [open], [close], [startStream], [stopStream].
 *
 * @param endpointInternals List of endpoints to combine
 * @param coroutineDispatcher Coroutine dispatcher to use for frame writing
 */
@OptIn(InternalStreamPackApi::class)
open class CombineEndpoint(
    protected val endpointInternals: List<IEndpointInternal>,
    coroutineDispatcher: CoroutineDispatcher
) :
    IEndpointInternal {
    protected val coroutineScope = CoroutineScope(SupervisorJob() + coroutineDispatcher)

    /**
     * Internal map of endpoint streamId to real streamIds
     */
    protected val endpointsToStreamIdsMap = mutableMapOf<Pair<IEndpoint, Int>, Int>()

    /**
     * Active stream configurations keyed by CombineEndpoint streamId.
     */
    protected val activeStreamConfigs = mutableMapOf<Int, CodecConfig>()

    /**
     * Endpoints that have completed [startStream] and have not been stopped or closed.
     */
    protected val startedEndpoints = ConcurrentHashMap.newKeySet<IEndpointInternal>()

    protected val _internalThrowableFlow = MutableStateFlow<Throwable?>(null)

    /**
     * List of [IEndpoint]s
     */
    val endpoints: List<IEndpoint>
        get() = endpointInternals

    /**
     * Whether at least one endpoint is open.
     * This is a combination of all endpoints' [IEndpoint.isOpenFlow].
     *
     * To verify if a specific endpoint is open, use [IEndpoint.isOpenFlow] of the endpoint.
     */
    override val isOpenFlow: StateFlow<Boolean> =
        combineTransform(*endpointInternals.map { it.isOpenFlow }.toTypedArray()) { isOpens ->
            emit(isOpens.any { it })
        }.stateIn(
            coroutineScope,
            SharingStarted.Eagerly,
            false
        )

    override val throwableFlow: StateFlow<Throwable?> = merge(
        _internalThrowableFlow,
        *endpointInternals.map { it.throwableFlow }.toTypedArray()
    ).stateIn(
        coroutineScope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    init {
        endpointInternals.forEach { endpoint ->
            coroutineScope.launch {
                endpoint.isOpenFlow.drop(1).collect { isOpen ->
                    if (!isOpen && startedEndpoints.remove(endpoint)) {
                        onChildUnexpectedClose(endpoint)
                    }
                }
            }
        }
    }

    protected open suspend fun onChildUnexpectedClose(endpoint: IEndpointInternal) {
        if (endpoint.throwableFlow.value == null) {
            _internalThrowableFlow.emit(ClosedException("Connection lost"))
        }
    }

    protected open fun onChildWriteError(endpoint: IEndpointInternal, throwable: Throwable) {
        _internalThrowableFlow.tryEmit(throwable)
    }

    /**
     * The union of all currently open endpoints' [IEndpoint.IEndpointInfo] (or all endpoints if none are open).
     */
    override val info: IEndpoint.IEndpointInfo
        get() = endpointInternals.filter { it.isOpenFlow.value }
            .ifEmpty { endpointInternals }
            .map { it.info }
            .reduce { acc, iEndpointInfo -> acc intersect iEndpointInfo }

    /**
     * The union of all endpoints' [IEndpoint.IEndpointInfo] that support [type].
     */
    override fun getInfo(type: MediaDescriptor.Type): IEndpoint.IEndpointInfo {
        val infos = endpointInternals.mapNotNull {
            try {
                it.getInfo(type)
            } catch (_: Throwable) {
                null
            }
        }
        require(infos.isNotEmpty()) { "Unsupported type: $type" }
        return infos.reduce { acc, iEndpointInfo -> acc intersect iEndpointInfo }
    }

    private fun createNewStreamId(): Int {
        var i = 0
        while (activeStreamConfigs.containsKey(i) || endpointsToStreamIdsMap.keys.any { it.second == i }) {
            i++
        }
        return i
    }

    override suspend fun addStream(streamConfig: CodecConfig): Int {
        val streamId = createNewStreamId()
        activeStreamConfigs[streamId] = streamConfig
        endpointInternals.filter { it.isOpenFlow.value }.forEach { endpoint ->
            endpointsToStreamIdsMap[Pair(endpoint, streamId)] = endpoint.addStream(streamConfig)
        }
        return streamId
    }

    override suspend fun addStreams(streamConfigs: List<CodecConfig>): Map<CodecConfig, Int> {
        val streamIds = mutableMapOf<CodecConfig, Int>()
        val openEndpoints = endpointInternals.filter { it.isOpenFlow.value }
        val newEntries = streamConfigs.map { streamConfig ->
            val streamId = createNewStreamId()
            activeStreamConfigs[streamId] = streamConfig
            streamIds[streamConfig] = streamId
            streamId to streamConfig
        }
        openEndpoints.forEach { endpoint ->
            val addedMap = endpoint.addStreams(newEntries.map { it.second })
            newEntries.forEach { (streamId, config) ->
                endpointsToStreamIdsMap[Pair(endpoint, streamId)] =
                    addedMap[config] ?: throw IllegalStateException("Endpoint did not return stream id for $config")
            }
        }
        return streamIds
    }

    protected suspend fun ensureEndpointStreamsRegistered(endpoint: IEndpointInternal) {
        val missingEntries = activeStreamConfigs.entries
            .filter { (streamId, _) -> endpointsToStreamIdsMap[Pair(endpoint, streamId)] == null }
        if (missingEntries.isEmpty()) return

        val addedMap = endpoint.addStreams(missingEntries.map { it.value })
        missingEntries.forEach { (streamId, config) ->
            endpointsToStreamIdsMap[Pair(endpoint, streamId)] =
                addedMap[config] ?: throw IllegalStateException("Endpoint did not return stream id for $config")
        }
    }

    protected fun clearEndpointStreams(endpoint: IEndpointInternal) {
        endpointsToStreamIdsMap.keys.filter { it.first == endpoint }.forEach {
            endpointsToStreamIdsMap.remove(it)
        }
    }

    protected open fun isKeepAliveOnWriteError(): Boolean = false

    /**
     * Opens the endpoints.
     * For specific behavior like reconnecting your remote endpoint, you can override this method.
     *
     * If the [descriptor] is a [CombineDescriptor] and has the same number of descriptors as
     * endpoints, it opens each endpoint with the corresponding descriptor.
     *
     * If the [descriptor] is not a [CombineDescriptor] or has a different number of descriptors.
     * It throws an [IllegalArgumentException].
     *
     * @param descriptor Media descriptor
     */
    override suspend fun open(descriptor: MediaDescriptor) {
        if (descriptor !is CombineDescriptor) {
            throw IllegalArgumentException("CombineEndpoint only supports CombineDescriptor.")
        }
        require(descriptor.descriptors.size == endpointInternals.size) {
            "CombineDescriptor must have the same number of descriptors as endpoints."
        }
        endpointInternals.forEachIndexed { index, endpoint ->
            try {
                endpoint.open(descriptor.descriptors[index])
            } catch (t: Throwable) {
                Logger.e(TAG, "Failed to open endpoint $endpoint", t)
            }
        }
    }

    /**
     * Closes all endpoints
     */
    override suspend fun close() {
        startedEndpoints.clear()
        endpointInternals.forEach { endpoint ->
            try {
                endpoint.close()
            } catch (t: Throwable) {
                Logger.e(TAG, "Failed to close endpoint $endpoint", t)
            }
        }
    }

    /**
     * Starts all opened endpoints
     */
    override suspend fun startStream() {
        endpointInternals.filter { it.isOpenFlow.value }.forEach { endpoint ->
            ensureEndpointStreamsRegistered(endpoint)
            endpoint.startStream()
            startedEndpoints.add(endpoint)
        }
    }

    /**
     * Stops all endpoints
     */
    override suspend fun stopStream() {
        startedEndpoints.clear()
        endpointInternals.forEach { endpoint ->
            try {
                endpoint.stopStream()
            } catch (t: Throwable) {
                Logger.e(TAG, "Failed to stop endpoint $endpoint", t)
            }
        }
        endpointsToStreamIdsMap.clear()
        activeStreamConfigs.clear()
    }

    /**
     * Writes frame to all opened and started endpoints.
     *
     * If all endpoints write fails and keep-alive is not active, it throws the exception of the first endpoint that failed.
     */
    override suspend fun write(frame: Frame, streamPid: Int) {
        val activeEndpoints = endpointInternals.filter {
            it.isOpenFlow.value && startedEndpoints.contains(it)
        }
        if (activeEndpoints.isEmpty()) {
            frame.close()
            return
        }

        val throwables = mutableListOf<Throwable>()

        /**
         * Track the number of frames written and processed to call onFrameProcessed only once
         * when all endpoints have processed the frame.
         */
        val deferreds = mutableListOf<Deferred<*>>()

        activeEndpoints.forEach { endpoint ->
            val deferred = CompletableDeferred<Unit>()
            try {
                val endpointStreamId = endpointsToStreamIdsMap[Pair(endpoint, streamPid)]
                    ?: throw IllegalStateException("Stream id not registered for endpoint $endpoint")
                val duplicatedFrame = frame.copy(
                    rawBuffer = frame.rawBuffer.duplicate(),
                    onClosed = { deferred.complete(Unit) }
                )

                deferreds += deferred
                endpoint.write(duplicatedFrame, endpointStreamId)
            } catch (t: Throwable) {
                deferred.complete(Unit)
                Logger.e(TAG, "Failed to write frame for endpoint $endpoint", t)
                startedEndpoints.remove(endpoint)
                onChildWriteError(endpoint, t)
                throwables += t
            }
        }

        coroutineScope.launch {
            deferreds.forEach { it.await() }
            frame.close()
        }

        if (throwables.isNotEmpty()) {
            if (!isKeepAliveOnWriteError() && endpoints.none { it.isOpenFlow.value }) {
                /**
                 * If no endpoint is opened, the exception is thrown directly to stop the pipeline.
                 */
                if (throwables.size == 1) {
                    throw throwables.first()
                }
                throw MultiThrowable(throwables)
            }
        }
    }

    override suspend fun release() {
        endpointInternals.forEach { endpoint ->
            try {
                endpoint.release()
            } catch (t: Throwable) {
                Logger.e(TAG, "Failed to release endpoint $endpoint", t)
            }
        }
        coroutineScope.coroutineContext.cancelChildren()
    }

    companion object {
        private const val TAG = "CombineEndpoint"
    }

    /**
     * A [MediaDescriptor] that combines multiple [MediaDescriptor]s.
     */
    class CombineDescriptor(val descriptors: List<MediaDescriptor>) :
        MediaDescriptor(descriptors.first().type, emptyList()) {
        /**
         * The URI of the first descriptor.
         */
        override val uri = descriptors.first().uri
    }
}

/**
 * A factory to build a [CombineEndpoint] from a varargs of [IEndpoint.Factory].
 */
fun CombineEndpointFactory(vararg endpointFactory: IEndpoint.Factory) =
    CombineEndpointFactory(endpointFactory.toList())

/**
 * A factory to build a [CombineEndpoint] from a list of [IEndpoint.Factory].
 */
@OptIn(InternalStreamPackApi::class)
class CombineEndpointFactory(private val endpointFactory: List<IEndpoint.Factory>) :
    IEndpoint.Factory {
    override fun create(
        context: Context,
        dispatcherProvider: IDispatcherProvider
    ): IEndpointInternal {
        return CombineEndpoint(
            endpointFactory.map { it.create(context, dispatcherProvider) },
            dispatcherProvider.default
        )
    }
}