package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome

/**
 * LLM RPA panel info (Dynamic Plugin)
 */
object LlmrpaInfo : PanelInfo {
    override val id = PanelId("llm_rpa", 18)
    override val displayName = "LLM RPA"
    override val icon = Icons.Outlined.AutoAwesome
    override val defaultSlotPosition = right.top.top
}
