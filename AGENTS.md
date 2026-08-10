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

## Handoff to the RPA Engine

Generating a plan was the whole plugin: the result was rendered and then dropped. `RpaEngineHandoff`
writes it to `~/.boss/config/rpaengine/llm-rpa-<slug>.json`, which the RPA Engine scans, so
`rpa_load` + `rpa_run` can execute it.

The envelope must match the engine's `RpaConfiguration`: `name`, `description`, `actions`, and each
action's `actionType` (**not** `action_type` - that is the field name in the LLM's JSON, and the
engine will not read it).

`write()` takes `configDir` as a parameter with a default. That is not gratuitous: the tests
originally wrote into the real `~/.boss/config/rpaengine` and left junk configurations in the
user's engine list.

## Selector guidance in the prompt is load-bearing

The prompt's selector rules are what make a generated plan runnable, and each one was added after
watching a run fail:

- **Attribute-only CSS, never tag-qualified.** The model emitted `input[name='q']`; Google's search
  box is a `textarea`. The tag is the part most often wrong, the attribute holds. (The engine also
  retries with the tag stripped, and logs it - but the plan should be right.)
- **`text` selectors for links and tabs, never `href*=` on query parameters.** The model emitted
  `a[href*='tbm=isch']` for Google Images, which has used `udm=2` for some time. A visible label
  does not change on Google's schedule.
- **No site-internal `data-*` attributes; prefer ARIA landmarks.** `[data-ils]` matched nothing;
  `[role='main'] img` is the first image result.

Verify a prompt change by generating and *running*, not by reading the plan. Both a stale-URL
selector and a wrapper-element click produce a plan that looks entirely reasonable on disk.

### Decisions worth keeping

- `testImplementation` **must** branch on `useLocalDependencies` exactly like the `compileOnly`
  above it. `newestApiJar`'s provider `error()`s when the sibling checkout is absent, which is the
  CI case, and `tasks.build` resolves the test compile classpath - so an unconditional local jar
  there fails the *release*, not just a test run.
- The handoff writes with `explicitNulls = false`. Another plugin parses this file, and kotlinx
  throws on an explicit `null` for a non-nullable-with-default field: if the engine ever gives
  `value` a default, every action without one (`submit`, a bare `wait`) would make the whole file
  unreadable.
- The filename carries a hash of the full instruction. The slug is capped at 40 characters, so
  two different instructions ("…invoices from january" / "…from february") truncate to the same
  name and the second write would silently replace the first plan. Truncating *before* trimming
  also matters, or a cut landing on a separator leaves a trailing hyphen.
- `BossLogger.forComponent` is resolved lazily. An api symbol resolved in an object initializer
  raises `NoSuchMethodError` on a host built against a different api revision, and an `Error`
  slips straight past `catch (e: Exception)` around the caller.
- `handoffPath` is rendered (`HandoffCard`). A public flow with no consumer is the bug it was
  meant to fix: the plan lands in a directory the user was never told about, and the button still
  says "Execute".

A fixture can silently fail to discriminate. The truncation test only holds if the 40-character
cut lands *exactly* on a separator - with any other instruction, trim-before-take and
take-before-trim produce the same name and the test passes on the mutation it names.

### Second review round

- **`_handoffPath` is cleared at the start of every generation.** It was only ever written on a
  successful write, so a following generation that failed left the card pointing at the *previous*
  instruction's file: a green check saying "ready to run" for a plan the user did not ask for. Once
  it is per-run, the card no longer needs gating on `errorMessage` (which also hid a valid card
  whenever an unrelated error was showing).
- **`LLMRpaResponse.runnablePlan()` is the single predicate** behind panel state, error text and
  what reaches disk. Those three were spelled out separately and disagreed - which is how an
  `"error"` response still got written as a runnable configuration.
- The verb list and selector-type set in the prompt are a **cross-repo contract** with the engine's
  `ActionTypes`/`SelectorTypes`. Nothing here can pin them: a new engine verb is silently withheld,
  a renamed one fails inside the *other* plugin. The KDoc on the prompt builder names the file and
  the version it was read from - keep it current.
- One entry point per side effect: `write` was dropped in favour of `writeResult`, and the
  `parseForTest`/`parseRpaResponse` aliases removed. Two doors to the same effect drift.

### Third review round

- **The JSON extraction must stop at the first *complete* object.** `\{[\s\S]*\}` ran from the
  first brace to the last one in the whole reply, and a reasoning model closes the JSON, closes a
  code fence and keeps talking - GLM produced `...}\n```\n\nHmm, wait. Let me reconsider...` - so
  the match swallowed the prose and the parse died at the far end of it. Every action was present
  and correct and the generation was thrown away. `firstJsonObject` counts braces at depth,
  skipping string literals and escapes. This was found by *running* a generation, not by review:
  it looked like an ordinary model failure until `llmrpa_status` reported the real reason.
- **The unconfigured example response is not `"success"`.** It is a single `wait 1000` and it
  satisfied `runnablePlan()`, so it landed on disk named after the user's instruction and the card
  offered to run it. It carries `STATUS_EXAMPLE` now. Reaching it needs the provider to disappear
  between `aiAvailable()` and the call, which is a TOCTOU window rather than an impossibility.
- **`llmrpa_status` reports the last history entry, not just `_errorMessage`.** The latter only
  carries a *save* failure, so a generation that produced nothing runnable reported `error=none` and
  an agent polling it could not tell anything had gone wrong. This is what surfaced the extraction
  bug above.
- **The write is atomic** (`.part` sibling plus `ATOMIC_MOVE`). The engine scans that directory on
  its own schedule, and `writeText` truncates first - so a scan landing mid-write reads half a file,
  most likely exactly when re-running an instruction overwrites in place.
- **The `onFailure` logger call has its own guard.** `logger` is lazy so an api mismatch degrades,
  but the initialiser first runs inside `onFailure`, which is *outside* the `runCatching` - a
  `NoSuchMethodError` there defeated the whole arrangement and slipped past the caller's
  `catch (e: Exception)`.
- `actionType` is pinned because the file must *parse*; the verb that decides what runs travels in
  `type`, whose name agrees on both sides. The doc used to emphasise only the first.
- `buildPrompt` is `internal` and `PromptRulesTest` asserts each selector rule and every verb is
  still being sent. It cannot prove a rule works - only that a stray edit did not delete one.

### Fourth review round

Three more routes by which a complete, correct plan was thrown away. That is the failure mode of
this plugin; assume any new comparison or extraction is another instance until shown otherwise.

- **Status was compared exactly.** `status == "success"` against a model that answers `"Success"`.
  Normalised once at the parse boundary (`trim().lowercase()`), because doing it at each comparison
  is what `runnablePlan()` exists to prevent.
- **The extraction anchored on the first `{` anywhere.** A braced aside before the fenced JSON
  handed back an undecodable slice. The contract is now "the first *decodable* object": advance to
  the next opening brace and retry, bounded.
- **`updateExecutionStatus`'s `message` was accepted and dropped**, so the model's explanation never
  reached the panel or `llmrpa_status`.

Also: `generateActions` refuses a second concurrent run - two of them collide on the staging file,
on `_handoffPath`, and on a history index resolved by `size` after a non-atomic append (now
`update`). The staging file is `Files.createTempFile` per write, since deriving it from the
instruction gave two runs of the *same* instruction the same path - the interleaving the atomic move
exists to rule out.

**A `contains` assertion over a whole prompt is close to worthless.** `PromptRulesTest`'s verb check
passed while `select` and `submit` were deleted from the verb list, because "CSS selectors" and the
submit rule carry those substrings. It now parses the `Available action types:` line and asserts set
equality, which also catches a verb offered here that the engine does not implement.

CI (`test.yml`, cherry-picked from `ci/add-test-workflow`) tracks api `latest`, matching
`plugin-release.yml`. That means an unrelated api release can redden every open PR, and nothing
verifies the `minApiVersion` floor the manifest declares - a matrix over floor and latest would get
both. One observed flake: `dl.google.com` failed to serve three androidx artifacts mid-run, which
looked exactly like a resolution bug; they returned 200 immediately after and a re-run was green.
