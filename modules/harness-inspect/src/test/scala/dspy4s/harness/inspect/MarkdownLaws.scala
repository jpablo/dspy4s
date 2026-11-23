package dspy4s.harness.inspect

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import scala.annotation.tailrec

import InspectorLaws.Markup

/** The laws of the Markdown of the chat view (docs/code-harness/OBSERVABILITY.md, 5.7 and L25). */
class MarkdownLaws extends ScalaCheckSuite:

  // ── Helpers ──

  private val Tag = """<(/?)([a-z0-9]+)((?: [a-z-]+(?:="[^"<>]*")?)*)>""".r

  /** The problems of `html`: a `<` or a `>` that is not a part of a tag of `Markdown.Tags`, a tag that does not close,
    * or a link whose target is not safe. An empty result means that the HTML is good.
    */
  private def problems(html: String): Vector[String] =
    @tailrec def scan(i: Int, open: List[String], found: Vector[String]): Vector[String] =
      if i >= html.length then found ++ open.map(t => s"<$t> does not close")
      else if html(i) == '>' then scan(i + 1, open, found :+ s"a bare > at $i")
      else if html(i) != '<' then scan(i + 1, open, found)
      else
        Tag.findPrefixMatchOf(html.substring(i)) match
          case None    => scan(i + 1, open, found :+ s"a bare < at $i: ${html.substring(i).take(40)}")
          case Some(m) =>
            val (closing, name, attrs) = (m.group(1) == "/", m.group(2), m.group(3))
            val bad                    = Vector(
              Option.when(!Markdown.Tags(name))(s"the tag $name"),
              """href="([^"]*)"""".r.findFirstMatchIn(attrs).map(_.group(1)).filterNot(Markdown.safe)
                .map(t => s"the link $t")
            ).flatten
            val next = i + m.end
            if name == "br" || name == "hr" then scan(next, open, found ++ bad)
            else if !closing then scan(next, name :: open, found ++ bad)
            else
              open match
                case `name` :: rest => scan(next, rest, found ++ bad)
                case _              => scan(next, open, found ++ bad :+ s"</$name> closes ${open.headOption}")
    scan(0, Nil, Vector.empty)

  private val pieces: Gen[String] = Gen.oneOf(
    Vector(
      "**",
      "*",
      "_",
      "__",
      "~~",
      "`",
      "``",
      "```",
      "```scala",
      "\n",
      "\n\n",
      " ",
      "  ",
      "a",
      "b c",
      "snake_case",
      "[",
      "]",
      "(",
      ")",
      "[x](https://example.com/a_(b))",
      "[y](javascript:alert(1))",
      "[z](site/docs/x.md)",
      "https://example.com/p.",
      "> ",
      "- ",
      "  - ",
      "1. ",
      "3) ",
      "# ",
      "### ",
      "---",
      "|",
      "| a | b |\n|---|:-:|\n",
      "\\*",
      "\\",
      "<",
      ">",
      "&",
      "\"",
      "'",
      Markup,
      "<script>alert(1)</script>",
      "~~~",
      "\t"
    )
  )

  private val texts: Gen[String] = Gen.listOf(pieces).map(_.mkString)

  // ── L25 ──

  property("Markdown L25: any text gives only the tags of the renderer, each one closed, and only safe links") {
    forAll(texts) { text =>
      val html = Markdown.html(text)
      assertEquals(problems(html), Vector.empty, s"text: ${text.take(300)}\nhtml: ${html.take(600)}")
      assert(!html.contains(Markup) && !html.contains("<script"), html)
    }
  }

  property("Markdown L31: any text gives only inline tags of the renderer as one line, each one closed") {
    forAll(texts) { text =>
      val line = Markdown.inline(text)
      assertEquals(problems(line), Vector.empty, s"text: ${text.take(300)}\nhtml: ${line.take(600)}")
      assert(!line.contains(Markup) && !line.contains("<script"), line)
      // A line has no block: no paragraph, heading, list, quote, table or fence.
      Vector("<p>", "<h", "<ul>", "<ol>", "<li>", "<blockquote>", "<table", "<div", "<pre", "<br>").foreach(tag =>
        assert(!line.contains(tag), s"$tag in $line")
      )
    }
  }

  test("Markdown L31: a line shows its inline parts, and a heading mark at its start goes away") {
    assertEquals(
      Markdown.inline("I **read** the `file` and *x*, see [a](https://example.com)."),
      "I <strong>read</strong> the <code>file</code> and <em>x</em>, see " +
        """<a href="https://example.com" rel="noopener noreferrer" target="_blank">a</a>."""
    )
    assertEquals(Markdown.inline("## The answer"), "The answer")
    assertEquals(Markdown.inline("a < b & **open"), "a &lt; b &amp; **open")
  }

  test("Markdown L25: a code fence keeps each line of its code, escaped, with its language and its colors") {
    val html = Markdown.html("Before:\n\n```scala\nval x = 1 < 2 && **y**\n  indented\n```\nAfter")
    assertEquals(
      html,
      "<p>Before:</p>" +
        """<div class="md-code"><div class="md-code-head"><span>scala</span><button data-copy-code>Copy</button>""" +
        """</div><pre><code><span class="t-keyword">val</span> <span class="t-def">x</span> = """ +
        """<span class="t-num">1</span> &lt; <span class="t-num">2</span> &amp;&amp; **y**""" +
        "\n  indented</code></pre></div><p>After</p>"
    )
    // Only a fence of Scala gets the colors of Scala (L26).
    assert(Markdown.html("```text\nval x = 1\n```").contains("<pre><code>val x = 1</code></pre>"))
    // A fence with no end holds the rest of the text.
    assert(Markdown.html("```\nopen\n*a*").endsWith("<pre><code>open\n*a*</code></pre></div>"))
  }

  test("Markdown L25: the inline parts, and a mark in a word or with no end stays text") {
    assertEquals(
      Markdown.html("a **b** *c* _d_ ~~e~~ `f *g*` snake_case_name 2 * 3 * 4 **open"),
      "<p>a <strong>b</strong> <em>c</em> <em>d</em> <del>e</del> <code>f *g*</code> snake_case_name " +
        "2 * 3 * 4 **open</p>"
    )
    assertEquals(Markdown.html("``a ` b``"), "<p><code>a ` b</code></p>")
    // An underscore after a letter opens no emphasis, also when a closing underscore comes later.
    assertEquals(Markdown.html("file_name_ and _x_"), "<p>file_name_ and <em>x</em></p>")
    assertEquals(Markdown.html("\\*not em\\*"), "<p>*not em*</p>")
    assertEquals(Markdown.html("line 1\nline 2\n\nnext"), "<p>line 1<br>line 2</p><p>next</p>")
  }

  test("Markdown L25: only a link to https, http or mailto gets an href") {
    assertEquals(
      Markdown.html("[docs](https://example.com/a_(b)) and https://example.com/x."),
      """<p><a href="https://example.com/a_(b)" rel="noopener noreferrer" target="_blank">docs</a> and """ +
        """<a href="https://example.com/x" rel="noopener noreferrer" target="_blank">https://example.com/x</a>.</p>"""
    )
    assertEquals(
      Markdown.html("[x](javascript:alert(1)) [Configuring](site/docs/x.md)"),
      """<p><span class="md-link" title="javascript:alert(1)">x</span> """ +
        """<span class="md-link" title="site/docs/x.md">Configuring</span></p>"""
    )
    // A reference of the memory is no link.
    assertEquals(Markdown.html("[0.13] The turn ended."), "<p>[0.13] The turn ended.</p>")
  }

  test("Markdown L25: the lists, with a nested list and the start of an ordered list") {
    assertEquals(
      Markdown.html("- a\n- b\n  - c\n\n3. x\n4. y"),
      "<ul><li><p>a</p></li><li><p>b</p><ul><li><p>c</p></li></ul></li></ul>" +
        """<ol start="3"><li><p>x</p></li><li><p>y</p></li></ol>"""
    )
    // A list item ends a paragraph.
    assertEquals(Markdown.html("Steps:\n1. one"), "<p>Steps:</p><ol><li><p>one</p></li></ol>")
  }

  test("Markdown L25: a table, a heading, a rule and a quote") {
    assertEquals(
      Markdown.html("| a | b |\n|---|--:|\n| `1` | 2 |"),
      """<div class="md-table"><table><thead><tr><th>a</th><th style="text-align:right">b</th></tr></thead>""" +
        """<tbody><tr><td><code>1</code></td><td style="text-align:right">2</td></tr></tbody></table></div>"""
    )
    assertEquals(
      Markdown.html("# Title\n## Part\n---\n> quoted *text*"),
      "<h3>Title</h3><h4>Part</h4><hr><blockquote><p>quoted <em>text</em></p></blockquote>"
    )
  }

  test("Markdown L25: a text with markup shows the markup as text") {
    val html = Markdown.html(s"$Markup\n\n**$Markup**")
    assert(!html.contains(Markup), html)
    assertEquals(html, s"<p>${Html.escape(Markup)}</p><p><strong>${Html.escape(Markup)}</strong></p>")
  }
