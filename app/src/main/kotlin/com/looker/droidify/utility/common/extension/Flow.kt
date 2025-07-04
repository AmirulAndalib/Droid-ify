package com.looker.droidify.utility.common.extension

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

context(viewModel: ViewModel)
fun <T> Flow<T>.asStateFlow(
    initialValue: T,
    scope: CoroutineScope = viewModel.viewModelScope,
    started: SharingStarted = SharingStarted.WhileSubscribed(5_000),
): StateFlow<T> = stateIn(
    scope = scope,
    started = started,
    initialValue = initialValue,
)

context(scope: CoroutineScope)
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> ReceiveChannel<T>.filter(
    block: suspend (T) -> Boolean,
): ReceiveChannel<T> = scope.produce(capacity = Channel.UNLIMITED) {
    consumeEach { item ->
        if (block(item)) send(item)
    }
}
