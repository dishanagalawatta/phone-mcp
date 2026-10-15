package com.phonemcp.server.di

import com.phonemcp.server.data.AudioProvider
import com.phonemcp.server.data.CameraProvider
import com.phonemcp.server.data.ExternalCapabilityProvider
import com.phonemcp.server.data.FileMediaStore
import com.phonemcp.server.data.LocationProvider
import com.phonemcp.server.data.NetInspectProvider
import com.phonemcp.server.data.OutputProvider
import com.phonemcp.server.data.RadioProvider
import com.phonemcp.server.data.SensorsProvider
import com.phonemcp.server.data.ServiceToolInvoker
import com.phonemcp.server.data.SystemProvider
import com.phonemcp.server.data.UiProvider
import com.phonemcp.server.domain.ExternalToolInvoker
import com.phonemcp.server.domain.MediaStore
import com.phonemcp.server.domain.ToolProvider
import com.phonemcp.server.service.ServerConfig
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ProvidersModule {
    @Binds @IntoSet abstract fun bindSensors(p: SensorsProvider): ToolProvider
    @Binds @IntoSet abstract fun bindLocation(p: LocationProvider): ToolProvider
    @Binds @IntoSet abstract fun bindAudio(p: AudioProvider): ToolProvider
    @Binds @IntoSet abstract fun bindCamera(p: CameraProvider): ToolProvider
    @Binds @IntoSet abstract fun bindOutput(p: OutputProvider): ToolProvider
    @Binds @IntoSet abstract fun bindRadio(p: RadioProvider): ToolProvider
    @Binds @IntoSet abstract fun bindSystem(p: SystemProvider): ToolProvider
    @Binds @IntoSet abstract fun bindNetInspect(p: NetInspectProvider): ToolProvider
    @Binds @IntoSet abstract fun bindUi(p: UiProvider): ToolProvider
    @Binds @IntoSet abstract fun bindExternal(p: ExternalCapabilityProvider): ToolProvider
    /** Bound-service transport behind ExternalCapabilityRegistry invocations. */
    @Binds abstract fun bindExternalInvoker(p: ServiceToolInvoker): ExternalToolInvoker
    @Binds abstract fun bindMediaStore(p: FileMediaStore): MediaStore
}

@Module
@InstallIn(SingletonComponent::class)
object ServerModule {
    @Provides @Singleton
    fun provideConfig(): ServerConfig = ServerConfig()
}
