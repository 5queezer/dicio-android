package org.stypox.dicio.eval

import android.content.Context
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.di.SkillContextImpl
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.llm.LlmInferenceEngine
import org.stypox.dicio.llm.LlmModelProvider
import org.stypox.dicio.llm.MockLlmEngine
import org.stypox.dicio.settings.datastore.FallbackMode
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.settings.datastore.UserSettingsModule
import org.stypox.dicio.skills.calculator.CalculatorInfo
import org.stypox.dicio.skills.current_time.CurrentTimeInfo
import org.stypox.dicio.skills.fallback.llm.LlmFallbackInfo
import org.stypox.dicio.skills.fallback.text.TextFallbackInfo
import org.stypox.dicio.skills.listening.ListeningInfo
import org.stypox.dicio.skills.lyrics.LyricsInfo
import org.stypox.dicio.skills.media.MediaInfo
import org.stypox.dicio.skills.navigation.NavigationInfo
import org.stypox.dicio.skills.notify.NotifyInfo
import org.stypox.dicio.skills.open.OpenInfo
import org.stypox.dicio.skills.search.SearchInfo
import org.stypox.dicio.skills.telephone.TelephoneInfo
import org.stypox.dicio.skills.timer.TimerInfo
import org.stypox.dicio.skills.translation.TranslationInfo
import org.stypox.dicio.skills.weather.WeatherInfo
import org.stypox.dicio.skills.joke.JokeInfo
import org.stypox.dicio.skills.flashlight.FlashlightInfo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillHandler @Inject constructor(
    private val dataStore: DataStore<UserSettings>,
    private val localeManager: LocaleManager,
    private val skillContext: SkillContextInternal,
    private val llmEngine: LlmInferenceEngine,
    private val llmModelProvider: LlmModelProvider,
) {
    // TODO improve id handling (maybe just use an int that can point to an Android resource)
    val allSkillInfoList = listOf(
        WeatherInfo,
        SearchInfo,
        LyricsInfo,
        OpenInfo,
        CalculatorInfo,
        NavigationInfo,
        TelephoneInfo,
        TimerInfo,
        CurrentTimeInfo,
        MediaInfo,
        JokeInfo,
        ListeningInfo(dataStore),
        TranslationInfo,
        NotifyInfo,
        FlashlightInfo,
    )

    private val scope = CoroutineScope(Dispatchers.Default)
    private val llmFallbackInfo = LlmFallbackInfo(llmEngine)

    // will be null when it has not been initialized yet
    private val _enabledSkillsInfo: MutableStateFlow<List<SkillInfo>?> = MutableStateFlow(null)
    val enabledSkillsInfo: StateFlow<List<SkillInfo>?> = _enabledSkillsInfo

    private val _skillRanker = MutableStateFlow(
        // an initial dummy value, will be overwritten directly by the launched job
        SkillRanker(listOf(), buildSkillFromInfo(TextFallbackInfo))
    )
    val skillRanker: StateFlow<SkillRanker> = _skillRanker

    init {
        scope.launch {
            var previousFallback: SkillInfo = TextFallbackInfo
            localeManager.locale
                .combine(dataStore.data) { locale, data ->
                    Triple(locale, data.enabledSkillsMap, data.fallbackMode)
                }
                .distinctUntilChanged()
                .collectLatest { (_, enabledSkills, fallbackMode) ->
                    // locale is not used here, because the skills directly use the sections locale

                    val newEnabledSkillsInfo = allSkillInfoList
                        .filter { enabledSkills.getOrDefault(it.id, true) }
                        .filter { it.isAvailable(skillContext) }

                    val activeFallback: SkillInfo = when {
                        fallbackMode == FallbackMode.FALLBACK_MODE_LLM &&
                                llmModelProvider.getModelPath() != null -> llmFallbackInfo
                        else -> TextFallbackInfo
                    }

                    // Release the LiteRT-LM native context (multi-GB weights) when leaving
                    // LLM fallback. Next call to generate() re-initializes.
                    if (previousFallback === llmFallbackInfo && activeFallback !== llmFallbackInfo) {
                        llmEngine.close()
                    }
                    previousFallback = activeFallback

                    _enabledSkillsInfo.value = newEnabledSkillsInfo
                    _skillRanker.value = SkillRanker(
                        newEnabledSkillsInfo.map(::buildSkillFromInfo),
                        buildSkillFromInfo(activeFallback),
                    )
                }
        }
    }

    private fun buildSkillFromInfo(skillInfo: SkillInfo): Skill<*> {
        return skillInfo.build(skillContext)
    }

    companion object {
        fun newForPreviews(context: Context): SkillHandler {
            return SkillHandler(
                UserSettingsModule.newDataStoreForPreviews(),
                LocaleManager.newForPreviews(context),
                SkillContextImpl.newForPreviews(context),
                MockLlmEngine(),
                object : LlmModelProvider { override fun getModelPath(): String? = null },
            )
        }
    }
}
