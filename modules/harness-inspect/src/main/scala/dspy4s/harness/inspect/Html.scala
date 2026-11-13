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
  val predictors: List[String] = List("policy", "compress", "merge", "subQuery", "finisher", "none")

  private inline def caseNames[T](using m: Mirror.SumOf[T]): List[String] =
    constValueTuple[m.MirroredElemLabels].toList.map(String.valueOf)

  private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
  private val day   = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

  /** The time of `millis` on the clock of this machine, to the millisecond. */
  def time(millis: Long): String = clock.format(Instant.ofEpochMilli(millis))

  /** The day and the minute of `millis` on the clock of this machine. */
  def date(millis: Long): String = day.format(Instant.ofEpochMilli(millis))

  /** The links of the raw paths `items` under `base` (5.5): one group that a browser shows as text, and one group with
    * `download=1` that a browser saves as files. `base` must be escaped.
    */
  def rawLinks(base: String, items: Vector[String]): String =
    def links(query: String) = items.map(item => s"""<a href="$base/$item$query">${escape(item)}</a>""").mkString(" ")
    s"""<span class="group">Raw: ${links("")}</span><span class="group">Download: ${links("?download=1")}</span>"""

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
button{font:inherit;font-size:12px;border:1px solid var(--line);background:var(--card);color:var(--fg);
border-radius:5px;padding:1px 8px;cursor:pointer}
button.on{border-color:var(--accent);color:var(--accent)}
main{padding:8px 16px 48px;max-width:1100px}
.row{border-left:3px solid transparent;padding:6px 10px;margin:4px 0;background:var(--card);border-radius:6px;
border:1px solid var(--line)}
.depth-1{margin-left:18px}.depth-2{margin-left:36px}
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
.role-user{border-left:3px solid var(--good)}.role-assistant{border-left:3px solid var(--policy)}
.role-tool{border-left:3px solid var(--leaf)}
.code{font:12.5px/1.45 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;margin:4px 0;padding:8px 10px 8px 0;
background:var(--code);border-radius:6px}
.code .ln{display:grid;grid-template-columns:4.5em 1fr}
.code .n{color:var(--muted);text-align:right;padding-right:1.2em;user-select:none}
.code .c{white-space:pre-wrap;overflow-wrap:anywhere;padding-left:calc(var(--i) * 1ch + 4ch);text-indent:-4ch}
.tools{display:flex;gap:6px;justify-content:flex-end;margin:6px 0 2px}
a.button{font-size:12px;border:1px solid var(--line);background:var(--card);color:var(--fg);border-radius:5px;
padding:1px 8px;text-decoration:none}
.t-key{color:var(--leaf)}.t-str{color:var(--good)}.t-num{color:var(--warn)}.t-lit{color:var(--memory)}
.t-type{color:var(--accent)}
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
.kind-TurnStarted,.kind-TurnEnded{border-left:3px solid var(--accent)}
.kind-Input{border-left:3px solid var(--good)}
.kind-StopRequested,.kind-JobsStopRequested{border-left:3px solid var(--warn)}
.exchange{background:var(--x)}
.exchange .meta a.kind{color:var(--accent);text-decoration:underline;text-underline-offset:2px}
.exchange.pred-policy{border-left:3px solid var(--policy)}
.exchange.pred-compress,.exchange.pred-merge{border-left:3px solid var(--memory)}
.exchange.pred-subQuery{border-left:3px solid var(--leaf)}
.exchange.open .kind::after{content:" (open)";color:var(--warn)}
table{border-collapse:collapse;width:100%;font-size:13px}
th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}
th{color:var(--muted);font-weight:500}
td.first{overflow-wrap:anywhere}
.probes{font-size:12px;color:var(--muted)}
@media (max-width:640px){.depth-1{margin-left:8px}.depth-2{margin-left:16px}main{padding:8px 16px 40px}
.meta .views{margin-left:0}}
""" + hides

  /** The script of a page: the views, the filters, and the follow of a session that runs (5.2). The page of a session
    * asks for the rows after its last `seq` each second, adds the new entries at the end, and places each exchange
    * after its entry again when the exchange file changed.
    */
  private val Script: String = """(() => {
const body = document.body, rows = document.getElementById('rows');
document.querySelectorAll('button[data-view]').forEach(b => b.addEventListener('click', () => {
  body.classList.remove('view-card', 'view-scala', 'view-json');
  body.classList.add('view-' + b.dataset.view);
  document.querySelectorAll('button[data-view]').forEach(x => x.classList.toggle('on', x === b));
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
if (!rows || !rows.dataset.session) return;
const follow = document.getElementById('follow');
let last = Number(rows.dataset.last), size = Number(rows.dataset.size), busy = false;
const place = x => {
  const after = Number(x.dataset.after);
  let anchor = null;
  for (const e of rows.querySelectorAll('.row.entry')) { if (Number(e.dataset.seq) <= after) anchor = e; else break; }
  if (!anchor) { rows.prepend(x); return; }
  let at = anchor;
  while (at.nextElementSibling && at.nextElementSibling.classList.contains('exchange')) at = at.nextElementSibling;
  at.after(x);
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
      for (const x of [...xs.children]) place(x);
      size = Number(xs.dataset.size);
      grew = true;
    }
    const status = document.getElementById('status');
    if (status && entries && entries.dataset.status) status.textContent = entries.dataset.status;
    const count = (id, n) => { const e = document.getElementById(id); if (e) e.textContent = String(n); };
    count('count-entries', rows.querySelectorAll('.row.entry').length);
    count('count-exchanges', rows.querySelectorAll('.row.exchange').length);
    if (grew && end) window.scrollTo(0, document.body.scrollHeight);
  } finally { busy = false; }
};
setInterval(tick, 1000);
})();"""
