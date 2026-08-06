# BOSS LLM RPA

Describe a browser task in plain language and have an LLM turn it into RPA actions, from the
right sidebar.

Takes an instruction, the current page of a selected browser tab, and a configured LLM, and
produces a list of typed RPA action configs.

**It generates actions; it does not run them.** There is no path from the generated list to
[RPA Engine](https://github.com/risa-labs-inc/boss-plugin-rpaengine) or to a browser. Treat the
output as a draft to inspect, not as automation that has happened.

## What it does

- **Instruction box** with quick example chips: Fill form, Extract data, Navigate.
- **Targets a real tab**: reads open tabs from `activeTabsProvider`, filters to those with a
  URL, auto-selects the first, and clears the selection if that tab closes.
- **Calls your provider** - Anthropic, OpenAI, Together, or a custom endpoint - and parses the
  reply into typed action configs (type, selector info, value, metadata).
- **Settings**: provider chips, model picker, API key, max tokens (default 4096) and
  temperature (default 0.7).
- **History** of runs, each with status (GENERATING, READY, ERROR) and its generated actions.

## Two things to know before you rely on it

- **With no API key configured, it silently returns a mock.** The call path produces a canned
  response reporting `status = "success"` with a single fake `wait` action, and the UI renders
  the normal green success path. The only tell is a short message reading "Mock response -
  configure an LLM API key to generate real actions". `llmrpa_run` over MCP reports success
  either way.
- **API keys are stored in plaintext.** They are written to
  `~/.boss/config/llm-settings.json` through plain file IO, bypassing the host's
  `SecretDataProvider` and its encryption. Prefer configuring providers through [Secret
  Manager](https://github.com/risa-labs-inc/boss-plugin-secret-manager) where you can.

The default model id shipped in settings is several generations old. Set the model explicitly
rather than accepting the default.

## MCP tools

| Tool | Purpose |
|---|---|
| `llmrpa_status` | Generating flag, current instruction, history size, last error |
| `llmrpa_run` | Set an instruction and trigger action generation |

Both act on the most recently opened panel instance and return an error when no panel is open.

## Requirements

- BOSS >= 9.2.20, boss-plugin-api >= 1.0.20
- Network egress to the provider you configure, and an API key you supply.
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
