# AGENTS.md

## Project Overview

**LLM RPA (Dynamic)** (`ai.rever.boss.plugin.dynamic.llmrpa`) is a dynamic plugin for the BOSS desktop application.

AI-powered robotic process automation with LLM integration

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.llmrpa`
- **Main Class**: `ai.rever.boss.plugin.dynamic.llmrpa.LlmrpaDynamicPlugin`
- **API Version**: 1.0.20 · **minApiVersion**: 1.0.71 · **minBossVersion**: 9.2.63

## AI credentials: this plugin owns none

Keys, endpoints, models and sampling parameters all come from the **secret-manager** plugin
(Settings → AI Providers), read through `PluginContext.llmProvider`. `LlmApiClient` is driven
entirely by the `LlmConfig` handed to `callLLMApi`.

Before this, `LLMSettings` kept four provider keys in **plaintext** in
`~/.boss/config/llm-settings.json` and rewrote the file on every keystroke of the panel's own
"API Key" field, alongside a hardcoded provider enum and a model list that had drifted years
out of date (`claude-3-5-sonnet-20240620`) — while its own status card told users to
"Configure in Settings > LLM Providers" and then ignored them. `LlmSettings.kt` and the
`LLMProvider`/`LLMModels` types are gone; do not reintroduce a local key field. secret-manager
v1.2.9+ imports that file on first run and renames it to `.migrated`.

Three things to keep right:

- **Re-read `llmConfig()`, never cache it.** `LlmProvider` exposes no change signal, so a
  remembered snapshot keeps showing a provider the user has since changed or removed. The
  composables call it per composition for the same reason.
- **`callProvider`'s `when` must keep its `else`.** `LlmApiFormat` is an open set the host can
  extend ahead of this plugin; made exhaustive, a new constant throws
  `NoWhenBranchMatchedException` mid-request (the bug that shipped in jupyter-notebook v1.0.12).
- **`CUSTOM` is an OpenAI-compatible chat endpoint now.** The old custom branch POSTed a raw
  `LLMRpaRequest` and expected an `LLMRpaResponse` back — a bespoke RPA protocol, not an LLM
  call. The provider registry defines `CUSTOM` as "Custom (OpenAI-compatible)", so it goes
  through the chat path like the others.

Under `BOSS_MODE=KERNEL` this plugin runs out-of-process, and the microkernel's
`RemotePluginContext` has no `llmProvider` proxy — so the config is null there and the panel
shows its unconfigured state. Tracked against `boss-microkernel-runtime`, not fixable here.

### The api jar must never be pinned by filename

`build.gradle.kts` resolves the **newest** `boss-plugin-api-*.jar` in the sibling checkout. It
used to name `boss-plugin-api-1.0.51.jar`, which no longer existed — and `compileOnly(files(…))`
on a missing path contributes nothing *silently*, so every api symbol came back "unresolved
reference" with no hint the cause was a stale filename.

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build              # Full build
./gradlew processResources   # Process resources (syncs version)
```

## Workflow Rules

- Do NOT run the BOSS application to test. The user will test manually.
- After building, copy JAR to `~/.boss/plugins/` for local testing.

## Architecture

### Plugin Structure
```
src/main/kotlin/   → Plugin source code (package: ai.rever.boss.plugin.dynamic.*)
src/main/resources/META-INF/boss-plugin/plugin.json → Plugin manifest
build.gradle.kts   → Build config + version (single source of truth)
```

### Key Patterns
- Entry point: `DynamicPlugin` interface with `register(context)` and `dispose()`
- UI: `PanelComponentWithUI` with `@Composable Content()`
- State: ViewModel pattern with `StateFlow`
- Providers from `PluginContext`: `workspaceDataProvider`, `splitViewOperations`, `contextMenuProvider`, `activeTabsProvider`
- Null-safe provider access: providers may be null, UI must handle gracefully

### Dependencies
- **boss-plugin-api**: compileOnly (provided by host app at runtime)
- **Compose Desktop**: UI framework
- **Decompose**: Navigation and component lifecycle
- **Coroutines**: Async operations

## Version Management

**`build.gradle.kts` is the single source of truth for version.**

The `processResources` task automatically syncs the version into `plugin.json` at build time. Never manually edit the version in `plugin.json` - only change it in `build.gradle.kts`.

## Code Quality

- Use Compose Multiplatform APIs (not Android-specific)
- All Kotlin files must end with a newline
- Handle null providers gracefully - show fallback UI, never crash

## CI/CD

Pushes to `main` trigger the release workflow which:
1. Builds the plugin JAR
2. Creates a GitHub release
3. Publishes to the BOSS Plugin Store

The workflow is defined in `.github/workflows/build.yml` and delegates to the shared workflow in `risa-labs-inc/BossConsole-Releases`.
