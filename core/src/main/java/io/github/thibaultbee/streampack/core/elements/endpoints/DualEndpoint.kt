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
import android.net.Uri
import androidx.core.net.toUri
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.UriMediaDescriptor
import io.github.thibaultbee.streampack.core.elements.metrics.EmptyEndpointMetrics
import io.github.thibaultbee.streampack.core.elements.metrics.EndpointMetrics
import io.github.thibaultbee.streampack.core.elements.metrics.WithEndpointMetrics
import io.github.thibaultbee.streampack.core.pipelines.IDispatcherProvider
import io.github.thibaultbee.streampack.core.utils.InternalStreamPackApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An implementation of [CombineEndpoint] that combines two endpoints.
 * The [mainEndpoint] is the only one closed and opened by this endpoint.
 * The [secondEndpoint] is opened and closed by the user.
 *
 * For example, you can combine a local endpoint and a remote endpoint to record and stream at the same time.
 * In that case, the local endpoint will be the [mainEndpoint] and the remote endpoint will be the [secondEndpoint].
 *
 * @param mainEndpoint the main endpoint
 * @param secondEndpoint the second endpoint
 * @param coroutineDispatcher the coroutine dispatcher
 */
@OptIn(InternalStreamPackApi::class)
open class DualEndpoint(
    val mainEndpoint: IEndpointInternal,
    val secondEndpoint: IEndpointInternal,
    coroutineDispatcher: CoroutineDispatcher
) : CombineEndpoint(
    listOf(secondEndpoint, mainEndpoint),
    coroutineDispatcher
), WithEndpointMetrics<Any> {
    private val secondMutex = Mutex()
    private val _isSessionActiveFlow = MutableStateFlow(false)

    override val metrics: EndpointMetrics<Any>
        get() = (secondEndpoint as? WithEndpointMetrics<Any>)?.metrics
            ?.takeIf { it !is EmptyEndpointMetrics }
            ?: (mainEndpoint as? WithEndpointMetrics<Any>)?.metrics
            ?: EmptyEndpointMetrics

    override val isOpenFlow: StateFlow<Boolean> =
        combineTransform(
            _isSessionActiveFlow,
            *endpointInternals.map { it.isOpenFlow }.toTypedArray()
        ) { values ->
            emit(values.any { it })
        }.stateIn(
            coroutineScope,
            SharingStarted.Eagerly,
            false
        )

    override fun isKeepAliveOnWriteError(): Boolean = _isSessionActiveFlow.value

    /**
     * Opens the [mainEndpoint].
     *
     * You must open the [secondEndpoint] manually.
     *
     * @param descriptor the media descriptor
     */
    override suspend fun open(descriptor: MediaDescriptor) {
        _internalThrowableFlow.value = null
        mainEndpoint.open(descriptor)
    }

    /**
     * Starts the opened endpoints ([mainEndpoint] and/or [secondEndpoint]).
     */
    override suspend fun startStream() {
        require(mainEndpoint.isOpenFlow.value || secondEndpoint.isOpenFlow.value) {
            "At least one endpoint must be opened before starting stream"
        }
        _isSessionActiveFlow.value = true
        try {
            if (mainEndpoint.isOpenFlow.value && !startedEndpoints.contains(mainEndpoint)) {
                ensureEndpointStreamsRegistered(mainEndpoint)
                mainEndpoint.startStream()
                startedEndpoints.add(mainEndpoint)
            }
            if (secondEndpoint.isOpenFlow.value) {
                startStreamSecond()
            }
        } catch (t: Throwable) {
            if (startedEndpoints.isEmpty()) {
                _isSessionActiveFlow.value = false
            }
            throw t
        }
    }

    override suspend fun stopStream() {
        _isSessionActiveFlow.value = false
        super.stopStream()
    }

    override suspend fun close() {
        _isSessionActiveFlow.value = false
        super.close()
    }

    /**
     * Opens the [secondEndpoint].
     */
    suspend fun openSecond(descriptor: MediaDescriptor) = secondMutex.withLock {
        _internalThrowableFlow.value = null
        secondEndpoint.open(descriptor)
    }

    /**
     * Starts the [secondEndpoint].
     */
    suspend fun startStreamSecond() = secondMutex.withLock {
        if (startedEndpoints.contains(secondEndpoint) && secondEndpoint.isOpenFlow.value) {
            return@withLock
        }
        _isSessionActiveFlow.value = true
        ensureEndpointStreamsRegistered(secondEndpoint)
        secondEndpoint.startStream()
        startedEndpoints.add(secondEndpoint)
    }

    /**
     * Starts audio/video stream for the second endpoint.
     *
     * @param descriptor The media descriptor to open
     */
    suspend fun startStreamSecond(descriptor: MediaDescriptor) = secondMutex.withLock {
        try {
            _internalThrowableFlow.value = null
            secondEndpoint.open(descriptor)
            _isSessionActiveFlow.value = true
            ensureEndpointStreamsRegistered(secondEndpoint)
            secondEndpoint.startStream()
            startedEndpoints.add(secondEndpoint)
        } catch (e: Exception) {
            startedEndpoints.remove(secondEndpoint)
            if (startedEndpoints.isEmpty()) {
                _isSessionActiveFlow.value = false
            }
            secondEndpoint.close()
            clearEndpointStreams(secondEndpoint)
            throw e
        }
    }

    /**
     * Stops the [secondEndpoint].
     */
    suspend fun stopStreamSecond() = secondMutex.withLock {
        startedEndpoints.remove(secondEndpoint)
        secondEndpoint.stopStream()
        clearEndpointStreams(secondEndpoint)
    }

    /**
     * Closes the [secondEndpoint].
     */
    suspend fun closeSecond() = secondMutex.withLock {
        startedEndpoints.remove(secondEndpoint)
        secondEndpoint.close()
        clearEndpointStreams(secondEndpoint)
    }
}

/**
 * Opens the second endpoint.
 *
 * @param uri The uri to open
 */
suspend fun DualEndpoint.openSecond(uri: Uri) =
    openSecond(UriMediaDescriptor(uri))

/**
 * Opens the second endpoint.
 *
 * @param uriString The uri to open
 */
suspend fun DualEndpoint.openSecond(uriString: String) =
    openSecond(uriString.toUri())

/**
 * Starts audio/video stream for the second endpoint.
 *
 * Same as doing [openSecond] and [startStreamSecond].
 *
 * @param uri The uri to open
 */
suspend fun DualEndpoint.startStreamSecond(uri: Uri) = startStreamSecond(UriMediaDescriptor(uri))

/**
 * Starts audio/video stream for the second endpoint.
 *
 * Same as doing [openSecond] and [startStreamSecond].
 *
 * @param uriString The uri to open
 */
suspend fun DualEndpoint.startStreamSecond(uriString: String) =
    startStreamSecond(uriString.toUri())

/**
 * A factory to build a [DualEndpoint].
 */
@OptIn(InternalStreamPackApi::class)
class DualEndpointFactory(
    private val mainEndpointFactory: IEndpoint.Factory,
    private val secondEndpointFactory: IEndpoint.Factory
) : IEndpoint.Factory {
    override fun create(
        context: Context,
        dispatcherProvider: IDispatcherProvider
    ): IEndpointInternal {
        return DualEndpoint(
            mainEndpointFactory.create(context, dispatcherProvider),
            secondEndpointFactory.create(context, dispatcherProvider),
            dispatcherProvider.default
        )
    }
}