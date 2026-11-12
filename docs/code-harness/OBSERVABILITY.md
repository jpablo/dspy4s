# The inspector and the exchange files: a proposal

Status: the review took the proposal on 2026-11-12 (9.2), and O1 is built. It covers two needs. A person must see the
journal of a session. A person must also see each HTTP request that the harness sends to the model endpoint, with its
reply. Each of the two must have raw views and a formatted view.

Section 2 gives what the harness has now. Section 4 adds a record of each HTTP exchange, and section 5 adds a local
web server that shows the journals and the exchanges. Section 8 gives the order of the work, and section 9 gives the
choices.

## 1. Goals

- A person selects a session and sees its journal as a tree of turns, steps and effects.
- Each entry has three views: the Scala value, the JSON row, and a formatted card.
- A person sees each HTTP request to the model endpoint and its reply as they went. The only change is the mask of
  the secrets (4.4).
- Each exchange shows in the tree after the entry before it, so a person sees which step sent it.
- An exchange of the policy shows its differences from the exchange of the step before it.
- The tool only reads. It changes no file of the host, and a session can run while a person looks at it.

The proposal leaves out a view of the sandbox, metrics across many sessions, and a shared server for a team. Section
7 keeps an OpenTelemetry export for later.

## 2. What the harness has now

- **The journal.** Each session has `<root>/sessions/<id>.db`, with one SQLite row for each `Logged` entry
  (CODE_HARNESS_DESIGN.md, 10.2). The `entry` column holds the JSON of the `Schema` of `Logged`.
  `SessionFile.readOnly` opens a journal with `mode=ro`, and `readFrom(seq)` reads the rows from a `seq`.
- **The catalog.** `<root>/catalog.db` has one `CatalogRow` for each session, and `dspy4s-harness --list` shows them.
- **The probes.** A probe keeps its journal at `<root>/probes/<caller>.<cell>.<n>.db`.
- **The terminal text.** `EntryText` gives the text of an entry for the terminal. It leaves out the entries that a
  person does not need during a session.
- **`DSPY_TRACE`.** `TracedModel` and `TracedStreams` write one JSON line for each model call. The line has these
  gaps:
  - It holds the `LmRequest`: the roles and the texts of the messages. It does not hold the HTTP body, so the tools,
    the options, the response format and the usage are not in it.
  - It has no session, no step and no predictor. One file takes the calls of all the sessions of the process.
  - It holds the joined reply, not the lines of the stream.
- **`HttpTransport`.** It is the only code that sees the exact body: `sendJson(url, headers, body)` and
  `streamSse(url, headers, body)`. `JdkHttpTransport.streamSse` gives each raw line of the reply body
  (`BodyHandlers.ofLines`), and `OpenAiClient` reads the `data:` lines from them.
- **`RuntimeServices.streamSink`.** It is a service of the context that each model call reads (`StreamSinkRef` in
  core, `StreamSink` in lm). Section 4.1 uses the same pattern.

## 3. The words of this proposal

- **Exchange:** one HTTP request to the model endpoint, and its reply. A retry is a second exchange.
- **Model call:** one call of a `LanguageModel`. It makes one exchange, or more than one if a retry occurs. The
  fallback adapter makes a second model call.
- **Exchange file:** the file of the exchanges of one session, `<root>/sessions/<id>.http.jsonl`.
- **Inspector:** the local web server of section 5.
- **View:** one form of an item on a page: Scala, JSON, card, chat, request, reply or diff.

## 4. The exchange file

### 4.1 Where the record comes from

The record comes from the transport, through a service of the context:

- **core.** A marker trait `ExchangeSinkRef`, as `StreamSinkRef` is. `RuntimeServices` gets
  `exchangeSink: Option[ExchangeSinkRef] = None`, and `fillFrom` and `keptBy` include it. The flat `apply` and `copy`
  of `RuntimeContext` build the services again from their arguments, so they have an `exchangeSink` argument too.
  Else a flat `copy(adapter = ...)`, as the examples use, loses the sink.
- **lm.** The sink and the record of one exchange (`LmExchange.scala`):

  ```scala
  trait ExchangeSink extends ExchangeSinkRef:
    /** The record of a new exchange of `request`. */
    def start(request: ExchangeRequest)(using RuntimeContext): ExchangeRecord

  final case class ExchangeRequest(url: String, headers: Map[String, String], body: String, stream: Boolean)

  trait ExchangeRecord:
    def replied(status: Int, headers: Map[String, String]): Unit // the status and the headers of the reply
    def body(text: String): Unit                                 // the body of a plain reply
    def line(text: String): Unit                                 // one raw line of a stream
    def end(how: ExchangeEnd): Unit                              // once: after it, the record gets no call

  enum ExchangeEnd:
    case Done                    // the reply came: the body, or each line to the end of the lines
    case Closed                  // the consumer closed the lines first
    case Failed(message: String) // the code and the message of the error, or the class of an exception
  ```

  The transport calls `start` on the thread of the model call, before the request goes. So a sink can read the scope
  of that thread (4.2). The calls of one record come one at a time, but a close can come from another thread than the
  reads.
- **`RecordedTransport(inner, sink)`.** An `HttpTransport` that gives each request and each reply of `inner` to the
  record of the sink:
  - `sendJson` starts a record, calls `inner`, and gives the status, the headers and the body, then `Done`. A `Left`
    or an exception gives `Failed`.
  - `streamSse` starts a record and calls `inner`. On a `Right`, it gives the status and the headers, and its
    `dataLines` give each line to the record as the consumer reads it. The end of the lines gives `Done`, or `Failed`
    with the `failure` of the reply. A close before the end gives `Closed`. The record ends before the inner lines
    close: after that close their `hasNext` is false, and a read on another thread must not give `Done`.
  - `OpenAiClient` closes the lines after the line `data: [DONE]`, and it does not read them to their end. So a
    complete stream of the client ends `Closed`, with `data: [DONE]` as its last line.
  - A `GuardedRecord` holds each record: it drops an exception of the sink, it takes only the first end, and it gives
    the record no call after its end. An `Error` of `inner`, for example `OutOfMemoryError`, goes on with no end.
  - `close()` does nothing, because the inner transport belongs to the client.
- **`OpenAiLanguageModel`.** When the context has an `ExchangeSink`, `call` and `stream` use
  `client.copy(transport = RecordedTransport(client.transport, sink))` for that call. With no sink, nothing changes.

So the record is in the lm module, and each program of dspy4s can use it. The harness only gives the sink.

### 4.2 The labels

The host gives each activation its own sink, an `ExchangeFile`. At its start, `WorkRuntime.activate` puts the sink in
a copy of the context of the runtime. It then uses that copy in each place that uses `context` now:

- the `withContext` around the start, because `HostThreads.ofThisThread()` captures the context there, and
  `llmQuery` runs on those threads;
- `StoppablePolicy`, which runs the policy and the memory leaves;
- `HarnessEffects` and `WorkActivation`.

So the sink reaches each model call of the activation: the policy, the memory leaves, `llmQuery` and the probes. The
name of a predictor is the `name` of its `Predict`. Each record gets these labels:

- `session`: the id of the session.
- `after`: the last `seq` of the journal when the exchange starts. The inspector shows the exchange after that entry.
  For a policy call, it is the `StepStarted` of the step.
- `predictor`: the name of the predictor that runs on the thread of the call (`RuntimeEnvironment.activePredictor`):
  `policy`, `compress`, `merge`, `subQuery` or `finisher`. It is empty outside a predictor. `Module` sets the
  predictor only in the scope of the thread, not in the context of the call, so the sink reads it in `start`.
- `probe`: the link of a probe (`<caller>.<cell>.<n>`) for each exchange of that probe. The probe adds it to the
  labels.
- `id`: a text that no other exchange of the file has. The two lines of an exchange share it.

A child session has its own activation, so it has its own exchange file.

### 4.3 The lines of the file

Each exchange has two lines:

- A `request` line when the exchange starts. So the file holds the request of a call that stops, or of a process that
  stops during the call.
- A `reply` line when the exchange ends.

An example, with parts cut:

```json
{"kind":"request","id":"e3b0c442","session":"9c1f5d2a","after":41,"predictor":"policy","at":1762950000123,
 "url":"<base>/chat/completions","headers":{"Authorization":"«secret»"},"stream":true,
 "body":"{\"model\":\"…\",\"messages\":[…],\"stream\":true}"}
{"kind":"reply","id":"e3b0c442","at":1762950003456,"first":1762950000610,"status":200,
 "headers":{"content-type":"text/event-stream"},"lines":["data: {…}","","data: [DONE]"],"end":"closed"}
```

- `body` is a JSON string with the exact text of the body. `jq '.body | fromjson'` gives the object.
- A plain reply has `body`. A stream has `lines`: each raw line of the reply body in its order, with the `data:`
  prefix and the empty lines.
- `first` is the time of the first line of a stream, so a person sees the wait for the first token.
- `end` is `done`, `closed` or `failed` (4.1), and a failed exchange also has `error`. A complete stream of the
  OpenAI client ends `closed`, and its last line is `data: [DONE]`. The views show such a stream as complete.
- Each line goes to the file in one write, under the lock of the file. So the lines of two threads do not mix.
- Each write opens the file, appends the line and closes the file, as `DSPY_TRACE` does now. So a thread that ends
  after its activation still writes its line.

### 4.4 The mask

- Each text of a line goes through the mask of the host (`Secrets.maskText`, 5.6) before the write.
- The value of the header `Authorization` is always `«secret»`, also when the mask does not know the key.
- So the record is exact apart from the masked values.

### 4.5 The cases

| Case                                                     | The reply line                                     |
|----------------------------------------------------------|----------------------------------------------------|
| A plain reply with a success status                      | The status, the headers and the body, `done`       |
| A plain reply with an error status                       | The status, the headers and the error body, `done` |
| A failure before the headers (no connection, time limit) | `failed` with the error, and no status             |
| A stream that the client reads to `data: [DONE]`         | The status, the headers, each line, `closed`       |
| A stream that the consumer reads to the end of its lines | The status, the headers, each line, `done`         |
| A stream that ends early (read limit, lost connection)   | The lines that came, `failed` with the error       |
| A consumer that closes the stream (a stop)               | The lines that came, `closed`                      |
| An interrupt of the thread of the call                   | `failed` with the error                            |
| An exception of the transport                            | `failed` with its class and its message            |
| An `Error` of the transport, for example out of memory   | No reply line; the inspector shows it as open      |
| The process stops during the exchange                    | No reply line; the inspector shows it as open      |
| A consumer that neither ends nor closes a stream         | No reply line; the inspector shows it as open      |
| A failed write of the file                               | One line on the standard error, once; the call goes on |
| A cache answers the model call                           | No exchange, so no lines. The harness sets no cache now |

### 4.6 The size

These numbers are estimates:

- A policy request sends the whole prompt again: about 20 to 80 KB.
- A stream line is about 250 bytes for each token, so a reply of 4,000 tokens is about 1 MB.
- A session of 50 model calls can thus have an exchange file of 10 to 50 MB.

Phase O2 measures the real sizes in a live run. The first version writes plain JSON lines. If the files are too large,
section 9.2, choice 2, has the options.

## 5. The inspector

### 5.1 The server

- `dspy4s-harness --inspect [--root DIR] [--port N]` starts the server and prints its URL.
- It uses the JDK `com.sun.net.httpserver.HttpServer`, so it adds no dependency.
- It listens on 127.0.0.1 only. It answers only a request whose `Host` header is `127.0.0.1:PORT` or
  `localhost:PORT`, so a web page that uses DNS rebinding gets no data. It sends no CORS headers, so a page of another
  origin cannot read a reply.
- It answers only `GET`. It opens each journal with `SessionFile.readOnly`, the catalog with `mode=ro`, and each
  exchange file as a plain read.
- It is a new module, `harness-inspect`. The module uses `harness-host` for the journal and the catalog, and `lm` for
  the parser of a stream. The CLI uses it for `--inspect`.

### 5.2 The pages

- **`/`: the sessions,** the newest first, from the catalog: the first message, the folder, the status, the time of
  the last entry, and a link to the parent. The probes show under their caller.
- **`/s/{id}`: one session as a tree.** An `Input` comes first, then its turn, then the steps of the turn. Each step
  holds its `Acted`, its effects and its `Observed`, and the turn ends with its `TurnEnded`.
  - Each exchange shows after its `after` entry, with its predictor, its time, the wait for the first line, and the
    tokens of its usage.
  - A filter selects the kinds of entries and the predictors of the exchanges.
  - A switch sets the view of all the entries (Scala, JSON or card), and each entry has its own switch.
- **`/s/{id}/x/{xid}`: one exchange** with the four views of 5.4.
- **A session that runs.** The page asks for the new rows each second (`/s/{id}/after/{seq}`) and adds them. The
  journal uses WAL, so a reader sees each commit whole. An exchange with a `request` line and no `reply` line shows as
  open.

### 5.3 The views of an entry

- **Scala.** The decoded `Logged` value as Scala text with indents. A small printer reads each value through
  `Product` (`productPrefix`, `productElementNames` and `productIterator`). So it gives the names of the cases and of
  the fields as the code has them. A string with a line end prints as a `"""` block, and a `Json` value prints as
  `Json(<its text>)`. A row that does not decode shows its error in this view.
- **JSON.** The `entry` column as it is in the file, with indents, and the columns `seq` and `required`.
- **Card.** A formatted card for each case of `Entry`. It shows the reasoning folded, the text, the code of a cell,
  the output and the outcome. It also shows the time, the source, and a mark for `Taint.External`.
  - The server makes the HTML of the card with a `match` on `Entry`. With `-Werror`, a `match` that does not cover each
    case does not compile. So a new case of `Entry` must get a card.
  - Each text goes through an HTML escape.

### 5.4 The views of an exchange

- **Chat.** The messages of the request with their roles, the tools, and the options (`model`, `temperature`,
  `max_tokens`, `response_format` and the others). Then the reply: the text, the reasoning folded, the tool calls, the
  finish reason and the usage. For a stream, the view reads the lines with the parser of the lm module
  (`OpenAiStreamChunk` and `StreamAccumulator`). So the view and the model read a stream the same way.
- **Request.** The URL, the masked headers, and the exact body with indents. A button copies the exact text.
- **Reply.** The exact body, or the raw lines of the stream with their numbers.
- **Diff.** The text of this request against the text of the last exchange of the same predictor in the same session.
  The text of a request has a line with the role of each message, then the lines of its content.
  - The view shows each changed part with three lines around it.
  - Each step sends the whole prompt again, so this view shows what the step added.
  - A person can also select another exchange for the diff.

### 5.5 The raw paths

- `/raw/{id}/journal.jsonl`: each row of the journal as one JSON line, with `seq`, `required` and `entry`.
- `/raw/{id}/http.jsonl`: the exchange file as it is.
- `/raw/{id}/x/{xid}/request` and `/raw/{id}/x/{xid}/reply`: the exact body of the request, and the exact body or the
  lines of the reply.

So `curl` and `jq` can read each item, and so can an agent.

## 6. The laws

As in the other parts of the harness, each law is a property or a test, and each law must fail first.

The exchange sink, in lm:

- **L1. No sink, no change.** With no `ExchangeSink` in the context, a model call uses the transport of its client,
  and nothing else.
- **L2. The same result.** A scripted transport can give a success, an error status, a failure, or a stream that ends,
  ends early or gets a close. For each of them, a model call gives the same result and the same lines in the same order
  with a sink and with no sink.
- **L3. An exact record.** The `body` of the request is the body that the transport got. The lines of the reply are
  the lines that the consumer read, in their order.
- **L4. One end.** Each exchange that started has one request line. After its end or its close, it has one reply line.
  A second close adds nothing.
- **L5. No effect of a failed write.** With a sink whose writes fail, each model call gives the same result as with no
  sink.

The exchange file, in the host:

- **L6. The mask.** No line of the file holds a known secret value or the value of `Authorization`.
- **L7. The labels.** Each exchange of an activation has its session. A policy exchange has `predictor = policy`, and
  its `after` is the `seq` of its `StepStarted`. A memory leaf has `compress` or `merge`, and an `llmQuery` has
  `subQuery`.
- **L8. Two threads.** The lines of exchanges on many threads do not mix, and each line is valid JSON.
- **L9. A late thread.** An exchange that ends after the end of its activation still writes its reply line, with no
  error.

The inspector:

- **L10. Only reads.** After any sequence of requests, the journals, the catalog and the exchange files have the same
  bytes.
- **L11. The host check.** A request with another `Host` header gets the status 403 and no data.
- **L12. The JSON view.** Its text decodes to the same `Logged` as the row.
- **L13. The Scala view.** A generator covers each case of `Entry`. For each value, the text has the name of the case
  and the name of each field.
- **L14. The escape.** For any text in an entry, the card holds no markup from that text.
- **L15. Follow.** `after/{seq}` gives the rows with a greater `seq`, all of them, in their order.
- **L16. The diff.** The diff of `a` and `b`, applied to `a`, gives `b`. The diff of `a` with `a` has no change. The
  kept lines occur in `a` and in `b` in the same order.

## 7. The tools outside dspy4s

- **MLflow.** MLflow 3.6 or later takes OTLP/HTTP spans at `/v1/traces`
  ([MLflow docs](https://www.mlflow.org/docs/latest/genai/tracing/opentelemetry/ingest/)), so a JVM process can send
  traces. It shows the attributes of the spans, and it does not know the entries of the journal.
- **Arize Phoenix.** It also takes OTLP, and it shows a span with `llm.input_messages` as a chat
  ([Phoenix docs](https://www.arize.com/docs/phoenix/tracing/concepts-tracing/otel-openinference/semantic-conventions.md)).
- **mitmproxy.** In its reverse proxy mode, it shows each exact request and reply with no code. It does not know the
  session or the step.

All three need data with labels to link an exchange to its step, and none of them shows the cases of `Entry`. So this
proposal builds the exchange file and the inspector first. A later exporter can read the exchange files and the
journals, and send OTLP spans with the GenAI attributes. Then MLflow or Phoenix can show the same data, and the files
do not change.

## 8. The order of the work

- **O1. The sink, in core and lm.** `ExchangeSinkRef`, `ExchangeSink`, `RecordedTransport`, and their use in
  `OpenAiLanguageModel`. Laws L1 to L5.
- **O2. The exchange file, in the host.** `ExchangeFile`, the labels, the mask, the context of the activation, and the
  link of a probe. Laws L6 to L9. A live run measures the sizes of 4.6.
- **O3. The inspector.** The module, the server, the list of the sessions, the tree, the views of an entry, and the
  follow of a session that runs. Laws L10 to L15.
- **O4. The views of an exchange.** Chat, request, reply and diff. Law L16.
- **O5. The end of `DSPY_TRACE`.** The live drivers read the exchange files, and `TracedModel` and `TracedStreams` go
  (9.2, choice 4).
- **O6. Later, if necessary.** The OTLP exporter of section 7.

## 9. The choices

### 9.1 Decided

- **Our own inspector,** with raw views and a formatted view of each item (the choice of 2026-11-12).
- **The record at the HTTP level,** through a service of the context, as the stream sink does.
- **A file next to the journal,** not a table in the journal file:
  - The writer of the journal is one thread with a bounded mailbox, and a model call must never wait for it.
  - The rules of the journal (one writer, the flush, the replay) do not change.
  - The name ends with `.jsonl`, and `Catalog.journalFiles` takes only the `.db` files, so the host does not see the
    file as a journal.
  - The cost: a session has two files, so a removal must remove both.
- **The card in Scala,** with a `match` on `Entry`, so the compiler checks that each case has a card.

### 9.2 Decided after the review

On 2026-11-12 the review took each proposal of this list:

1. **On by default.** The exchange file is on, and `DSPY_EXCHANGES=off` turns it off.
2. **Plain lines first** (4.6). After the measure of O2, a choice can add compression. The options are a gzip member
   for each line (`gzip -dc` reads the file), or the lines of a stream only when the stream fails.
3. **A fixed port.** The default port is 7411, so that a bookmark stays valid. `--port 0` takes a free port.
4. **No `DSPY_TRACE`.** O5 removes it, `TracedModel` and `TracedStreams`.
5. **The exchanges of a probe** go in the file of its caller, with the link of the probe.
