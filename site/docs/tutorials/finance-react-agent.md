# Finance ReAct Agent

A `ReAct` agent that answers financial queries by calling typed tools. It demonstrates how to expose annotated Scala methods as ReAct tools and wire them into an agent that reasons over the results.

## Defining the tools

```scala
--8<-- "tutorials/yahoo_finance_react/YahooFinanceReact.scala:finance-tools"
```

Each tool is a plain method annotated with `@description`. The return type is a `Schema`-deriving case class (or a `List` of them), so the tool result is structured rather than a raw string. `StockQuote` derives `Schema`, which lets the framework serialize the value back to the agent. Here the quotes are static, standing in for a live data source.

## Building the agent

```scala
--8<-- "tutorials/yahoo_finance_react/YahooFinanceReact.scala:react-agent"
```

`ReAct` takes a base signature (`financial_query -> analysis_response`), the set of tools, and a maximum number of reasoning iterations. `ToolFunction.fromMethod` turns each annotated method into a tool: the macro derives the tool name, its description, and its argument schema from the method signature. `forward` runs the agent for one query and returns the `analysis_response` field, threading a `RuntimeContext` that supplies the language model.

## Running it

```bash
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.yahoo_finance_react.yahooFinanceReactMain"
```

## Notes

Live market data is out of scope. The tools return static illustrative quotes
rather than fetching real prices, so the example runs without any market-data
connection.

Full source: [YahooFinanceReact.scala](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/tutorials/yahoo_finance_react/YahooFinanceReact.scala)
