package org.stypox.dicio.skills.fallback.llm

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.llm.LlmInferenceEngine
import org.stypox.dicio.skills.fallback.text.TextFallbackOutput
import org.stypox.dicio.util.RecognizeEverythingSkill

class LlmFallbackSkill(
    correspondingSkillInfo: SkillInfo,
    private val engine: LlmInferenceEngine,
) : RecognizeEverythingSkill(correspondingSkillInfo) {
    override suspend fun generateOutput(ctx: SkillContext, inputData: String): SkillOutput {
        if (!engine.isReady) {
            return TextFallbackOutput(askToRepeat = false)
        }
        return LlmFallbackOutput(engine.generate(inputData))
    }
}
