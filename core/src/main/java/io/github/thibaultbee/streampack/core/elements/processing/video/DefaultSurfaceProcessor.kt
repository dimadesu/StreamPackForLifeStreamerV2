/*
 * Copyright 2022 The Android Open Source Project
 * Copyright 2024 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thibaultbee.streampack.core.elements.processing.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import androidx.annotation.IntRange
import androidx.concurrent.futures.CallbackToFutureAdapter
import com.google.common.util.concurrent.ListenableFuture
import io.github.thibaultbee.streampack.core.elements.interfaces.ISnapshotable
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.ISurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.extensions.preRotate
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.extensions.preVerticalFlip
import io.github.thibaultbee.streampack.core.elements.utils.av.video.DynamicRangeProfile
import io.github.thibaultbee.streampack.core.elements.utils.extensions.rotate
import io.github.thibaultbee.streampack.core.elements.utils.time.TimeUtils
import io.github.thibaultbee.streampack.core.elements.utils.time.Timebase
import io.github.thibaultbee.streampack.core.elements.utils.time.VideoTimebaseConverter
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.core.pipelines.DispatcherProvider.Companion.THREAD_NAME_GL
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider
import io.github.thibaultbee.streampack.core.pipelines.utils.HandlerThreadExecutor
import io.github.thibaultbee.streampack.core.utils.InternalStreamPackApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(InternalStreamPackApi::class)
private class DefaultSurfaceProcessor(
    private val dynamicRangeProfile: DynamicRangeProfile,
    private val glThread: HandlerThreadExecutor,
    private val cfrFps: Int = 0
) : ISurfaceProcessorInternal, SurfaceTexture.OnFrameAvailableListener, ISnapshotable {
    override var isMuted: Boolean = false
    override var inputResolver: IInputResolver? = null

    private val renderer = OpenGlRenderer()
    private val compositor = MultiInputCompositor(renderer)

    private val glHandler = glThread.handler

    private val isReleaseRequested = AtomicBoolean(false)
    private var isReleased = false

    private val textureMatrix = FloatArray(16)
    private val surfaceOutputMatrix = FloatArray(16)
    private val singleInputOutputMatrix = FloatArray(16)

    private val surfaceOutputs: MutableList<ISurfaceOutput> = mutableListOf()
    private val surfaceInputs: MutableList<SurfaceInput> = mutableListOf()
    private val surfaceInputsToTimeConverterMap: MutableMap<SurfaceTexture, VideoTimebaseConverter> =
        hashMapOf()
    private val surfaceInputsToMatrixMap: MutableMap<SurfaceTexture, FloatArray> = hashMapOf()

    // CFR & Transition Render loop state
    @Volatile private var latestTimestampNs: Long = 0L
    @Volatile private var hasFirstFrame: Boolean = false
    private var isRenderLoopRunning = false
    @Volatile private var isTransitioning: Boolean = false
    private var transitionIntervalNs: Long = 0L
    private val cfrFrameIntervalNs = if (cfrFps > 0) 1_000_000_000L / cfrFps else 0L
    private var nextFrameTimeNs = 0L
    private val transitionTimeoutRunnable = Runnable {
        if (isTransitioning) {
            Logger.w(TAG, "Transition timed out after ${TRANSITION_TIMEOUT_MS}ms, stopping transition loop")
            stopTransitionInternal()
        }
    }
    private val renderRunnable = object : Runnable {
        override fun run() {
            if (isReleaseRequested.get() || isReleased) return

            val intervalNs = if (isTransitioning) {
                transitionIntervalNs
            } else if (cfrFps > 0) {
                cfrFrameIntervalNs
            } else {
                0L
            }

            if (intervalNs <= 0L) {
                isRenderLoopRunning = false
                return
            }

            val nowNs = System.nanoTime()
            if (nextFrameTimeNs == 0L) {
                nextFrameTimeNs = nowNs
            }

            if (nowNs >= nextFrameTimeNs) {
                renderLatestFrame()
                // Skip missed slots so a slow render doesn't burst later.
                do {
                    nextFrameTimeNs += intervalNs
                } while (nextFrameTimeNs <= nowNs)
            }

            val delayMs = ((nextFrameTimeNs - System.nanoTime() + 999_999L) / 1_000_000L)
                .coerceAtLeast(0L)
            glHandler.postDelayed(this, delayMs)
        }
    }

    private val pendingSnapshots = mutableListOf<PendingSnapshot>()

    init {
        Logger.d(TAG, "Setting dynamic range profile to $dynamicRangeProfile")

        val future = submitSafely {
            renderer.init(dynamicRangeProfile)
        }
        try {
            future.get()
        } catch (e: Exception) {
            release()
            Logger.e(TAG, "Error while initializing renderer", e)
            throw e
        }
    }

    override fun createInputSurface(
        surfaceSize: Size,
        timebase: Timebase,
        isSecondary: Boolean
    ): Surface {
        if (isReleaseRequested.get()) {
            throw IllegalStateException("SurfaceProcessor is released")
        }
        require(cfrFps <= 0 || surfaceInputs.isEmpty()) {
            "CFR mode supports only a single input surface"
        }

        val future = submitSafely {
            if (isReleaseRequested.get()) {
                throw IllegalStateException("SurfaceProcessor is released")
            }

            val textureId = if (isSecondary) {
                renderer.createTextureName()
            } else {
                renderer.textureName
            }

            val surfaceTexture = SurfaceTexture(textureId)
            surfaceTexture.setDefaultBufferSize(surfaceSize.width, surfaceSize.height)
            surfaceTexture.setOnFrameAvailableListener(this, glHandler)
            if (dynamicRangeProfile.isHdr) {
                renderer.setInputFormat(GLUtils.InputFormat.YUV)
            }

            surfaceInputsToTimeConverterMap[surfaceTexture] = VideoTimebaseConverter(
                timebase,
                TimeUtils.systemTimeProvider
            )
            surfaceInputsToMatrixMap[surfaceTexture] = FloatArray(16)
            val surfaceInput =
                SurfaceInput(Surface(surfaceTexture), surfaceTexture, textureId, surfaceSize)
            surfaceInputs.add(surfaceInput)
            surfaceInput
        }

        val surfaceInput = future.get()

        // Start CFR render loop when input is created, if enabled
        executeSafely {
            if (cfrFps > 0 && !isRenderLoopRunning) {
                isRenderLoopRunning = true
                nextFrameTimeNs = 0L
                glHandler.post(renderRunnable)
                Logger.i(TAG, "Started CFR render loop at $cfrFps fps")
            }
        }

        return surfaceInput.surface
    }

    override fun removeInputSurface(surface: Surface) {
        if (isReleaseRequested.get()) {
            Logger.w(TAG, "SurfaceProcessor is released")
            return
        }
        executeSafely {
            val surfaceInput = surfaceInputs.find { it.surface == surface }
            if (surfaceInput != null) {
                val surfaceTexture = surfaceInput.surfaceTexture
                surfaceTexture.setOnFrameAvailableListener(null, glHandler)
                surfaceTexture.release()
                surface.release()

                if (surfaceInput.textureId != renderer.textureName) {
                    renderer.deleteTextureName(surfaceInput.textureId)
                }

                surfaceInputsToTimeConverterMap.remove(surfaceTexture)
                surfaceInputsToMatrixMap.remove(surfaceTexture)
                surfaceInputs.remove(surfaceInput)

                if (surfaceInputs.isEmpty() && isRenderLoopRunning && !isTransitioning) {
                    isRenderLoopRunning = false
                    glHandler.removeCallbacks(renderRunnable)
                }

                checkReadyToRelease()
            } else {
                Logger.w(TAG, "Surface not found")
            }
        }
    }

    override fun setTimebase(surface: Surface, timebase: Timebase) {
        executeSafely {
            val surfaceInput = surfaceInputs.find { it.surface == surface }
            if (surfaceInput != null) {
                surfaceInputsToTimeConverterMap[surfaceInput.surfaceTexture] =
                    VideoTimebaseConverter(
                        timebase,
                        TimeUtils.systemTimeProvider
                    )
            } else {
                Logger.w(TAG, "Surface not found")
            }
        }
    }

    override fun addOutputSurface(surfaceOutput: ISurfaceOutput) {
        if (isReleaseRequested.get()) {
            throw IllegalStateException("SurfaceProcessor is released")
        }

        executeSafely {
            if (isReleaseRequested.get()) {
                throw IllegalStateException("SurfaceProcessor is released")
            }
            if (!surfaceOutputs.map { it.targetSurface }.contains(surfaceOutput.targetSurface)) {
                renderer.registerOutputSurface(surfaceOutput.targetSurface, surfaceOutput.viewportRect)
                surfaceOutputs.add(surfaceOutput)
            } else {
                Logger.w(TAG, "Surface already added")
            }
        }
    }

    private fun removeOutputSurfaceInternal(surfaceOutput: ISurfaceOutput) {
        if (surfaceOutputs.contains(surfaceOutput)) {
            renderer.unregisterOutputSurface(surfaceOutput.targetSurface)
            surfaceOutputs.remove(surfaceOutput)
        } else {
            Logger.w(TAG, "Surface not found")
        }
    }

    override fun removeOutputSurface(surfaceOutput: ISurfaceOutput) {
        if (isReleaseRequested.get()) {
            Logger.w(TAG, "SurfaceProcessor is released")
            return
        }

        executeSafely {
            if (isReleaseRequested.get()) {
                Logger.w(TAG, "SurfaceProcessor is released")
                return@executeSafely
            }
            removeOutputSurfaceInternal(surfaceOutput)
        }
    }

    override fun removeOutputSurface(surface: Surface) {
        if (isReleaseRequested.get()) {
            Logger.w(TAG, "SurfaceProcessor is released")
            return
        }

        executeSafely {
            if (isReleaseRequested.get()) {
                Logger.w(TAG, "SurfaceProcessor is released")
                return@executeSafely
            }
            val surfaceOutput =
                surfaceOutputs.firstOrNull { it.targetSurface == surface }
            if (surfaceOutput != null) {
                removeOutputSurfaceInternal(surfaceOutput)
            } else {
                Logger.w(TAG, "Surface not found")
            }
        }
    }

    private fun removeAllOutputSurfacesInternal() {
        surfaceOutputs.forEach { surfaceOutput ->
            renderer.unregisterOutputSurface(surfaceOutput.targetSurface)
        }
        surfaceOutputs.clear()
    }

    override fun removeAllOutputSurfaces() {
        if (isReleaseRequested.get()) {
            Logger.w(TAG, "SurfaceProcessor is released")
            return
        }

        executeSafely {
            if (isReleaseRequested.get()) {
                Logger.w(TAG, "SurfaceProcessor is released")
                return@executeSafely
            }
            removeAllOutputSurfacesInternal()
        }
    }

    override fun release() {
        if (isReleaseRequested.getAndSet(true)) {
            return
        }
        executeSafely(block = {
            if (!isReleased) {
                isReleased = true
                isTransitioning = false
                isRenderLoopRunning = false
                glHandler.removeCallbacks(renderRunnable)
                glHandler.removeCallbacks(transitionTimeoutRunnable)

                checkReadyToRelease()
            }
        })
    }

    private fun checkReadyToRelease() {
        if (isReleased && surfaceInputs.isEmpty()) {
            // Once release is called, we can stop sending frame to output surfaces.
            removeAllOutputSurfacesInternal()

            renderer.release()
            glThread.quit()
        }
    }

    override fun startTransition(fps: Int) {
        if (isReleaseRequested.get()) return
        executeSafely {
            if (isReleaseRequested.get() || isReleased) return@executeSafely
            val targetFps = if (fps > 0) fps else 30
            transitionIntervalNs = 1_000_000_000L / targetFps
            isTransitioning = true
            hasFirstFrame = false
            nextFrameTimeNs = 0L

            glHandler.removeCallbacks(transitionTimeoutRunnable)
            glHandler.postDelayed(transitionTimeoutRunnable, TRANSITION_TIMEOUT_MS)

            if (!isRenderLoopRunning) {
                isRenderLoopRunning = true
                glHandler.post(renderRunnable)
                Logger.i(TAG, "Started transition black frame loop at $targetFps fps")
            }
        }
    }

    override fun stopTransition() {
        if (isReleaseRequested.get()) return
        executeSafely {
            if (isReleaseRequested.get() || isReleased) return@executeSafely
            stopTransitionInternal()
        }
    }

    private fun stopTransitionInternal() {
        glHandler.removeCallbacks(transitionTimeoutRunnable)
        if (isTransitioning) {
            isTransitioning = false
            nextFrameTimeNs = 0L
            Logger.i(TAG, "Stopped transition black frame loop")
            if (cfrFps <= 0 && isRenderLoopRunning) {
                isRenderLoopRunning = false
                glHandler.removeCallbacks(renderRunnable)
            }
        }
    }

    /**
     * Takes a snapshot of the current video frame.
     *
     * The snapshot is returned as a [Bitmap].
     *
     * @param rotationDegrees The rotation to apply to the snapshot, in degrees. 0 means no rotation.
     * @return The snapshot as a [Bitmap].
     */
    override suspend fun takeSnapshot(rotationDegrees: Int): Bitmap {
        return suspendCoroutine { continuation ->
            val listener = snapshot(rotationDegrees)
            try {
                val bitmap = listener.get()
                continuation.resume(bitmap)
            } catch (e: Exception) {
                continuation.resumeWith(Result.failure(e))
            }
        }
    }

    private fun snapshot(
        @IntRange(from = 0, to = 359) rotationDegrees: Int
    ): ListenableFuture<Bitmap> {
        if (isReleaseRequested.get()) {
            throw IllegalStateException("SurfaceProcessor is released")
        }
        if (isMuted) {
            throw IllegalStateException("Snapshot is not available when video is muted")
        }
        return CallbackToFutureAdapter.getFuture { completer ->
            executeSafely {
                pendingSnapshots.add(PendingSnapshot(rotationDegrees, completer))
            }
        }
    }

    private fun isPrimaryInput(surfaceTexture: SurfaceTexture): Boolean {
        val resolver = inputResolver ?: return true
        val input = surfaceInputs.find { it.surfaceTexture == surfaceTexture } ?: return false
        return !resolver.isSecondaryInput(input.surface)
    }

    // Executed on GL thread
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        if (isReleaseRequested.get()) {
            return
        }
        val timeConverter = surfaceInputsToTimeConverterMap[surfaceTexture] ?: return

        // Guard against race condition where frame callback fires after surface is released
        // This can happen when removeInputSurface is called while a frame is being processed
        try {
            surfaceTexture.updateTexImage()
        } catch (e: RuntimeException) {
            // Surface was already released or in invalid state - ignore this frame
            Logger.w(TAG, "updateTexImage failed (surface likely released): ${e.message}")
            return
        }
        val transform = surfaceInputsToMatrixMap[surfaceTexture] ?: FloatArray(16)
        surfaceTexture.getTransformMatrix(transform)
        surfaceTexture.getTransformMatrix(textureMatrix)

        surfaceInputs.find { it.surfaceTexture == surfaceTexture }?.hasFrame = true

        val isPrimary = isPrimaryInput(surfaceTexture)
        if (isPrimary) {
            latestTimestampNs = timeConverter.convertToUptimeNs(surfaceTexture.timestamp)
            hasFirstFrame = true

            if (isTransitioning) {
                stopTransitionInternal()
            }

            if (cfrFps == 0) {
                // Standard event-driven render
                renderLatestFrame()
            }
        }

        // Surface, size and transform matrix for JPEG Surface if exists
        if (pendingSnapshots.isNotEmpty() && isPrimary) {
            try {
                val bitmapSurface = selectSnapshotSurface(surfaceOutputs)
                    ?: throw IllegalStateException(
                        "No output surface available for snapshot"
                    )

                // Execute all pending snapshots.
                takeSnapshot(bitmapSurface.targetResolution, surfaceOutputMatrix.clone())
            } catch (e: RuntimeException) {
                // Propagates error back to the app if failed to take snapshot.
                failAllPendingSnapshots(e)
            }
        }
    }

    // Executed on GL thread continuously (CFR/Transition) or onFrameAvailable (Event-driven)
    private fun renderLatestFrame() {
        if (isTransitioning) {
            renderBlackFrame(System.nanoTime())
            return
        }

        if (!hasFirstFrame || surfaceInputs.isEmpty()) return

        val renderTimestampNs = if (cfrFps > 0) {
            System.nanoTime()
        } else {
            latestTimestampNs
        }

        val resolver = inputResolver
        if (resolver == null) {
            renderSingleInput(renderTimestampNs)
        } else {
            compositor.render(
                renderTimestampNs,
                surfaceInputs,
                surfaceInputsToMatrixMap,
                surfaceOutputs.filterIsInstance<SurfaceOutput>(),
                resolver,
                isMuted,
                surfaceOutputMatrix
            )
        }
    }

    private fun renderBlackFrame(renderTimestampNs: Long) {
        surfaceOutputs.filterIsInstance<SurfaceOutput>().forEach {
            try {
                if (it.isStreaming()) {
                    renderer.render(
                        renderTimestampNs,
                        surfaceOutputMatrix,
                        it.targetSurface,
                        isMuted = true
                    )
                }
            } catch (t: Throwable) {
                Logger.e(TAG, "Error while rendering black transition frame", t)
            }
        }
    }

    // Upstream path: draws the primary input only. Also used while a source switch briefly
    // holds two inputs without a dual source, so the incoming input is never shown as PiP.
    private fun renderSingleInput(renderTimestampNs: Long) {
        val snapshotSurface = selectSnapshotSurface(surfaceOutputs)
        surfaceOutputs.filterIsInstance<SurfaceOutput>().forEach {
            try {
                it.updateTransformMatrix(singleInputOutputMatrix, textureMatrix)
                // Keep surfaceOutputMatrix aligned with the snapshot target (and unmirrored when
                // possible so mirrored front-camera preview does not flip snapshots).
                if (it === snapshotSurface) {
                    singleInputOutputMatrix.copyInto(surfaceOutputMatrix)
                }
                if (it.isStreaming()) {
                    renderer.render(
                        renderTimestampNs,
                        singleInputOutputMatrix,
                        it.targetSurface,
                        isMuted
                    )
                }
            } catch (t: Throwable) {
                Logger.e(TAG, "Error while rendering frame", t)
            }
        }
    }

    /**
     * Takes a snapshot of the current frame and draws it to given JPEG surface.
     *
     * @param snapshotSize The size of the snapshot.
     * @param snapshotTransform The GL transform matrix to apply to the snapshot.
     */
    private fun takeSnapshot(snapshotSize: Size, snapshotTransform: FloatArray) {
        if (pendingSnapshots.isEmpty()) {
            // No pending snapshot requests, do nothing.
            return
        }

        // Write to Bitmap, once for each snapshot request.
        try {
            for (pendingSnapshot in pendingSnapshots) {
                try {
                    // Take a snapshot of the current frame.
                    val bitmap =
                        getBitmap(snapshotSize, snapshotTransform, pendingSnapshot.rotationDegrees)

                    // Complete the snapshot request.
                    pendingSnapshot.completer.set(bitmap)
                } catch (t: Throwable) {
                    // Propagate error back to the app if failed to take snapshot.
                    pendingSnapshot.completer.setException(t)
                }
            }
        } finally {
            pendingSnapshots.clear()
        }
    }

    private fun failAllPendingSnapshots(throwable: Throwable) {
        for (pendingSnapshot in pendingSnapshots) {
            pendingSnapshot.completer.setException(throwable)
        }
    }

    private fun getBitmap(
        size: Size,
        textureTransform: FloatArray,
        rotationDegrees: Int
    ): Bitmap {
        val snapshotTransform = textureTransform.clone()

        // Rotate the output if requested.
        snapshotTransform.preRotate(rotationDegrees.toFloat(), 0.5f, 0.5f)

        // Flip the snapshot. This is for reverting the GL transform added in SurfaceOutputImpl.
        snapshotTransform.preVerticalFlip(0.5f)

        // Update the size based on the rotation degrees.
        val rotatedSize = size.rotate(rotationDegrees)

        // Take a snapshot Bitmap and compress it to JPEG.
        return renderer.snapshot(rotatedSize, snapshotTransform)
    }

    private fun executeSafely(
        block: () -> Unit,
    ) {
        executeSafely(block, {}, {})
    }

    private fun <T> executeSafely(
        block: () -> T, onSuccess: ((T) -> Unit), onError: ((Throwable) -> Unit)
    ) {
        try {
            glHandler.post {
                if (isReleased) {
                    Logger.w(TAG, "SurfaceProcessor is released, block will not be executed")
                    onError(IllegalStateException("SurfaceProcessor is released"))
                } else {
                    try {
                        onSuccess(block())
                    } catch (t: Throwable) {
                        onError(t)
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "Error while executing block", t)
            onError(t)
        }
    }

    private fun <T : Any> submitSafely(block: () -> T): ListenableFuture<T> {
        return CallbackToFutureAdapter.getFuture {
            executeSafely(block, { result -> it.set(result) }, { t -> it.setException(t) })
        }
    }

    companion object {
        private const val TAG = "SurfaceProcessor"
        private const val TRANSITION_TIMEOUT_MS = 3000L
    }

    private data class PendingSnapshot(
        @IntRange(from = 0, to = 359)
        val rotationDegrees: Int,
        val completer: CallbackToFutureAdapter.Completer<Bitmap>
    )
}

@OptIn(InternalStreamPackApi::class)
class DefaultSurfaceProcessorFactory(private val cfrFps: Int = 0) :
    ISurfaceProcessor.Factory {
    override fun create(
        context: Context,
        dynamicRangeProfile: DynamicRangeProfile,
        dispatcherProvider: IVideoDispatcherProvider
    ): ISurfaceProcessorInternal {
        return DefaultSurfaceProcessor(
            dynamicRangeProfile,
            dispatcherProvider.createVideoHandlerExecutor(THREAD_NAME_GL),
            cfrFps
        )
    }
}

@OptIn(InternalStreamPackApi::class)
internal fun selectSnapshotSurface(surfaceOutputs: List<ISurfaceOutput>): ISurfaceOutput? {
    val typedOutputs = surfaceOutputs.filterIsInstance<SurfaceOutput>()
    return typedOutputs.filter { !it.needMirroring }
        .maxByOrNull { it.targetResolution.width * it.targetResolution.height }
        ?: typedOutputs.maxByOrNull { it.targetResolution.width * it.targetResolution.height }
        ?: surfaceOutputs.maxByOrNull { it.targetResolution.width * it.targetResolution.height }
}