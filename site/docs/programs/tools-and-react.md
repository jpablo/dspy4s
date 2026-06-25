# Tools & ReAct

A **tool** is a typed function the model can call. `ReAct` is the module that
lets a program use tools: it reasons, picks a tool, sees the result, and repeats
until it has an answer.

## Defining a tool

The simplest way to make a tool is to annotate a typed method with
`@description` and lift it with `ToolFunction.fromMethod`. The macro derives the
tool's name, description, and argument schema from the method:

```scala
--8<-- "learn/programming/Tools.scala:tools"
```

The argument names and types (`city: String`) become the tool's schema, which is
surfaced to the model so it knows how to call the tool.

## Building a ReAct agent

`ReAct` takes a base signature and a set of tools. It runs a reason-act loop up
to `maxIterations` times, then produces the signature's output:

```scala
--8<-- "learn/programming/Tools.scala:react-agent"
```

Like `ChainOfThought`, `ReAct` prepends a `reasoning` field to the output. The
full step-by-step trajectory is available on the prediction's `.raw`.

## Native function calling

By default, tools reach the model through the text protocol. If your model
supports native function calling, enable it at the adapter level and tools are
sent as a structured `tools` array instead:

```scala
--8<-- "learn/programming/Tools.scala:native-fc"
```

This is an adapter setting, not a `ReAct` setting: the agent loop is unchanged,
only how tool definitions are transmitted to the provider differs. It applies to
any program whose predictor declares tools, and is gated on the model reporting
function-calling support.

## When to use it

| You want | Reach for |
|---|---|
| The model to fetch data or take an action | `ReAct` with one or more tools |
| A tool from an existing typed method | `ToolFunction.fromMethod(method)` |
| Structured tool calls on a capable model | `ChatAdapter(useNativeFunctionCalling = true)` |

Next: [Composing programs](composing.md).
