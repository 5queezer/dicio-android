package org.stypox.dicio.skills.fallback.llm

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.RichText
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.SkillOutput

class LlmFallbackOutput(
    private val response: String,
) : SkillOutput {
    // For TTS strip the markdown markers — the speech output should be plain prose.
    override fun getSpeechOutput(ctx: SkillContext): String =
        response.replace(Regex("""\*+|^#+\s*|`+""", RegexOption.MULTILINE), "").trim()

    override fun getInteractionPlan(ctx: SkillContext) =
        InteractionPlan.Continue(reopenMicrophone = false)

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        RichText(modifier = Modifier.fillMaxWidth()) {
            Markdown(content = response)
        }
    }
}
