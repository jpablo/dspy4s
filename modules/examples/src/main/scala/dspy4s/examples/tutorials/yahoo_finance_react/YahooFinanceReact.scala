/**
 * Financial Analysis with DSPy ReAct and Yahoo Finance News
 *
 * Source:   docs/docs/tutorials/yahoo_finance_react/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/yahoo_finance_react/index.md
 * Status:   translated (the DSPy core: the typed tools + the ReAct agent, snippets 2/3/4). The live
 *           market data is out of scope — there is no `yfinance` / LangChain bridge in dspy4s, so the
 *           tools return illustrative static quotes instead of fetching, and the LangChain
 *           `YahooFinanceNewsTool` → `Tool.from_langchain` conversion (snippet 1) is omitted.
 *
 * Python passes plain `def`s with docstrings as ReAct tools; the dspy4s analogue is
 * `ToolFunction.fromMethod` over an `@description`-annotated typed method (the macro derives the name,
 * description, and argument schema). A `dict`/`json.dumps` return becomes a `Schema`-deriving case class
 * (or `List` of them), lifted to the tool result automatically.
 */
package dspy4s.examples.tutorials.yahoo_finance_react

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.examples.Demo
import dspy4s.programs.ReAct
import dspy4s.programs.contracts.{ToolFunction, description}
import dspy4s.typed.Signature
import zio.blocks.schema.Schema

// ── Snippet 2 — the finance tools (typed methods; live data stubbed) ──
// | def get_stock_price(ticker: str) -> str: """Get current stock price and basic info."""
// | def compare_stocks(tickers: str) -> str: """Compare multiple stocks (comma-separated)."""
// --8<-- [start:finance-tools]
case class StockQuote(ticker: String, price: Double, change_percent: Double, company: String) derives Schema

object FinanceTools:
  // Illustrative static quotes — a real implementation would call yfinance here.
  private val quotes: Map[String, StockQuote] = Map(
    "AAPL"  -> StockQuote("AAPL", 229.87, 1.24, "Apple Inc."),
    "GOOGL" -> StockQuote("GOOGL", 178.12, -0.45, "Alphabet Inc."),
    "MSFT"  -> StockQuote("MSFT", 442.57, 0.83, "Microsoft Corporation"),
    "TSLA"  -> StockQuote("TSLA", 251.44, 3.10, "Tesla, Inc.")
  )

  @description("Get current stock price and basic info.")
  def get_stock_price(ticker: String): StockQuote =
    quotes.getOrElse(ticker.trim.toUpperCase, StockQuote(ticker.toUpperCase, 0.0, 0.0, s"Unknown ($ticker)"))

  @description("Compare multiple stocks (comma-separated).")
  def compare_stocks(tickers: String): List[StockQuote] =
    tickers.split(",").iterator.map(t => get_stock_price(t)).toList
// --8<-- [end:finance-tools]

object YahooFinanceReact:

  // ── Snippet 3 — the ReAct agent over the finance tools ──
  // | class FinancialAnalysisAgent(dspy.Module):
  // |     self.react = dspy.ReAct("financial_query -> analysis_response", tools=[...], max_iters=6)
  // |     def forward(self, financial_query): return self.react(financial_query=financial_query)
  // --8<-- [start:react-agent]
  final class FinancialAnalysisAgent:
    private val react = ReAct(
      baseSignature = Signature.fromString("financial_query -> analysis_response"),
      tools = Vector(
        ToolFunction.fromMethod(FinanceTools.get_stock_price),
        ToolFunction.fromMethod(FinanceTools.compare_stocks)
        // NOTE: the LangChain Yahoo Finance News tool has no dspy4s bridge and is omitted.
      ),
      maxIterations = 6
    )

    def forward(financialQuery: String)(using RuntimeContext): Either[DspyError, String] =
      react.apply((financial_query = financialQuery)).map(_.output.analysis_response)
  // --8<-- [end:react-agent]

  // ── Snippet 4 — the demo queries ──
  // | queries = ["What's the latest news about Apple (AAPL)...", "Compare AAPL, GOOGL, and MSFT performance", ...]
  val demoQueries: Vector[String] = Vector(
    "What's the latest news about Apple (AAPL) and how might it affect the stock price?",
    "Compare AAPL, GOOGL, and MSFT performance",
    "Find recent Tesla news and analyze sentiment"
  )

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.yahoo_finance_react.yahooFinanceReactMain"
@main def yahooFinanceReactMain(): Unit = Demo.withLm {
  val agent = new YahooFinanceReact.FinancialAnalysisAgent
  YahooFinanceReact.demoQueries.foreach { query =>
    println(s"Query: $query")
    println(s"Analysis: ${agent.forward(query)}")
    println("-" * 50)
  }
}
