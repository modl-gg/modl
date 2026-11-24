# AI Moderation

An AI model can review chat reports (`/chatreport`) and suggest or apply a punishment. Only reported chat is sent to the provider. Each ticket is analysed once, never when closed.

## Connect a provider

The owner sets this in Panel > Settings > Tickets > AI moderation, or in `embedded.ai` (plugin) or `MODL_AI_*` (separate panel). No model is preselected.

| Provider id | Provider | Key |
|---|---|---|
| `openai` | OpenAI | required |
| `anthropic` | Anthropic | required |
| `gemini` | Google Gemini | required (`MODL_GEMINI_API_KEY` also works) |
| `deepseek` | DeepSeek | required |
| `qwen` | Qwen (Alibaba Model Studio), regions Singapore, China, US | required |
| `moonshot` | Moonshot AI (Kimi) | required |
| `zhipu` | Zhipu GLM | required |
| `minimax` | MiniMax | required |
| `openrouter` | OpenRouter | required |
| `ollama` | Ollama, default `http://localhost:11434/v1` | optional |
| `lm-studio` | LM Studio, default `http://localhost:1234/v1` | optional |
| `custom` | any OpenAI-compatible server; set the base URL | optional |

```yaml
embedded:
  ai:
    provider: "openai"
    api-key: "<provider key>"
    model: "<model id>"
    base-url: ""
```

- For OpenAI-compatible providers the base URL ends before `/chat/completions`. Plain `http` works only for local and private addresses.
- From Docker, reach a model on the host at `host.docker.internal`; the compose file maps it, also on Linux.
- Give local models a large context (Ollama: `OLLAMA_CONTEXT_LENGTH=16000`); reported chat can be up to 32,000 characters. Test small models on a few reports before turning on automatic actions.

## Behavior

On the same page, staff with `admin.settings.modify` turn on AI review (suggestions shown on the ticket) and automated actions (suggestions applied on their own), and pick which punishment types the AI may suggest, each with a description for the model. Staff apply a suggestion from the ticket.

The owner changes these limits under AI provider > Usage limits.

| Limit (default) | Range |
|---|---|
| 60 calls per hour | 1 to 10,000 |
| 500 calls per day | 1 to 100,000 |
| 5 reports per reporter per hour | 1 to 1,000 |
| 2 calls at once | 1 to 16 |
| 4096 output tokens | 256 to 32,768 |

An invalid or refused answer stores nothing. Changing the provider or model keeps automatic actions on.
