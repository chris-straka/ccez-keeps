package dev.cstraka.keeps.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cstraka.keeps.auth.AuthStore
import dev.cstraka.keeps.sync.ApiException
import dev.cstraka.keeps.sync.KeepsApi
import dev.cstraka.keeps.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface EnrollState {
    data object Idle : EnrollState
    data object WaitingInBrowser : EnrollState
    data object Exchanging : EnrollState
    data class Failed(val message: String) : EnrollState
}

/**
 * Enrollment: open the Access login in a Custom Tab (system browser UI, the
 * app never sees the password), the server 302s to keeps://enroll?code=...,
 * the Activity hands the code here, and exchange() swaps it for the
 * permanent device token. Browser never opens again after this.
 */
class EnrollViewModel(
    private val activity: androidx.activity.ComponentActivity,
    private val api: KeepsApi,
    private val auth: AuthStore,
) : ViewModel() {

    private val _state = MutableStateFlow<EnrollState>(EnrollState.Idle)
    val state: StateFlow<EnrollState> = _state

    val deviceName = MutableStateFlow(android.os.Build.MODEL ?: "android")

    fun startLogin() {
        val url = api.enrollCodeUrl("keeps://enroll")
        CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(url))
        _state.value = EnrollState.WaitingInBrowser
    }

    fun exchange(code: String, onDone: () -> Unit) {
        if (_state.value is EnrollState.Exchanging) return
        _state.value = EnrollState.Exchanging
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    api.exchange(code, deviceName.value.take(120))
                }
                auth.saveEnrollment(res.deviceId, res.token, deviceName.value.take(120))
                SyncWorker.scheduleNow(activity)
                onDone()
            } catch (e: ApiException) {
                android.util.Log.w("KeepsEnroll", "exchange HTTP ${e.status}", e)
                _state.value = EnrollState.Failed(
                    when (e.status) {
                        404 -> "That login code was already used. Start over."
                        410 -> "That login code expired (10 min). Start over."
                        else -> "Enrollment failed (${e.status}). Try again."
                    },
                )
            } catch (e: Exception) {
                android.util.Log.e("KeepsEnroll", "exchange failed", e)
                _state.value = EnrollState.Failed("No connection. Try again when online.")
            }
        }
    }

    fun retry() {
        _state.value = EnrollState.Idle
    }
}
