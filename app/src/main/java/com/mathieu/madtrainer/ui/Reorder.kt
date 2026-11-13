package com.mathieu.madtrainer.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Réordonnancement par glisser-déposer d'une liste verticale (appui long sur la poignée ⋮⋮ puis glisser).
 * Sans bibliothèque externe : on échange l'élément avec son voisin dès qu'on dépasse la moitié de sa hauteur.
 */
class ReorderState {
    var draggedIndex by mutableStateOf<Int?>(null)
    var offset by mutableFloatStateOf(0f)
    val heights = mutableStateMapOf<Int, Int>()
}

@Composable
fun rememberReorderState() = remember { ReorderState() }

/** À appliquer sur chaque élément de la liste (mesure + déplacement visuel de l'élément saisi). */
fun Modifier.reorderItem(state: ReorderState, index: Int): Modifier =
    this
        .onGloballyPositioned { state.heights[index] = it.size.height }
        .zIndex(if (state.draggedIndex == index) 1f else 0f)
        .graphicsLayer {
            if (state.draggedIndex == index) {
                translationY = state.offset
                scaleX = 1.02f
                scaleY = 1.02f
                shadowElevation = 12f
            }
        }

/** Poignée de glisser-déposer (⋮⋮). [onMove] échange deux éléments de la liste. */
@Composable
fun DragHandle(state: ReorderState, index: Int, count: Int, onMove: (from: Int, to: Int) -> Unit, modifier: Modifier = Modifier) {
    val currentIndex by rememberUpdatedState(index)
    val currentCount by rememberUpdatedState(count)
    val move by rememberUpdatedState(onMove)
    Icon(
        Icons.Filled.DragIndicator,
        contentDescription = "Appui long puis glisser pour déplacer",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .size(28.dp)
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        state.draggedIndex = currentIndex
                        state.offset = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val cur = state.draggedIndex
                        if (cur != null) {
                            state.offset += amount.y
                            val down = state.heights[cur + 1]?.toFloat() ?: 120f
                            val up = state.heights[cur - 1]?.toFloat() ?: 120f
                            if (state.offset > down / 2 && cur < currentCount - 1) {
                                move(cur, cur + 1)
                                state.draggedIndex = cur + 1
                                state.offset -= down
                            } else if (state.offset < -up / 2 && cur > 0) {
                                move(cur, cur - 1)
                                state.draggedIndex = cur - 1
                                state.offset += up
                            }
                        }
                    },
                    onDragEnd = {
                        state.draggedIndex = null
                        state.offset = 0f
                    },
                    onDragCancel = {
                        state.draggedIndex = null
                        state.offset = 0f
                    },
                )
            },
    )
}

/** Échange deux éléments d'une liste (utilitaire pour [DragHandle]). */
fun <T> List<T>.swapped(a: Int, b: Int): List<T> =
    if (a !in indices || b !in indices) this else toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }
