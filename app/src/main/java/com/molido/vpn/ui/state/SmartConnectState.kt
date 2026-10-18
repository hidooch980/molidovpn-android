package com.molido.vpn.ui.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * UI state of Smart Connect, written by the connection engine (service process) and observed by
 * the home screen. One immutable snapshot per change; the screen never asks the service anything.
 *
 * Same-process singleton on purpose: [com.molido.vpn.MainActivity] is a plain `Activity`
 * (no AndroidX lifecycle), so this plays the ViewModel role as a process-scoped StateFlow holder.
 */
object SmartConnectState {

    enum class Phase { IDLE, PROBING, TESTING, CONNECTING, CONNECTED, FAILED }
    enum class Status { QUEUED, PROBING, TESTING, OK, FAILED, SKIPPED }

    data class MethodState(
        val id: String,
        val label: String,
        val status: Status = Status.QUEUED,
        val score: Int? = null,
        val latencyMs: Long? = null,
        val detail: String = "",
    )

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val methods: List<MethodState> = emptyList(),
        val leader: String? = null,
        val winner: String? = null,
        val winnerReason: String = "",
        val runnerUp: String? = null,
        val runnerUpScore: Int? = null,
        val network: String = "",
        val pingMs: Long? = null,
        val downKbps: Long? = null,
        val upKbps: Long? = null,
        val startedAt: Long = 0L,
    ) {
        val tested: Int get() = methods.count { it.status == Status.OK || it.status == Status.FAILED || it.status == Status.SKIPPED }
        /** 0..100 for the progress ring. */
        val progress: Int get() = if (methods.isEmpty()) 0 else tested * 100 / methods.size
        fun label(id: String?): String? = methods.firstOrNull { it.id == id }?.label ?: id
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun begin(network: String, methods: List<Pair<String, String>>, now: Long) {
        _state.value = UiState(
            phase = Phase.PROBING,
            methods = methods.map { MethodState(it.first, it.second) },
            network = network,
            startedAt = now,
        )
    }

    fun phase(p: Phase) = _state.update { it.copy(phase = p) }

    fun method(id: String, status: Status, score: Int? = null, latencyMs: Long? = null, detail: String = "") =
        _state.update { s ->
            s.copy(methods = s.methods.map {
                if (it.id == id) it.copy(status = status, score = score ?: it.score, latencyMs = latencyMs ?: it.latencyMs,
                    detail = detail.ifEmpty { it.detail }) else it
            })
        }

    fun leader(id: String?) = _state.update { it.copy(leader = id) }

    fun result(winner: String?, reason: String, runnerUp: String?, runnerUpScore: Int?) = _state.update {
        it.copy(winner = winner, winnerReason = reason, runnerUp = runnerUp, runnerUpScore = runnerUpScore,
            phase = if (winner == null) Phase.FAILED else Phase.CONNECTING)
    }

    fun connected() = _state.update { it.copy(phase = Phase.CONNECTED) }

    fun live(pingMs: Long?, downKbps: Long?, upKbps: Long?) =
        _state.update { it.copy(pingMs = pingMs ?: it.pingMs, downKbps = downKbps ?: it.downKbps, upKbps = upKbps ?: it.upKbps) }

    fun reset() { _state.value = UiState() }

    /** For screenshot tests and previews. */
    fun set(s: UiState) { _state.value = s }
}
