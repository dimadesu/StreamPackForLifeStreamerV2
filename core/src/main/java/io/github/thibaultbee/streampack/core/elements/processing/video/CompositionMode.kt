/*
 * Copyright (C) 2026 Thibault B.
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
package io.github.thibaultbee.streampack.core.elements.processing.video

import android.graphics.RectF

/**
 * Defines the layout configuration for compositing multiple video input streams into a single frame.
 */
enum class CompositionMode {
    /**
     * Single input source (full screen).
     */
    SINGLE,

    /**
     * Picture-in-Picture: Primary camera full screen, secondary camera in top-right overlay.
     */
    PIP_TOP_RIGHT,

    /**
     * Picture-in-Picture: Primary camera full screen, secondary camera in top-left overlay.
     */
    PIP_TOP_LEFT,

    /**
     * Picture-in-Picture: Primary camera full screen, secondary camera in bottom-right overlay.
     */
    PIP_BOTTOM_RIGHT,

    /**
     * Picture-in-Picture: Primary camera full screen, secondary camera in bottom-left overlay.
     */
    PIP_BOTTOM_LEFT,

    /**
     * Split screen: Primary camera on left 50%, secondary camera on right 50%.
     */
    SIDE_BY_SIDE,

    /**
     * Split screen: Primary camera on top 50%, secondary camera on bottom 50%.
     */
    TOP_BOTTOM;

    /**
     * Returns normalized target rectangles [left, top, right, bottom] in range [0..1]
     * for each input given the total count of inputs.
     */
    fun getNormalizedRects(inputCount: Int): List<RectF> {
        if (inputCount <= 1 || this == SINGLE) {
            return List(inputCount) { RectF(0f, 0f, 1f, 1f) }
        }

        return when (this) {
            PIP_TOP_RIGHT -> listOf(
                RectF(0f, 0f, 1f, 1f),
                RectF(0.65f, 0.05f, 0.95f, 0.35f)
            )
            PIP_TOP_LEFT -> listOf(
                RectF(0f, 0f, 1f, 1f),
                RectF(0.05f, 0.05f, 0.35f, 0.35f)
            )
            PIP_BOTTOM_RIGHT -> listOf(
                RectF(0f, 0f, 1f, 1f),
                RectF(0.65f, 0.65f, 0.95f, 0.95f)
            )
            PIP_BOTTOM_LEFT -> listOf(
                RectF(0f, 0f, 1f, 1f),
                RectF(0.05f, 0.65f, 0.35f, 0.95f)
            )
            SIDE_BY_SIDE -> listOf(
                RectF(0f, 0f, 0.5f, 1f),
                RectF(0.5f, 0f, 1f, 1f)
            )
            TOP_BOTTOM -> listOf(
                RectF(0f, 0f, 1f, 0.5f),
                RectF(0f, 0.5f, 1f, 1f)
            )
            else -> List(inputCount) { RectF(0f, 0f, 1f, 1f) }
        }
    }
}
