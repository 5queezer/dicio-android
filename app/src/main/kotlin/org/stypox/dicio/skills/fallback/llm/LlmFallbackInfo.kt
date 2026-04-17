package org.stypox.dicio.skills.fallback.llm

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.llm.LlmInferenceEngine

class LlmFallbackInfo(
    private val engine: LlmInferenceEngine,
) : SkillInfo("llm") {
    override fun name(context: Context) = "LLM fallback"

    override fun sentenceExample(context: Context) = ""

    @Composable
    override fun icon() = rememberVectorPainter(Icons.Default.AutoAwesome)

    override fun isAvailable(ctx: SkillContext): Boolean = true

    override fun build(ctx: SkillContext): Skill<*> = LlmFallbackSkill(this, engine)
}
