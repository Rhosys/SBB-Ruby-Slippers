package ch.rhosys.sbb.ui.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries a "plan this trip" request from anywhere (Home's tile taps/drags) into the
 * Search bottom-tab, which otherwise always shows whatever was last searched. The Search
 * screen is a single persistent destination — this is how another screen pushes a new
 * from/to into it without a navigation argument forcing the tab to reset or duplicate.
 */
@Singleton
class SearchNavigationBridge @Inject constructor() {
    data class Request(val from: String, val to: String, val nonce: Long = System.nanoTime())

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    fun request(from: String, to: String) {
        _pending.value = Request(from, to)
    }

    fun consume() {
        _pending.value = null
    }
}
