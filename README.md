## dspy4s (Scala 3)

A minimal, pragmatic Scala 3 port of the Python `dspy` library — starting with primitives, signatures, a `Predict` module, and an OpenAI client.

### Modules

- `core`: primitives (`Module`, `Example`, `Prediction`), `signatures`, `utils` (`DspyError`, `Settings`, logging).
- `clients`: `LM` trait, `OpenAI` client (chat completions), in-memory `Cache`, `LMWithCache` wrapper.
- `predict`: `Predict` flow (template → LM → parse JSON → `Prediction`).
- `examples`: tiny demo wiring components together (stubbed `LM` by default).

### Build & Test

- Format check: `sbt scalafmtCheckAll`
- Run tests: `sbt test`
- Run example: `sbt examples/run`

To run the second example (multi-output with confidence):

- `sbt "examples/runMain dspy.examples.QAConfidence"`

### OpenAI Setup (optional)

To use the OpenAI client, set `OPENAI_API_KEY` and create a backend in your app:

```scala
import dspy.clients.openai.OpenAI
import dspy.utils.Settings
import sttp.client3.httpclient.future.HttpClientFutureBackend
import scala.concurrent.ExecutionContext.Implicits.global

val backend = HttpClientFutureBackend()
val lm = new OpenAI(model = "gpt-4o-mini", settings = Settings.default, backend)
```

Settings supports `DSPY_DEBUG=true` for request/response logging, with `DSPY_LOG_PROMPTS` / `DSPY_LOG_RESPONSES` to include bodies (redacted & truncated).

You can also put config in `~/.dspy4s/config.json` (or set `DSPY4S_CONFIG` to a custom path). Example:

```json
{
  "openai_api_key": "sk-...",
  "openai_base_url": "https://api.openai.com/v1",
  "request_timeout_ms": 60000,
  "debug": false,
  "log_prompts": false,
  "log_responses": false
}
```

### Status

This is an early MVP. See `docs/PORTING_PLAN.md` for scope, decisions, and tasks.
