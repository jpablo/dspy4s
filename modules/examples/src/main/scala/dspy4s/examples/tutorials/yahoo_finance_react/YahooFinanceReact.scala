/** Financial Analysis with DSPy ReAct and Yahoo Finance News
  *
  * Source: docs/docs/tutorials/yahoo_finance_react/index.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/yahoo_finance_react/index.md Status: translated.
  *
  * Live market data is replaced with static fixtures. The functional ReAct constructor receives a generator, an
  * explicit tool-invocation capability, and an extractor.
  */
package dspy4s.examples.tutorials.yahoo_finance_react

import dspy4s.core.contracts.{DspyError, DynamicValues, TypeRef}
import dspy4s.examples.Demo
import dspy4s.programs.contracts.{Tool, ToolCallRequest}
import dspy4s.programs.*
import dspy4s.signatures.Signature
import zio.ZEnvironment
import zio.blocks.schema.Schema

// --8<-- [start:finance-tools]
final case class StockQuote(ticker: String, price: Double, changePercent: Double, company: String) derives Schema

object FinanceTools:
  private val quotes: Map[String, StockQuote] = Map(
    "AAPL"  -> StockQuote("AAPL", 229.87, 1.24, "Apple Inc."),
    "GOOGL" -> StockQuote("GOOGL", 178.12, -0.45, "Alphabet Inc."),
    "MSFT"  -> StockQuote("MSFT", 442.57, 0.83, "Microsoft Corporation"),
    "TSLA"  -> StockQuote("TSLA", 251.44, 3.10, "Tesla, Inc.")
  )

  private def quote(ticker: String): StockQuote =
    quotes.getOrElse(ticker.trim.toUpperCase, StockQuote(ticker.toUpperCase, 0.0, 0.0, s"Unknown ($ticker)"))

  val getStockPrice: Tool = Tool.fromEither(
    "get_stock_price",
    "Get current stock price and basic information.",
    Vector("ticker" -> TypeRef.string)
  )(args =>
    DynamicValues.requireString(args, "ticker", "get_stock_price")
      .map(ticker => DynamicValues.fromAny(quote(ticker)))
  )

  val compareStocks: Tool = Tool.fromEither(
    "compare_stocks",
    "Compare multiple comma-separated stock tickers.",
    Vector("tickers" -> TypeRef.string)
  )(args =>
    DynamicValues.requireString(args, "tickers", "compare_stocks")
      .map(tickers => DynamicValues.fromAny(tickers.split(",").iterator.map(quote).toList))
  )
// --8<-- [end:finance-tools]

final case class FinancialQuery(financialQuery: String) derives Schema
final case class FinancialAnswer(analysisResponse: String) derives Schema
final case class FinancialExtractInput(financialQuery: String, trajectory: String) derives Schema

object YahooFinanceReact:

  // --8<-- [start:react-agent]
  private val generator = Program.lift[ReAct.StepInput[FinancialQuery], ReAct.Step] { input =>
    if input.trajectory.nonEmpty then ReAct.Step("The data is available.", ReAct.Action.Finish())
    else
      val query   = input.input.financialQuery
      val compare = query.toLowerCase.contains("compare")
      val args    = DynamicValues.recordFromEntries(Seq(
        (if compare then "tickers" else "ticker") -> DynamicValues.fromAny(
          if compare then "AAPL,GOOGL,MSFT" else if query.toUpperCase.contains("TSLA") then "TSLA" else "AAPL"
        )
      ))
      val tool = if compare then "compare_stocks" else "get_stock_price"
      ReAct.Step("Collect market data before analysis.", ReAct.Action.Invoke(ToolCallRequest(tool, args)))
  }

  private val extractor = Program
    .predict(
      Signature.derived[FinancialExtractInput, FinancialAnswer](
        "FinancialAnalysis",
        "Answer the financial query from the tool trajectory. State that fixture prices are illustrative."
      )
    )
    .contramap[ReAct.ExtractInput[FinancialQuery]](input =>
      FinancialExtractInput(input.input.financialQuery, input.trajectory.mkString("\n"))
    )

  val agent = ReAct(generator, Program.invokeTool, extractor, maxIterations = 6)

  def run(financialQuery: String)(using backend: PredictionBackend): Either[DspyError, String] =
    val tools: ToolBackend = new LiveToolBackend(Vector(FinanceTools.getStockPrice, FinanceTools.compareStocks))
    val environment        = ZEnvironment[PredictionBackend](backend) ++ ZEnvironment[ToolBackend](tools)
    Demo.runWith(agent, FinancialQuery(financialQuery), environment).map(_.output.analysisResponse)
  // --8<-- [end:react-agent]

  val demoQueries: Vector[String] = Vector(
    "What is the latest Apple (AAPL) price and what might it mean?",
    "Compare AAPL, GOOGL, and MSFT performance",
    "Analyze Tesla (TSLA) sentiment"
  )

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.yahoo_finance_react.yahooFinanceReactMain"
@main def yahooFinanceReactMain(): Unit =
  Demo.withLm {
    YahooFinanceReact.demoQueries.foreach { query =>
      println(s"Query: $query")
      println(s"Analysis: ${YahooFinanceReact.run(query)}")
      println("-" * 50)
    }
  }
