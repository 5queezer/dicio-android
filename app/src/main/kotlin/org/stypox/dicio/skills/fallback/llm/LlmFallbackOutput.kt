package org.stypox.dicio.skills.fallback.llm

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.InteractionPlan
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput

class LlmFallbackOutput(
    private val response: String,
) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = response

    override fun getInteractionPlan(ctx: SkillContext) =
        InteractionPlan.Continue(reopenMicrophone = false)
}
