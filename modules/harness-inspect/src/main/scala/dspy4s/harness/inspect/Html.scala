package dspy4s.harness.inspect

import dspy4s.harness.kernel.Entry

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import scala.compiletime.constValueTuple
import scala.deriving.Mirror

/** The parts of the pages of the inspector (docs/code-harness/OBSERVABILITY.md, 5.2): the escape of a text, the shell
  * of a page, its style and its script.
  */
object Html:

  /** `text` with the five marks of HTML as entities, so the text never makes markup. */
  def escape(text: String): String =
    val out = StringBuilder()
    text.foreach {
      case '&'   => out ++= "&amp;"
      case '<'   => out ++= "&lt;"
      case '>'   => out ++= "&gt;"
      case '"'   => out ++= "&quot;"
      case '\''  => out ++= "&#39;"
      case other => out += other
    }
    out.result()

  /** The names of the cases of `Entry`, from the compiler, for the filter of the kinds. */
  val kinds: List[String] = caseNames[Entry]

  /** The names of the predictors of a work session, and `none` for an exchange outside a predictor. */
  val predictors: List[String] = List("policy", "compress", "merge", "shorten", "subQuery", "finisher", "none")

  private inline def caseNames[T](using m: Mirror.SumOf[T]): List[String] =
    constValueTuple[m.MirroredElemLabels].toList.map(String.valueOf)

  private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
  private val day   = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

  /** The time of `millis` on the clock of this machine, to the millisecond. */
  def time(millis: Long): String = clock.format(Instant.ofEpochMilli(millis))

  private val minuteOf = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

  /** The hour and the minute of `millis` on the clock of this machine, for a message of the chat (5.7). */
  def minute(millis: Long): String = minuteOf.format(Instant.ofEpochMilli(millis))

  /** The day and the minute of `millis` on the clock of this machine. */
  def date(millis: Long): String = day.format(Instant.ofEpochMilli(millis))

  /** The links of the raw paths `items` under `base` (5.5): one group that a browser shows as text, and one group with
    * `download=1` that a browser saves as files. `base` must be escaped.
    */
  def rawLinks(base: String, items: Vector[String]): String =
    def links(query: String) = items.map(item => s"""<a href="$base/$item$query">${escape(item)}</a>""").mkString(" ")
    s"""<span class="group">Raw: ${links("")}</span><span class="group">Download: ${links("?download=1")}</span>"""

  /** The buttons that switch the views of a box (5.8), each with its key and its label. The first one is on. The box
    * has `data-forms` or is a tab of a prompt, and each of its views has `data-form-view` with the key of its button.
    */
  def forms(views: (String, String)*): String =
    views.zipWithIndex.map { case ((key, label), i) =>
      s"""<button data-form="${escape(key)}"${if i == 0 then " class=\"on\"" else ""}>${escape(label)}</button>"""
    }.mkString("""<span class="forms">""", "", "</span>")

  /** A page with `title`, `body` and the class `bodyClass` of its body, the style and the script. The script has
    * `nonce`, so the policy of the page lets it run, and a script that a text could make does not run.
    */
  def page(title: String, nonce: String, body: String, bodyClass: String = "view-card"): String =
    "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
      "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
      s"<title>${escape(title)}</title><style>$style</style></head><body class=\"${escape(bodyClass)}\">$body" +
      s"<script nonce=\"$nonce\">$Script</script></body></html>"

  /** The rules that hide each kind and each predictor that a filter turns off. */
  private val hides: String = (kinds.map(k => s"kind-$k") ++ predictors.map(p => s"pred-$p"))
    .map(name => s".hide-$name .$name{display:none}").mkString

  private val style: String =
    """
:root{--bg:#fbfbfa;--fg:#1d1d1b;--muted:#6b6b66;--line:#e4e3de;--card:#fff;--code:#f3f2ee;--accent:#2f5fb3;
--policy:#2f5fb3;--memory:#7a5c00;--leaf:#6b3fa0;--warn:#a34b00;--bad:#b3261e;--good:#2e7d32;--x:#f1f5fb}
@media (prefers-color-scheme: dark){:root{--bg:#161615;--fg:#e8e6e1;--muted:#9a978f;--line:#2e2d2a;--card:#1e1e1c;
--code:#242421;--accent:#8ab4f8;--policy:#8ab4f8;--memory:#e0b84f;--leaf:#c3a0f0;--warn:#f0a35c;--bad:#f28b82;
--good:#81c995;--x:#1c2330}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI",
Helvetica,Arial,sans-serif}
a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}
pre,code{font:12.5px/1.45 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
pre{margin:4px 0;padding:8px 10px;background:var(--code);border-radius:6px;white-space:pre-wrap;
overflow-wrap:anywhere}
.bar{position:sticky;top:0;z-index:2;background:var(--bg);border-bottom:1px solid var(--line);padding:10px 16px}
.bar h1{font-size:16px;margin:0 0 4px;font-weight:600;overflow-wrap:anywhere}
.facts{color:var(--muted);font-size:12.5px;overflow-wrap:anywhere}
.controls{display:flex;flex-wrap:wrap;gap:6px 14px;align-items:center;margin-top:8px;font-size:12.5px}
.controls .group{display:flex;flex-wrap:wrap;gap:4px 8px;align-items:center}
.controls label{white-space:nowrap;color:var(--muted)}
.controls .cut{color:var(--warn)}
button{font:inherit;font-size:12px;border:1px solid var(--line);background:var(--card);color:var(--fg);
border-radius:5px;padding:1px 8px;cursor:pointer}
button.on{border-color:var(--accent);color:var(--accent)}
main{padding:8px 16px 48px;max-width:1100px}
.row{padding:6px 10px;margin:4px 0;background:var(--card);border-radius:6px;border:1px solid var(--line)}
body.indent .depth-1{margin-left:18px}body.indent .depth-2{margin-left:36px}
.meta{display:flex;flex-wrap:wrap;gap:4px 10px;align-items:baseline;color:var(--muted);font-size:12px}
.meta .kind{font-weight:600;color:var(--fg)}
.meta .views{margin-left:auto;display:flex;gap:4px}
.meta .views button{font-size:11px;padding:0 6px}
.taint{color:var(--warn)}
.v{display:none}
body.view-card .row:not([data-view]) .v-card,body.view-scala .row:not([data-view]) .v-scala,
body.view-json .row:not([data-view]) .v-json,.row[data-view=card] .v-card,.row[data-view=scala] .v-scala,
.row[data-view=json] .v-json{display:block}
.v-card p{margin:4px 0;white-space:pre-wrap;overflow-wrap:anywhere}
.v-card pre{max-height:24em;overflow:auto}
.x-view{display:none}
body.tab-chat .x-chat,body.tab-request .x-request,body.tab-reply .x-reply,body.tab-diff .x-diff{display:block}
.x-view h2{font-size:14px;margin:14px 0 6px}
.x-view dl{display:grid;grid-template-columns:max-content 1fr;gap:2px 12px;font-size:12.5px}
.x-view dt{color:var(--muted)}.x-view dd{margin:0;overflow-wrap:anywhere}
.msg{border:1px solid var(--line);border-radius:6px;padding:6px 10px;margin:6px 0;background:var(--card)}
.msg summary{cursor:pointer;color:var(--muted);font-size:12.5px}
.msg .role{font-size:11px;font-weight:600;color:var(--muted);text-transform:uppercase}
.msg .text{white-space:pre-wrap;overflow-wrap:anywhere}
.msg .role::before{content:"";display:inline-block;width:7px;height:7px;border-radius:50%;margin-right:6px;
vertical-align:1px;background:var(--muted)}
.role-user .role::before{background:var(--good)}.role-assistant .role::before{background:var(--policy)}
.role-tool .role::before{background:var(--leaf)}
.code{font:12.5px/1.45 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;margin:4px 0;padding:8px 10px 8px 0;
background:var(--code);border-radius:6px}
.code .ln{display:grid;grid-template-columns:4.5em 1fr}
.code .n{color:var(--muted);text-align:right;padding-right:1.2em;user-select:none}
.code .c{white-space:pre-wrap;overflow-wrap:anywhere;padding-left:calc(var(--i) * 1ch + 4ch);text-indent:-4ch}
.tools{display:flex;gap:6px;justify-content:flex-end;margin:6px 0 2px}
.tools .group{display:flex;gap:4px;margin-right:auto}
.replies.show-response .r-stream,.replies.show-stream .r-response{display:none}
a.button{font-size:12px;border:1px solid var(--line);background:var(--card);color:var(--fg);border-radius:5px;
padding:1px 8px;text-decoration:none}
.t-key{color:var(--leaf)}.t-str{color:var(--good)}.t-num{color:var(--warn)}.t-lit{color:var(--memory)}
.t-type{color:var(--accent)}.t-keyword{color:var(--leaf)}.t-def{font-weight:600}.t-annotation{color:var(--memory)}
.t-comment{color:var(--muted);font-style:italic}
.hunk{margin:8px 0}.hunk-head{color:var(--muted);font:12px ui-monospace,Menlo,monospace}
pre.diff{padding:4px 0}
.d{display:block;padding:0 10px}
.d.add{background:color-mix(in srgb,var(--good) 18%,transparent)}
.d.del{background:color-mix(in srgb,var(--bad) 18%,transparent)}
.d.ctx{color:var(--muted)}
.filters summary{cursor:pointer;color:var(--muted)}
.filters[open]{flex-basis:100%}
.filters .group{margin:4px 0}
.v-card h2{font-size:13px;margin:2px 0;font-weight:600}
.v-card details summary{cursor:pointer;color:var(--muted);font-size:12.5px}
.v-card .note{color:var(--muted);font-size:12px}
.v-card .error{color:var(--bad)}
.v-card dl{display:grid;grid-template-columns:max-content 1fr;gap:2px 12px;margin:4px 0;font-size:12.5px}
.v-card dt{color:var(--muted)}.v-card dd{margin:0;overflow-wrap:anywhere}
.meta .kind::before{content:"";display:inline-block;width:7px;height:7px;border-radius:50%;margin-right:6px;
vertical-align:1px;background:transparent}
.kind-TurnStarted .kind::before,.kind-TurnEnded .kind::before{background:var(--accent)}
.kind-Input .kind::before{background:var(--good)}
.kind-StopRequested .kind::before,.kind-JobsStopRequested .kind::before{background:var(--warn)}
.exchange{background:var(--x)}
.exchange .meta a.kind{color:var(--accent);text-decoration:underline;text-underline-offset:2px}
.exchange.pred-policy .kind::before{background:var(--policy)}
.exchange.pred-compress .kind::before,.exchange.pred-merge .kind::before,.exchange.pred-shorten .kind::before{
background:var(--memory)}
.exchange.pred-subQuery .kind::before{background:var(--leaf)}
.exchange.open .kind::after{content:" (open)";color:var(--warn)}
table{border-collapse:collapse;width:100%;font-size:13px}
th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}
th{color:var(--muted);font-weight:500}
td.first{overflow-wrap:anywhere}
.probes{font-size:12px;color:var(--muted)}
@media (max-width:640px){body.indent .depth-1{margin-left:8px}body.indent .depth-2{margin-left:16px}
main{padding:8px 16px 40px}
.meta .views{margin-left:0}}
body.mode-chat #rows,body.mode-trajectory #rows,body.mode-entries #story{display:none}
body.mode-chat .entries-only,body.mode-trajectory .entries-only{display:none}
body:not(.mode-trajectory) #story .cols{display:none}
#story .item{margin:10px 0}
.say{max-width:780px;padding:8px 12px;border-radius:10px;margin:6px 0}
.say .who{font-size:11.5px;color:var(--muted);margin-bottom:2px}
.say .text{white-space:pre-wrap;overflow-wrap:anywhere}
.say.person{margin-left:auto;background:var(--x);border:1px solid var(--line)}
.say.other{background:var(--card);border:1px dashed var(--line)}
.say.reply{background:var(--card);border:1px solid var(--line)}
.say.reply.bad{border-color:var(--bad)}
.say.working .text{color:var(--warn)}
details.work>summary{cursor:pointer;color:var(--muted);font-size:12.5px;margin:4px 0}
.traj{margin:4px 0 8px;border-left:2px solid var(--line);padding-left:10px}
.step{margin:6px 0}.step-head{font-size:12px;color:var(--muted);margin:8px 0 2px}
.r{display:grid;grid-template-columns:64px minmax(0,1fr) 64px 112px 52px 72px;gap:8px;align-items:baseline;
padding:3px 6px;border-radius:5px;font-size:13px}
.r:hover{background:var(--card)}
.r .kind{font:600 10.5px ui-monospace,SFMono-Regular,Menlo,monospace;padding:1px 4px;border-radius:4px;
text-align:center;background:var(--code)}
.r .sum{min-width:0;overflow-wrap:anywhere}
.r .sum details summary{cursor:pointer;color:var(--muted);font-size:12px}
.r .sum pre{max-height:24em;overflow:auto}
.r .n{text-align:right;color:var(--muted);font-variant-numeric:tabular-nums;font-size:12px;white-space:nowrap}
.r .note,.r .link{color:var(--muted);font-size:12px}
.r.cols{color:var(--muted);font-size:11.5px}.r.cols:hover{background:none}
.k-model .kind{color:var(--policy)}.k-cell .kind,.k-call .kind{color:var(--warn)}.k-output .kind{color:var(--good)}
.k-memory{color:var(--muted)}.k-memory .kind{color:var(--memory)}.k-llm .kind{color:var(--leaf)}
.k-note .kind{color:var(--muted)}.r.bad .kind{color:var(--bad)}
body.mode-trajectory .say{max-width:none;margin-left:0;border-radius:6px}
.say .text.md{white-space:normal}
.say .meta{font-size:11.5px;color:var(--muted);margin-top:6px}
body:not(.mode-chat) .c-only,body.mode-chat .t-only,body.mode-chat .item.quiet{display:none}
body.mode-chat #story{max-width:780px;margin:0 auto}
#story .item{scroll-margin-top:120px}
body.mode-chat #story .item{margin:6px 0}
body.mode-chat #story .item.turn{margin:22px 0 26px}
body.mode-chat .say.person,body.mode-chat .say.other{max-width:82%;width:fit-content}
body.mode-chat .say.person{border-radius:14px 14px 4px 14px}
body.mode-chat .say.reply{background:none;border:none;padding:0;max-width:none}
body.mode-chat .say.reply .who{display:none}
body.mode-chat .say.reply.bad .text{color:var(--bad)}
.ev{display:flex;align-items:center;gap:10px;color:var(--muted);font-size:12px;margin:12px 0}
.ev::before,.ev::after{content:"";flex:1;min-width:24px;border-top:1px solid var(--line)}
.ev span{max-width:85%;text-align:center;overflow-wrap:anywhere}.ev b{font-weight:600}
.mk.ok{color:var(--good)}.mk.bad{color:var(--bad)}.mk.run{color:var(--warn)}.mk{margin-right:5px}
body.mode-chat details.work>summary{display:inline-flex;align-items:center;list-style:none;border:1px solid var(--line);
border-radius:7px;padding:2px 9px;background:var(--card);margin:10px 0 8px}
body.mode-chat details.work>summary::-webkit-details-marker{display:none}
body.mode-chat details.work>summary::before{content:"▸";margin-right:6px}
body.mode-chat details.work[open]>summary::before{content:"▾"}
.brief{border-left:2px solid var(--line);margin:0 0 12px 6px;padding:2px 0 2px 14px;font-size:12.5px}
.bstep{display:grid;grid-template-columns:22px minmax(0,1fr) 56px;gap:8px;padding:3px 0;align-items:baseline}
.bstep .i,.bstep .n{color:var(--muted);font-variant-numeric:tabular-nums}.bstep .n{text-align:right;white-space:nowrap}
.bstep .c{display:block;color:var(--muted);font:12px ui-monospace,SFMono-Regular,Menlo,monospace;white-space:nowrap;
overflow:hidden;text-overflow:ellipsis}
.bstep .ok{color:var(--good)}.bstep .bad{color:var(--bad)}.bstep .run{color:var(--warn)}
.bfoot{font-size:12px;color:var(--muted);margin-top:4px}.bfoot button{font-size:11.5px}
body.mode-chat details.prompt>summary.r{display:flex;justify-content:center;padding:0;margin:8px 0}
body.mode-chat details.prompt>summary.r:hover{background:none}
body.mode-chat details.prompt>summary .kind,body.mode-chat details.prompt>summary .n{display:none}
body.mode-chat details.prompt>summary .sum{border:1px solid var(--line);border-radius:999px;padding:1px 12px;
background:var(--card);font-size:12px;color:var(--muted)}
.md p{margin:0 0 8px}.md p:last-child{margin-bottom:0}
.md code{background:var(--code);padding:1px 5px;border-radius:4px;font-size:12.5px}
.md-code{border:1px solid var(--line);border-radius:8px;margin:6px 0 10px;background:var(--code);overflow:hidden}
.md-code-head{display:flex;justify-content:space-between;align-items:center;padding:2px 10px;
border-bottom:1px solid var(--line);font-size:11.5px;color:var(--muted)}
.md-code-head button{font-size:11px;padding:0 6px}
.md .md-code pre{margin:0;padding:8px 12px;background:none;border-radius:0;white-space:pre;overflow:auto}
.md pre code{background:none;padding:0}
.md ul,.md ol{margin:4px 0 8px;padding-left:22px}.md li>p{margin:0}
.md blockquote{margin:6px 0;padding:2px 12px;border-left:3px solid var(--line);color:var(--muted)}
.md h3,.md h4,.md h5{margin:12px 0 6px;font-size:15px}.md h4{font-size:14px}.md h5{font-size:13.5px}
.md hr{border:none;border-top:1px solid var(--line);margin:10px 0}
.md-table{overflow-x:auto;margin:6px 0 10px}.md table{width:auto}
.md th,.md td{border:1px solid var(--line);padding:4px 8px}
.md .md-link{color:var(--accent);border-bottom:1px dotted var(--accent);cursor:help}
.files{position:relative}.files>summary{cursor:pointer;list-style:none}
.files>summary::-webkit-details-marker{display:none}.files>summary::after{content:" ▾"}
.files .menu{position:absolute;right:0;top:1.7em;background:var(--card);border:1px solid var(--line);border-radius:6px;
padding:6px 10px;z-index:3;display:flex;flex-direction:column;gap:4px;white-space:nowrap}
details.prompt>summary{list-style:none;cursor:pointer}
details.prompt>summary::-webkit-details-marker{display:none}
details.prompt>summary .sum::before{content:"▸ ";color:var(--muted)}
details.prompt[open]>summary .sum::before{content:"▾ "}
.k-system .kind{color:var(--accent)}
.pbox{border:1px solid var(--line);border-radius:6px;background:var(--card);padding:8px 10px;margin:2px 0 10px}
.ptabs{display:flex;flex-wrap:wrap;gap:4px;margin-bottom:6px}
.pbox .note{color:var(--muted);font-size:12px;margin:4px 0}
.pbox pre{max-height:36em;overflow:auto}
.pbox h3{font-size:13px;margin:10px 0 4px}
.pbox>.pview>dl{display:grid;grid-template-columns:max-content 1fr;gap:2px 12px;font-size:12.5px;margin:4px 0}
.pbox>.pview>dl dt{color:var(--muted)}.pbox>.pview>dl dd{margin:0;overflow-wrap:anywhere}
.ptext .mk{color:var(--accent);font-weight:600}
.forms{margin-left:6px}.forms button{font-size:11px;padding:0 6px}
.ptask{background:var(--code);border-radius:6px;padding:8px 12px;max-height:36em;overflow:auto}
.ptask :not(pre)>code{background:var(--card)}
.fields{border-collapse:collapse;font-size:12.5px;margin:2px 0 8px;width:100%}
.fields td{border-top:1px solid var(--line);padding:4px 10px 4px 0;vertical-align:top}
.fields td:first-child{white-space:nowrap}.fields td p{margin:0}
.fields .ty{color:var(--accent);font:12px ui-monospace,Menlo,monospace;white-space:nowrap}
.fnote{color:var(--muted);font-size:12px}
.rest{color:var(--muted);font-size:12.5px;margin-top:8px}
.msg .parts h3{font-size:13px;margin:10px 0 4px}.msg .parts pre{max-height:36em;overflow:auto}
.msg>summary .forms{margin-left:8px}.msg:not([open])>summary .forms{display:none}
details.fixed{border-top:1px solid var(--line);padding:3px 0}details.fixed>summary{cursor:pointer}
.mhead{display:flex;gap:8px;align-items:center}
.field{margin:6px 0}.fname{margin-bottom:2px}.fname code{color:var(--accent);font-size:11.5px;font-weight:600}
.fempty,.fend{color:var(--muted);font-size:12px;font-style:italic}
.framed pre{max-height:36em;overflow:auto;margin:0}
.calls .call{border-bottom:1px solid var(--line);padding:3px 0}
.calls .call>summary,.pbox details.source>summary{cursor:pointer}
.calls .about{color:var(--muted)}
.calls .call p{margin:4px 0 6px}
@media (max-width:640px){.r{grid-template-columns:56px minmax(0,1fr)}.r .n{display:none}}
""" + hides

  /** The script of a page: the views, the filters, and the follow of a session that runs (5.2). The page of a session
    * asks for the rows after its last `seq` each second, adds the new entries at the end, and places each exchange
    * after its entry again when the exchange file changed. A page that does not show the first entries (`skipped`)
    * keeps out each exchange before its first entry, and its counts add the entries that it does not show. When the
    * follow brings new entries or exchanges, the chat and the trajectory get their last items again (5.7).
    */
  private val Script: String = """(() => {
const body = document.body, rows = document.getElementById('rows');
document.querySelectorAll('button[data-view]').forEach(b => b.addEventListener('click', () => {
  body.classList.remove('view-card', 'view-scala', 'view-json');
  body.classList.add('view-' + b.dataset.view);
  document.querySelectorAll('button[data-view]').forEach(x => x.classList.toggle('on', x === b));
}));
const story = document.getElementById('story');
document.querySelectorAll('button[data-mode]').forEach(b => b.addEventListener('click', () => {
  body.classList.remove('mode-chat', 'mode-trajectory', 'mode-entries');
  body.classList.add('mode-' + b.dataset.mode);
  document.querySelectorAll('button[data-mode]').forEach(x => x.classList.toggle('on', x === b));
  if (story && b.dataset.mode !== 'entries')
    story.querySelectorAll('details.work').forEach(d => { d.open = b.dataset.mode === 'trajectory'; });
}));
document.querySelectorAll('button[data-tab]').forEach(b => b.addEventListener('click', () => {
  body.classList.remove('tab-chat', 'tab-request', 'tab-reply', 'tab-diff');
  body.classList.add('tab-' + b.dataset.tab);
  document.querySelectorAll('button[data-tab]').forEach(x => x.classList.toggle('on', x === b));
  const url = new URL(location.href);
  url.searchParams.set('tab', b.dataset.tab);
  history.replaceState(null, '', url);
  document.querySelectorAll('a[data-tabbed]').forEach(a => {
    const to = new URL(a.href);
    to.searchParams.set('tab', b.dataset.tab);
    a.href = to.pathname + to.search;
  });
}));
document.querySelectorAll('button[data-reply]').forEach(b => b.addEventListener('click', () => {
  const box = document.querySelector('.replies');
  if (!box) return;
  box.classList.remove('show-response', 'show-stream');
  box.classList.add('show-' + b.dataset.reply);
  document.querySelectorAll('button[data-reply]').forEach(x => x.classList.toggle('on', x === b));
  const copy = b.closest('.tools').querySelector('button[data-copy]');
  if (copy) copy.dataset.copy = b.dataset.reply === 'stream' ? 'reply-exact' : 'reply-merged';
}));
document.querySelectorAll('button[data-copy]').forEach(b => b.addEventListener('click', async () => {
  const source = document.getElementById(b.dataset.copy);
  if (!source) return;
  const label = b.dataset.label || b.textContent;
  b.dataset.label = label;
  try { await navigator.clipboard.writeText(source.textContent); b.textContent = 'Copied'; }
  catch (e) { b.textContent = 'The copy failed'; }
  setTimeout(() => { b.textContent = label; }, 1500);
}));
const against = document.getElementById('against');
if (against) against.addEventListener('change', () => {
  if (against.value) location.search = '?against=' + encodeURIComponent(against.value);
});
if (rows) rows.addEventListener('click', ev => {
  const b = ev.target.closest('button[data-v]');
  if (!b) return;
  const row = b.closest('.row');
  if (row.dataset.view === b.dataset.v) delete row.dataset.view; else row.dataset.view = b.dataset.v;
});
document.querySelectorAll('input[data-hide]').forEach(c =>
  c.addEventListener('change', () => body.classList.toggle('hide-' + c.dataset.hide, !c.checked)));
// The Indent switch of the Entries view (5.2). The rows are flat at the start, and the browser keeps the choice.
const indent = document.getElementById('indent');
if (indent) {
  try { indent.checked = localStorage.getItem('inspector.indent') === 'on'; } catch (e) { indent.checked = false; }
  body.classList.toggle('indent', indent.checked);
  indent.addEventListener('change', () => {
    body.classList.toggle('indent', indent.checked);
    try { localStorage.setItem('inspector.indent', indent.checked ? 'on' : 'off'); } catch (e) {}
  });
}
// The buttons in the items of the chat and the trajectory: the tabs of the box of a prompt, the buttons that switch the
// views of a tab or of a system message (5.8), the Copy button of a code block, and the button that opens a turn in
// the trajectory (5.7). The follow adds new items, so one listener on the page serves each button.
document.addEventListener('click', async ev => {
  const tab = ev.target.closest('button[data-ptab]');
  if (tab) {
    const box = tab.closest('.pbox');
    box.querySelectorAll('[data-pview]').forEach(v => { v.hidden = v.dataset.pview !== tab.dataset.ptab; });
    box.querySelectorAll('button[data-ptab]').forEach(x => x.classList.toggle('on', x === tab));
    return;
  }
  const form = ev.target.closest('button[data-form]');
  if (form) {
    ev.preventDefault();
    const view = form.closest('[data-forms], .pview');
    view.querySelectorAll('[data-form-view]').forEach(v => { v.hidden = v.dataset.formView !== form.dataset.form; });
    view.querySelectorAll('button[data-form]').forEach(x => x.classList.toggle('on', x === form));
    return;
  }
  const copy = ev.target.closest('button[data-copy-code]');
  if (copy) {
    const code = copy.closest('.md-code').querySelector('pre');
    try { await navigator.clipboard.writeText(code.textContent); copy.textContent = 'Copied'; }
    catch (e) { copy.textContent = 'The copy failed'; }
    setTimeout(() => { copy.textContent = 'Copy'; }, 1500);
    return;
  }
  const go = ev.target.closest('button[data-goto]');
  if (go) {
    const item = go.closest('.item');
    const mode = document.querySelector('button[data-mode="trajectory"]');
    if (mode) mode.click();
    if (item) item.scrollIntoView({block: 'start'});
  }
});
// The totals of the turns on the page, in the bar (5.7): the count, the time of the work, and the tokens.
const span = ms => ms < 1000 ? ms + ' ms' : ms < 60000 ? (ms / 1000).toFixed(1) + ' s'
  : Math.floor(ms / 60000) + ' min ' + Math.floor(ms % 60000 / 1000) + ' s';
const short = n => n < 1000 ? String(n) : n < 1e6 ? (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'k'
  : (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
const totals = () => {
  const out = document.getElementById('totals');
  if (!out || !story) return;
  const turns = [...story.querySelectorAll('.item.turn')];
  const sum = key => turns.reduce((a, t) => a + Number(t.dataset[key] || 0), 0);
  out.textContent = turns.length === 0 ? '' : ' · ' + turns.length + (turns.length === 1 ? ' turn' : ' turns') +
    ' · ' + span(sum('ms')) + ' of work · ' + short(sum('in')) + ' → ' + short(sum('out')) + ' tokens';
};
totals();
if (!rows || !rows.dataset.session) return;
const follow = document.getElementById('follow');
let last = Number(rows.dataset.last), size = Number(rows.dataset.size), busy = false;
const skipped = Number(rows.dataset.skipped || 0);
const count = (id, n) => { const e = document.getElementById(id); if (e) e.textContent = String(n); };
const place = x => {
  const after = Number(x.dataset.after);
  let anchor = null;
  for (const e of rows.querySelectorAll('.row.entry')) { if (Number(e.dataset.seq) <= after) anchor = e; else break; }
  if (!anchor) { if (!skipped) rows.prepend(x); return; }
  let at = anchor;
  while (at.nextElementSibling && at.nextElementSibling.classList.contains('exchange')) at = at.nextElementSibling;
  at.after(x);
};
// The items of the chat from the key of the last item, in place of the items from that key (5.7). A turn that the
// person opened stays open. The item of a prompt never changes, so the old item stays, with its box as it was (5.8).
const refreshStory = async () => {
  const items = [...story.querySelectorAll('.item')];
  const from = items.length ? Number(items[items.length - 1].dataset.key) : 0;
  const r = await fetch('/s/' + story.dataset.session + '/story?from=' + from + '&skipped=' + skipped,
    {cache: 'no-store'});
  if (!r.ok) return;
  const t = document.createElement('template');
  t.innerHTML = await r.text();
  const gone = items.filter(e => Number(e.dataset.key) >= from);
  const open = new Set(gone.filter(e => e.querySelector('details.work[open]')).map(e => e.dataset.key));
  const kept = new Map(gone.filter(e => e.dataset.prompt).map(e => [e.dataset.prompt, e]));
  gone.forEach(e => e.remove());
  const all = body.classList.contains('mode-trajectory');
  for (const e of [...t.content.children]) {
    const old = e.dataset.prompt && kept.get(e.dataset.prompt);
    if (old) { story.appendChild(old); continue; }
    if (all || open.has(e.dataset.key)) e.querySelectorAll('details.work').forEach(d => { d.open = true; });
    story.appendChild(e);
  }
  totals();
};
const tick = async () => {
  if (busy || !follow || !follow.checked) return;
  busy = true;
  try {
    const r = await fetch('/s/' + rows.dataset.session + '/after/' + last + '?size=' + size, {cache: 'no-store'});
    if (!r.ok) return;
    const t = document.createElement('template');
    t.innerHTML = await r.text();
    const end = window.innerHeight + window.scrollY >= document.body.scrollHeight - 40;
    const entries = t.content.getElementById('entries');
    let grew = false;
    if (entries) {
      for (const row of [...entries.children]) { rows.appendChild(row); grew = true; }
      last = Number(entries.dataset.last);
    }
    const xs = t.content.getElementById('exchanges');
    if (xs) {
      rows.querySelectorAll('.row.exchange').forEach(x => x.remove());
      count('count-exchanges', xs.children.length);
      for (const x of [...xs.children]) place(x);
      size = Number(xs.dataset.size);
      grew = true;
    }
    const status = document.getElementById('status');
    if (status && entries && entries.dataset.status) status.textContent = entries.dataset.status;
    const shown = rows.querySelectorAll('.row.entry').length;
    count('count-entries', skipped + shown);
    count('count-shown', shown);
    count('count-all', skipped + shown);
    if (grew && story) await refreshStory();
    if (grew && end) window.scrollTo(0, document.body.scrollHeight);
  } finally { busy = false; }
};
setInterval(tick, 1000);
})();"""
