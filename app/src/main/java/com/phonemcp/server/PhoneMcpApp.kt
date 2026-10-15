package com.phonemcp.server

import android.app.Application
import com.phonemcp.server.data.ExternalCapabilityStore
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class PhoneMcpApp : Application() {
    @Inject
    lateinit var capabilityStore: ExternalCapabilityStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Injected fields are available after super.onCreate() (generated base).
        scope.launch { capabilityStore.restore() }
        capabilityStore.watch()
    }
}
