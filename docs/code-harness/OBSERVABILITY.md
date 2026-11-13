# The inspector and the exchange files: a proposal

Status: the review took the proposal on 2026-11-12 (9.2), and O1 to O5 are built. It covers two needs. A person
must see the journal of a session. A person must also see each HTTP request that the harness sends to the model
endpoint, with its reply. Each of the two must have raw views and a formatted view.

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
- **`DSPY_TRACE`, before O5.** `TracedModel` and `TracedStreams` wrote one JSON line for each model call. O5 removed
  them (section 8). The line has these gaps:
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
 "url":"<base>/chat/completions","headers":{"Authorization":"«secret:Authorization»"},"stream":true,
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
- Each line goes to the file in one write, under one lock of the process for all the exchange files. Two activations
  of one session write to one file (a late thread of the first one, and the second one), so a lock of each file
  object is not enough. So the lines of two threads do not mix.
- Each write opens the file, appends the line and closes it, through a `FileOutputStream`. A thread that ends after
  its activation still writes its line. A channel of NIO stops on the interrupt flag, and a stop sets the flag on the
  thread of a policy call, so a channel would lose the reply line of a cut exchange (law L9 and its mutant).

### 4.4 The mask

- Each text of a line goes through the mask of the host (`Secrets.maskText`, 5.6) before the write, also the names of
  the headers.
- The value of a header with a secret name, by the rule of the variables of 5.6 (`Environment.isSecret`), is the mark
  of its name, for example `«secret:Authorization»`. This is true also when the mask does not know the value.
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

A live run of 2026-11-12 measured the sizes. It used the CLI in script mode, a private OpenAI-compatible endpoint and
an open model. It had three turns: a cell that counts the lines of a file, an `llmQuery` for a haiku, and a story of
about 300 words.

- The file had 11 exchanges and 235 KB: 5 of the policy, 1 `compress`, 4 `merge` and 1 `subQuery`.
- A policy request was 12 to 13.5 KB, because each step sends the whole prompt again.
- A stream line was about 190 bytes. The reply of a short step was 6 to 11 KB in 31 to 59 lines. The story was 88 KB
  in 461 lines.
- A request of a memory leaf or of `subQuery` was 0.7 to 1.3 KB, and its reply 1.5 to 2.9 KB.
- The first line of a stream came 0.3 to 0.6 s after the request, and a whole exchange took 0.5 to 3 s.

So a turn adds about 80 KB, and a session of 50 model calls has about 1 to 5 MB. This size is small, so the file keeps
plain JSON lines (9.2, choice 2). The estimate before the run was 10 to 50 MB, because it took 250 bytes for each
token and a prompt of 20 to 80 KB.

## 5. The inspector

### 5.1 The server

- `dspy4s-harness --inspect [--root DIR] [--port N]` starts the server and prints its URL. The default port is 7411,
  and `--port 0` takes a free port. `--inspect` takes no session, no list, no script mode and no folder.
- It uses the JDK `com.sun.net.httpserver.HttpServer` on virtual threads, so it adds no dependency.
- It listens on 127.0.0.1 only. It answers only a request whose `Host` header is `127.0.0.1:PORT` or
  `localhost:PORT`, so a web page that uses DNS rebinding gets 403 and no data. It sends no CORS headers, so a page of
  another origin cannot read a reply.
- It answers only `GET`, and each other method gets 405.
- A page has a policy of its scripts (`Content-Security-Policy`): only the script with the nonce of that reply runs.
  So a script that a text could make does not run, also if an escape fails. A reply also has `no-store`, `nosniff`,
  `no-referrer`, and a page `X-Frame-Options: DENY`.
- `Store` reads the files of the root. It opens each journal with `SessionFile.readOnly` and the catalog with
  `Catalog.readOnly` for one call, so the inspector holds no connection while it waits. An exchange file is a plain
  read.
- **A fact that O3 found.** A read-only connection to a SQLite file with WAL and no `-wal` file makes a `-wal` and a
  `-shm` file, and they stay after its close. `SessionFile.readOnly` said that nothing changes on the disk, and this
  was not true for the reads of the host either. Now `SessionFile.readUrl` opens a file with no `-wal` file with
  `immutable=1`: it has no open writer, and nothing comes on the disk. A file with a `-wal` file has an open writer,
  and its read uses that file, so it sees each commit. So the inspector also reads a root that it cannot write, for
  example an archive.
- It is a new module, `harness-inspect`, on `harness-host`. The CLI uses it for `--inspect`.

### 5.2 The pages

- **`/`: the sessions,** the newest first: the rows of the catalog, and the fold of each journal that the catalog does
  not have (10.2). A row has the time of the last entry, the status, the first message, the folder, the id and a link
  to the parent. The probes of a session show under it, with a link to `/p/{name}`.
- **`/s/{id}`: one session as a tree.** An `Input` comes first, then its turn, then the steps of the turn. Each step
  holds its `Acted`, its effects and its `Observed`, and the turn ends with its `TurnEnded`.
  - Each exchange shows after the last entry whose `seq` is not more than its `after`. Its row has the predictor,
    stream or plain, the status, the end (or `open`), the wait for the first line, the whole time, the bytes of the
    request and of the reply, the tokens of the usage, and links to its raw request and reply.
  - The bar has the facts (the id, the folder, the status and the counts), a switch for the view of all the entries
    (card, Scala or JSON), the follow, the filters, and the raw paths. The filters of the kinds of entries and of the
    predictors are folded, so the bar stays short in a small window. Each entry also has its own switch.
- **`/p/{name}`: the journal of a probe,** as a tree with no exchanges. Its exchanges are in the file of its caller.
- **`/s/{id}/x/{xid}`: one exchange** with the four views of 5.4.
- **A session that runs.** Each second, the page asks for `/s/{id}/after/{seq}?size=N`. The fragment has each entry
  with a greater `seq` and the status of the session. When the exchange file does not have the size `N`, it also has
  each exchange row again. The page adds the entries, places the exchanges after their entries again, updates the
  status and the counts, and goes to the end when it was at the end. The journal uses WAL, so a reader sees each commit
  whole. An exchange with a `request` line and no `reply` line shows as open.

### 5.3 The views of an entry

- **Scala.** The `Entry` of the decoded row as Scala text with indents (`ScalaText`). The printer reads each value
  through `Product` (`productPrefix`, `productElementNames` and `productIterator`). So it gives the names of the cases
  and of the fields as the code has them.
  - The first version showed the whole `Logged`, so each view began with `Logged(seq = ..., meta = ...)`. The bar of
    the row shows the `seq` and each field of the meta: the time, the source, a mark for `Taint.External`, and a note
    for a row that is not required. So the view shows only the entry (2026-11-12).
  - The bar first showed only the name of the case of the source. Now it also shows the session of
    `Source.Child`, and the session and the call of `Source.Parent`, with a link to the page of the session.
  - A string is a Scala literal, and a string with a line end is a `"""` block. A `Long` has the suffix `L`, and a
    `Json` value prints as `Json(<its text>)`.
  - A value goes on one line when it fits in 100 characters and holds no block. A card uses the form on one line
    (`ScalaText.line`) for a short part, for example the scope.
  - A row that does not decode shows its error in this view.
- **JSON.** The `entry` field of the row as it is in the file, with an indent of 2 (`JsonText.pretty`). The value
  comes from the text of the file, not from a new encode of the decoded row. As for the Scala view, the bar shows the
  `seq` and the meta, so the view leaves them out (2026-11-12). A row that does not decode shows its whole text, so
  its meta stays in view, and a text that is not JSON stays as it is. The first version used
  the printer of zio-blocks, which writes an empty object on three lines, with a blank line inside. `JsonText` puts
  each field and each item on a line of its own, and writes `{}` and `[]`. Each key and each leaf keeps the text of the
  printer of zio-blocks, so no string changes, and law L19 shows that the value stays the same.
- **Colors.** The Scala view and the JSON view have colors (2026-11-12). The server cuts the text of each view into
  pieces (`Highlight`), and it puts each piece with a color in a span with a class: `t-key` for a field or a key,
  `t-str` for a string, `t-num` for a number, `t-lit` for `true`, `false` and `null`, and `t-type` for the name of a
  case or of a collection. The marks and the plain text have no span, so a page stays small.
  - The colors come from the tokens of the theme, so a dark screen gets its own colors.
  - The pieces, joined, give the text again, for any text, and each piece goes through the HTML escape. So a text
    that is not JSON or not Scala keeps each character, and a text of an entry cannot add markup.
  - A row that does not decode shows its error with no colors.
  - The code view of the Request and Reply views of an exchange (5.4) has the colors of JSON too.
- **Card.** A formatted card for each case of `Entry`. It shows the reasoning folded, the text, the code of a cell,
  the output and the outcome. It also shows the time, the source, and a mark for `Taint.External`.
  - The server makes the HTML of the card with a `match` on `Entry`. With `-Werror`, a `match` that does not cover each
    case does not compile. So a new case of `Entry` must get a card.
  - Each text goes through an HTML escape. A long block of a card has a scroll.

### 5.4 The views of an exchange

The page `/s/{id}/x/{xid}` has a bar with the facts of the exchange and links to the exchange before and after it.
Four buttons select its view. A row of the tree links to the page.

- **Chat.** The options of the request (`model`, `temperature`, `max_tokens`, `stream_options` and the others), the
  tools (folded), and the messages with their roles. A system message is folded, because the instructions of the policy
  are long. Then the reply: the text, the reasoning (folded), the tool calls, the finish reason and the usage.
  - `Chat.read` sends the request again through `OpenAiLanguageModel` on a transport that gives the recorded status,
    headers, and body or lines. So the view and the model read a reply on one path: the same parser of the stream, the
    same assembly of the chunks and of the tool calls, and the same usage. The view copies no parser.
- **Request.** The URL, the masked headers, the size, and the code view of the body with indents.
- **Reply.** The status, the end, the error and the headers, then the code view of the raw lines of a stream, or of
  the body of a plain reply with indents.
- **The code view** of the Request and Reply views is as an editor shows a JSON file (2026-11-12). Each line has its
  number and the colors of JSON. The rest of a line after its first spaces goes in a block with that indent, so a
  long line, for example the system message, wraps under its own indent and not at the left edge. The first version
  showed the body in one block of text, and a long string made a wall of text.
  - Each of the two views has a `Copy` button, which copies the exact text from a hidden copy, and a `Download` link
    (5.5). So the bar has no raw links. A first version had them, and a raw path in a browser shows the exact text
    with no layout.
  - A JSON string has no line end, so each line gets its colors alone, also a `data:` line of a stream.
- **Diff.** The text of this request against the text of the last earlier exchange of the same predictor in the same
  session, or of the exchange that `?against={xid}` names. A list selects another exchange. The text of a request has
  a line with the role of each message, then the lines of its content and of its tool calls.
  - The view gives the count of the added and the removed lines, and each changed part with three lines around it.
  - Each step sends the whole prompt again, so this view shows what the step added.
  - `LineDiff` keeps the common start and the common end of the two texts, and it takes the middle from the table of
    the longest common subsequence. A middle whose table has more than 4,000,000 cells is one removal and one addition,
    so a diff never needs much memory.

### 5.5 The raw paths

- `/raw/{id}/journal.jsonl`: each row of the journal as one JSON line, with `seq`, `required` and `entry`.
- `/raw/{id}/http.jsonl`: the exchange file as it is.
- `/raw/{id}/x/{xid}/request` and `/raw/{id}/x/{xid}/reply`: the exact body of the request, and the exact body or the
  lines of the reply.

So `curl` and `jq` can read each item, and so can an agent.

A browser shows each raw path, because the server sends it as text: `text/plain`, or `application/json` for the body
of a request, each with `charset=utf-8`. The first version sent the two files as `application/x-ndjson`. A browser does
not show that type, so it saved the file in Downloads with the name `http.jsonl.ndjson` (found on 2026-11-12).

With `?download=1`, a raw path gives the same bytes with `Content-Disposition: attachment`, so a browser saves them as
a file. The name is `{id}.journal.jsonl`, `{id}.http.jsonl`, `{xid}.request.json` or `{xid}.reply.txt`. An exchange id
comes from a file, so the name keeps only ASCII letters, digits, `-`, `_` and `.`, and each other character becomes
`_`. The bar of a session has a `Raw` group of links and a `Download` group of links, and the Request and Reply
views of an exchange each have a `Download` link (5.4).

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

- **L10. Only reads.** After any sequence of requests, the files of the root have the same bytes, and no file comes.
  The inspector also reads a root that it cannot write, and the rows of its catalog. Two laws of the host show the
  reads of `readOnly`: a closed journal and a closed catalog get no new file, and a journal with an open writer gives
  each commit.
- **L11. The host check.** A request with another `Host` header gets the status 403 and no data, and a method that is
  not `GET` gets 405. A page has the policy of its scripts with the nonce of its script, and no reply has a CORS
  header.
- **L12. The JSON view.** Its text is the `entry` field of the text of the row, and it decodes to the same `Entry` as
  the row. A row that does not decode shows its whole text.
- **L13. The Scala view.** A generator covers each case of `Entry`. For each value, the text has the name of the case
  and the name of each field. The Scala view of a row is the text of its entry, and the bar of the row shows its
  `seq`, its time, its source with the fields of the source, the mark of `Taint.External` only for that taint, and
  the note only for a row that is not required.
- **L14. The escape.** For any text in an entry, the card and the row hold no markup from that text. The escape gives
  an entity for each of the five marks of HTML.
- **L15. Follow.** `after/{seq}` gives the rows with a greater `seq`, all of them, in their order, and the status of
  the session. A reader that follows a journal while a writer appends gets each row once, in order.
- **The pages.** Each exchange shows after its entry, and an exchange with no reply shows as open. The list of the
  sessions comes from the catalog, and from the journals with no catalog. The raw paths give the rows, the exchange
  file and the bodies of an exchange, and a path that leaves the root gets 404.
- **L16. The diff.** The diff of `a` and `b`, applied to `a`, gives `b`. The diff of `a` with `a` has no change. The
  kept lines occur in `a` and in `b` in the same order, and they are as many as the longest common subsequence. Each
  change is in one part, with at most three kept lines around it, and the line numbers of a part point at its lines.
  Two texts past the limit of the table still give a diff that gives `b`.
- **The views of an exchange.** The chat view reads a stream as the model does (the text, the reasoning, the tool
  calls, the finish reason and the usage), and a plain reply too. The page has its four views, the exact body and the
  numbered lines of the reply. The diff is against the last earlier exchange of the predictor, or the one of
  `against`.
- **L17. The raw paths in a browser.** Each raw path has a text type with `charset=utf-8` and no
  `Content-Disposition`. With `download=1`, it gives the same body and the same type, with
  `attachment; filename="..."` and the name of 5.5. An exchange id with other characters gives a name with `_` in
  their place. The bar of a session links to each of its downloads, and the Request and Reply views of an exchange
  each link to their download.
- **L18. The colors.** For any text, the pieces of each lexer, joined, give the text, and no piece is empty. The HTML
  of the pieces shows the same text and holds no markup from it. The JSON pieces of a value are its keys, its strings,
  its numbers and its literals, in their order. In the Scala view of an entry, the name of the case is a type, each
  field is a key, and each string, number and `Boolean` of a field is one piece. A row has the colors in its Scala
  view and its JSON view, and an exchange in its Request view and in the lines of its reply.
- **L19. The layout of the JSON view.** The layout gives the same value, and no line of it is blank. An empty object
  is `{}` and an empty array is `[]`, also inside a value.
- **L20. The code view.** Read back, the code view of the lines of any text gives the lines again, each with its
  first spaces, and the lines of the layout of a value give the layout. The numbers go from 1 in order, and the view
  holds no markup from the text. Each line has the colors of JSON, under its indent. The Request and Reply views each
  have a code view, a `Copy` button with a hidden copy of the exact text, and a `Download` link, and the bar of the
  exchange has no raw link.

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
  (9.2, choice 4). As built:
  - The CLI reads no `DSPY_TRACE`, and `TracedModel`, `TracedStreams` and their laws are gone.
  - `scripts/live/chat.sh` sets no trace file. Its notes give the exchange file and the command of the inspector.
  - `scripts/live/scenario.sh` prints the exchanges of the session after its journal, with
    `scripts/live/exchanges.py`, and then the command of the inspector for the root of the scenario.
  - `exchanges.py` prints one line for each exchange: the `seq` before it, the time, the predictor or the probe, the
    status, the end, the time of the reply, the size of the request, the tokens and the start of the reply text.
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
4. **No `DSPY_TRACE`.** O5 removed it, `TracedModel` and `TracedStreams`.
5. **The exchanges of a probe** go in the file of its caller, with the link of the probe.
