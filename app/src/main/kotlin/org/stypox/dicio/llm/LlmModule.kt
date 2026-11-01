package org.stypox.dicio.llm

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

const val REMOTE_LLM = "remote"

@Module
@InstallIn(SingletonComponent::class)
abstract class LlmModule {
    @Binds
    @Singleton
    abstract fun bindLlmInferenceEngine(real: RealLlmEngine): LlmInferenceEngine

    @Binds
    @Singleton
    @Named(REMOTE_LLM)
    abstract fun bindRemoteLlmInferenceEngine(remote: RemoteLlmEngine): LlmInferenceEngine

    @Binds
    @Singleton
    abstract fun bindLlmModelProvider(default: DefaultLlmModelProvider): LlmModelProvider
}
