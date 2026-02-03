package ai.rever.boss.plugin.dynamic.llmrpa

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Serializable settings data
 */
@Serializable
data class LLMSettingsData(
    val selectedProvider: String = "ANTHROPIC",
    val selectedModelId: String = "claude-3-5-sonnet-20240620",
    val anthropicApiKey: String = "",
    val openaiApiKey: String = "",
    val togetherApiKey: String = "",
    val customApiKey: String = "",
    val customEndpoint: String = "",
    val maxTokens: Int = 4096,
    val temperature: Double = 0.7
)

/**
 * LLM Settings manager
 */
object LLMSettings {
    private val json = Json { 
        prettyPrint = true 
        ignoreUnknownKeys = true
    }
    
    private val settingsFile: File by lazy {
        val configDir = File(System.getProperty("user.home"), ".boss/config")
        configDir.mkdirs()
        File(configDir, "llm-settings.json")
    }
    
    private var settings: LLMSettingsData = LLMSettingsData()
    
    val selectedProvider: LLMProvider
        get() = try {
            LLMProvider.valueOf(settings.selectedProvider)
        } catch (e: Exception) {
            LLMProvider.ANTHROPIC
        }
    
    val selectedModelId: String
        get() = settings.selectedModelId
    
    val maxTokens: Int
        get() = settings.maxTokens
    
    val temperature: Double
        get() = settings.temperature
    
    fun loadSettings() {
        try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                settings = json.decodeFromString(content)
            }
        } catch (e: Exception) {
            // Use defaults
            settings = LLMSettingsData()
        }
    }
    
    fun saveSettings() {
        try {
            settingsFile.writeText(json.encodeToString(settings))
        } catch (e: Exception) {
            // Ignore save errors
        }
    }
    
    fun getApiKey(provider: LLMProvider): String? {
        val key = when (provider) {
            LLMProvider.ANTHROPIC -> settings.anthropicApiKey
            LLMProvider.OPENAI -> settings.openaiApiKey
            LLMProvider.TOGETHER -> settings.togetherApiKey
            LLMProvider.CUSTOM -> settings.customApiKey
        }
        return key.takeIf { it.isNotBlank() }
    }
    
    fun setApiKey(provider: LLMProvider, key: String) {
        settings = when (provider) {
            LLMProvider.ANTHROPIC -> settings.copy(anthropicApiKey = key)
            LLMProvider.OPENAI -> settings.copy(openaiApiKey = key)
            LLMProvider.TOGETHER -> settings.copy(togetherApiKey = key)
            LLMProvider.CUSTOM -> settings.copy(customApiKey = key)
        }
        saveSettings()
    }
    
    fun getCustomEndpoint(): String = settings.customEndpoint
    
    fun setCustomEndpoint(endpoint: String) {
        settings = settings.copy(customEndpoint = endpoint)
        saveSettings()
    }
    
    fun setProvider(provider: LLMProvider) {
        settings = settings.copy(selectedProvider = provider.name)
        // Also set a default model for the provider
        val models = LLMModels.getModelsForProvider(provider)
        if (models.isNotEmpty()) {
            settings = settings.copy(selectedModelId = models.first().id)
        }
        saveSettings()
    }
    
    fun setModel(modelId: String) {
        settings = settings.copy(selectedModelId = modelId)
        saveSettings()
    }
    
    fun setMaxTokens(tokens: Int) {
        settings = settings.copy(maxTokens = tokens.coerceIn(100, 32000))
        saveSettings()
    }
    
    fun setTemperature(temp: Double) {
        settings = settings.copy(temperature = temp.coerceIn(0.0, 2.0))
        saveSettings()
    }
    
    fun hasValidApiKey(): Boolean {
        return getApiKey(selectedProvider) != null
    }
}
