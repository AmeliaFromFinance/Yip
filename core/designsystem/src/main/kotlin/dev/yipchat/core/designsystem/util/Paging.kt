package dev.yipchat.core.designsystem.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

/** Calls [onLoadMore] when the user scrolls within [threshold] items of the end. */
@Composable
fun LazyGridState.OnNearEnd(threshold: Int = 6, onLoadMore: () -> Unit) {
    val callback by rememberUpdatedState(onLoadMore)
    val nearEnd by remember {
        derivedStateOf {
            val info = layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - threshold
        }
    }
    LaunchedEffect(this) { snapshotFlow { nearEnd }.collect { if (it) callback() } }
}

@Composable
fun LazyListState.OnNearEnd(threshold: Int = 6, onLoadMore: () -> Unit) {
    val callback by rememberUpdatedState(onLoadMore)
    val nearEnd by remember {
        derivedStateOf {
            val info = layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - threshold
        }
    }
    LaunchedEffect(this) { snapshotFlow { nearEnd }.collect { if (it) callback() } }
}

/** Simple cursor pager state shared by list view models. */
data class Paged<T>(
    val items: List<T> = emptyList(),
    val cursor: String? = null,
    val loading: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
    val initialized: Boolean = false,
)
