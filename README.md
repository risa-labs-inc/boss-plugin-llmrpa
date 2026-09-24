# BOSS LLM RPA

Describe a browser task in plain words and watch it get done, one step at a time, from the right
sidebar.

## What it does

- **Run** does the task on the tab you pick. Each step, RPA Engine lists what can be clicked or
  typed on the page (`rpa_observe`), a model picks the next action, and RPA Engine performs it
  (`rpa_step`). The timeline shows every step as a plain sentence with how sure the model was.
- **Any model.** The picker lists Jev (a decision model that picks among options) and every model
  of every provider configured in Settings → AI Providers, including local ones. Chat models go
  through the AI Gateway with a per-request provider and model.
- **It asks instead of guessing.** Below 60% confidence the run pauses and offers the top three
  choices (keys 1–3). An action that looks irreversible (submit, pay, send, delete) waits for an
  explicit Continue. When the model says the task is done, one more check confirms it against the
  current page before the run reports success.
- **It only types your text with Jev.** Jev cannot write text, so it types only values found in
  the instruction (quoted text, emails, web addresses), shown as chips before the run. A chat model
  may write its own text; the timeline marks those steps.
- **It stops** at the step limit (default 12), after two failed steps in a row, when the model
  finds nothing useful, or when you press Stop (Esc).
- **Draft steps** keeps the older feature: a chat model writes a whole RPA plan, saved to
  `~/.boss/config/rpaengine/llm-rpa-*.json` for RPA Engine's `rpa_load` / `rpa_run`.
- **At every width.** One column in a sidebar; compose and timeline side by side from 640dp.

## What leaves the machine

Each step sends the instruction, the page title and address, and element labels to the chosen
model's provider (OpenRouter for Jev). Field values and passwords are never read or sent. A local
model keeps everything on the machine. Run history stays in memory.

## MCP tools

| Tool | Purpose |
|---|---|
| `llmrpa_execute` | Run a task headless: `{instruction, tab_id?, max_steps?, model?}`. Stops where the panel would ask, and returns the steps. No panel needed. |
| `llmrpa_status` | The panel's live run and last drafted plan |
| `llmrpa_run` | Draft steps for an instruction in the open panel (does not act on the page) |

## Requirements

- BOSS >= 9.2.63, boss-plugin-api >= 1.0.75 (model picking needs a gateway with `providerOverride`; runs need RPA Engine 1.3+)
- A model: Jev (from Toolbox, with an OpenRouter key) or any provider in Settings → AI Providers.
- `activeTabsProvider` for tab targeting.
- No external binaries. The plugin bundles its own Ktor client.

## Build

```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-llmrpa-*.jar ~/.boss/plugins/
```

See [AGENTS.md](AGENTS.md) for architecture and conventions.

## License

Proprietary - Risa Labs Inc.
