package com.example.freevpn

import android.content.Context
import android.content.Intent
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.StringReader
import java.util.Locale

enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

data class TrafficStats(
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxBytesPerSec: Long = 0L,
    val txBytesPerSec: Long = 0L
)

class VpnController private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val repository = ProfileRepository(appContext)
    private val backend by lazy { GoBackend(appContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hotspotProxy = VpnHotspotProxy(PROXY_PORT)

    private var statsJob: Job? = null

    private val _profiles = MutableStateFlow<List<ServerProfile>>(emptyList())
    val profiles: StateFlow<List<ServerProfile>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    private val _status = MutableStateFlow(VpnStatus.DISCONNECTED)
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _trafficStats = MutableStateFlow(TrafficStats())
    val trafficStats: StateFlow<TrafficStats> = _trafficStats.asStateFlow()

    private val _proxyEnabled = MutableStateFlow(false)
    val proxyEnabled: StateFlow<Boolean> = _proxyEnabled.asStateFlow()

    private val _hotspotIp = MutableStateFlow(VpnHotspotProxy.detectLocalHotspotIp())
    val hotspotIp: StateFlow<String> = _hotspotIp.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        val loaded = repository.loadProfiles()
        _profiles.value = loaded
        val savedActiveId = repository.getActiveProfileId()
        _activeProfileId.value = loaded.firstOrNull { it.id == savedActiveId }?.id
            ?: loaded.firstOrNull()?.id
    }

    private val tunnel = object : Tunnel {
        override fun getName(): String = "free_vpn_tunnel"

        override fun onStateChange(newState: Tunnel.State) {
            when (newState) {
                Tunnel.State.UP -> {
                    _status.value = VpnStatus.CONNECTED
                    startStatsPolling()
                }
                Tunnel.State.DOWN -> {
                    _status.value = VpnStatus.DISCONNECTED
                    stopStatsPolling()
                }
                else -> Unit
            }
        }
    }

    fun preparePermission(): Intent? = GoBackend.VpnService.prepare(appContext)

    fun toggleHotspotProxy(enable: Boolean) {
        if (enable) {
            refreshHotspotIp()
            hotspotProxy.start { err -> _errorMessage.value = err }
            _proxyEnabled.value = true
        } else {
            hotspotProxy.stop()
            _proxyEnabled.value = false
        }
    }

    fun refreshHotspotIp() {
        _hotspotIp.value = VpnHotspotProxy.detectLocalHotspotIp()
    }

    fun selectProfile(id: String) {
        if (_activeProfileId.value == id) return
        _activeProfileId.value = id
        repository.setActiveProfileId(id)

        if (_status.value == VpnStatus.CONNECTED) {
            scope.launch {
                disconnect()
                connectActiveProfile()
            }
        }
    }

    fun upsertProfile(profile: ServerProfile) {
        val current = _profiles.value.toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) current[index] = profile else current.add(profile)
        _profiles.value = current
        repository.saveProfiles(current)

        if (_activeProfileId.value == null) {
            selectProfile(profile.id)
        } else if (_activeProfileId.value == profile.id && _status.value == VpnStatus.CONNECTED) {
            scope.launch {
                disconnect()
                connectActiveProfile()
            }
        }
    }

    fun deleteProfile(id: String) {
        val current = _profiles.value.filterNot { it.id == id }
        _profiles.value = current
        repository.saveProfiles(current)

        if (_activeProfileId.value == id) {
            val nextId = current.firstOrNull()?.id
            _activeProfileId.value = nextId
            repository.setActiveProfileId(nextId)
            if (_status.value == VpnStatus.CONNECTED) {
                scope.launch { disconnect() }
            }
        }
    }

    fun connectActiveProfile() {
        val profile = _profiles.value.firstOrNull { it.id == _activeProfileId.value }
            ?: _profiles.value.firstOrNull()
        if (profile == null) {
            _errorMessage.value = "Add a server configuration first."
            return
        }

        scope.launch {
            _errorMessage.value = null
            _status.value = VpnStatus.CONNECTING

            withContext(Dispatchers.IO) {
                try {
                    val parsed = Config.parse(BufferedReader(StringReader(profile.config.trim())))
                    backend.setState(tunnel, Tunnel.State.UP, parsed)
                    _status.value = VpnStatus.CONNECTED
                    startStatsPolling()
                } catch (e: Exception) {
                    _status.value = VpnStatus.DISCONNECTED
                    stopStatsPolling()
                    _errorMessage.value = e.message ?: "Unable to establish tunnel"
                }
            }
        }
    }

    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
            } catch (_: Exception) {
            } finally {
                stopStatsPolling()
                _status.value = VpnStatus.DISCONNECTED
            }
        }
    }

    private fun startStatsPolling() {
        if (statsJob?.isActive == true) return
        statsJob = scope.launch(Dispatchers.IO) {
            var previousRx = 0L
            var previousTx = 0L
            while (isActive && _status.value == VpnStatus.CONNECTED) {
                try {
                    val stats = backend.getStatistics(tunnel)
                    val currentRx = stats.totalRx()
                    val currentTx = stats.totalTx()
                    _trafficStats.value = TrafficStats(
                        rxBytes = currentRx,
                        txBytes = currentTx,
                        rxBytesPerSec = (currentRx - previousRx).coerceAtLeast(0L),
                        txBytesPerSec = (currentTx - previousTx).coerceAtLeast(0L)
                    )
                    previousRx = currentRx
                    previousTx = currentTx
                } catch (_: Exception) {
                }
                delay(1000L)
            }
        }
    }

    private fun stopStatsPolling() {
        statsJob?.cancel()
        statsJob = null
        _trafficStats.value = TrafficStats()
    }

    companion object {
        const val PROXY_PORT = 8080

        @Volatile
        private var instance: VpnController? = null

        fun getInstance(context: Context): VpnController {
            return instance ?: synchronized(this) {
                instance ?: VpnController(context).also { instance = it }
            }
        }

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
            val gb = mb / 1024.0
            return String.format(Locale.US, "%.2f GB", gb)
        }
    }
}

