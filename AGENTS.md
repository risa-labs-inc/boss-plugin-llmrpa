# AGENTS.md

## Project Overview

**LLM RPA (Dynamic)** (`ai.rever.boss.plugin.dynamic.llmrpa`) is a dynamic plugin for the BOSS desktop application.

AI-powered robotic process automation with LLM integration

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.llmrpa`
- **Main Class**: `ai.rever.boss.plugin.dynamic.llmrpa.LlmrpaDynamicPlugin`
- **API Version**: 1.0.20 · **minApiVersion**: 1.0.74 · **minBossVersion**: 9.2.63

## AI: this plugin owns no credentials and no wire formats

Requests go through the shared **AI Gateway** plugin (`AiGatewayAPI`), reached with
`context.getPluginAPI(...)`. Keys, endpoints, models and sampling parameters are the gateway's
problem, resolved from Settings → AI Providers per call.

`LlmApiClient` keeps only what is this plugin's own work: building the RPA prompt and parsing
the model's reply back into `LLMRpaResponse`. It went from 298 lines to about 150 when the
transport and the four per-format request builders left, and **Ktor left with them** - all four
artifacts, because they were bundled purely for that client.

Before any of this, `LLMSettings` kept four provider keys in **plaintext** in
`~/.boss/config/llm-settings.json` and rewrote the file on every keystroke of the panel's own
"API Key" field, alongside a hardcoded provider enum and a model list that had drifted years
out of date (`claude-3-5-sonnet-20240620`) - while its own status card told users to "Configure
in Settings > LLM Providers" and then ignored them. Do not reintroduce a local key field.
secret-manager v1.2.9+ imports that file on first run and renames it to `.migrated`.

Three things to keep right:

- **Resolve the gateway lazily, per call.** It is held as a `() -> AiGatewayAPI?` lambda, not a
  resolved instance, because plugin load order is not guaranteed and `getPluginAPI` at
  `register()` would cache a null forever. Neither `LlmApiClient.gateway` nor
  `LlmrpaComponent.aiGateway` has a **default**: a call site that forgot to pass it would
  otherwise be indistinguishable at runtime from "no gateway installed", and the panel would
  serve example responses forever.
- **Guard calls across the boundary.** `aiModel()` wraps its call, because a gateway built
  against a different api revision raises `NoSuchMethodError` rather than returning null, and it
  is read from composition.
- **`activeModel()` is display-only.** `aiAvailable()` derives from it, and the composables read
  it per composition, because there is no change signal - a remembered snapshot keeps naming a
  provider the user has since changed or removed.

There are no wire formats here any more, so the `else`-branch rule that used to matter is the
gateway's problem. The api floor is **1.0.74**.

### The gateway is an *optional* declared dependency

`plugin.json` lists `ai.rever.boss.plugin.dynamic.aigateway` with `"optional": true`. Declaring it
is what makes the host's one existing check work - `DynamicPluginManager.checkCanUnload` refuses to
uninstall a plugin a loaded plugin depends on, so the gateway cannot be removed from under this one
silently. Nothing reads `dependencies` at *install* time (no resolver, no prompt), so installing
this plugin without the gateway still just shows the unconfigured state.

`optional: true` is truthful: the panel deliberately serves an example response rather than an
error when no gateway or provider is available, so the plugin genuinely works without one. A hard
dependency would make it refuse to load the moment load-time enforcement is wired up.

Under `BOSS_MODE=KERNEL` this plugin runs out-of-process and the microkernel's
`RemotePluginContext` has no plugin-API proxy, so the gateway is null there and the panel shows
its unconfigured state. Tracked against `boss-microkernel-runtime`, not fixable here.

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
- **No HTTP client.** AI goes through the AI Gateway plugin. The host deliberately excludes the
  Ktor stack, so a plugin carrying its own copy is a loader-constraint hazard.

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
