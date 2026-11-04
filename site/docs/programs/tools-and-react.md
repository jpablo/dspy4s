# Tools & ReAct

A **tool** is a callable operation the model can invoke. `ReAct` is the module that
lets a program use tools: it reasons, picks a tool, sees the result, and repeats
until it has an answer.

## Defining a tool

The simplest way to make a tool is to annotate a method with
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

With the default `ChatAdapter`, tools reach the model through the text protocol.
If your model supports native function calling, enable it at the adapter level
and tools are sent as a structured `tools` array instead. `JSONAdapter` has this
setting on by default, and `NativeTools(enabled = false)` turns it off:

```scala
--8<-- "learn/programming/Tools.scala:native-fc"
```

This is an adapter setting, not a `ReAct` setting: the agent loop is unchanged,
only how tool definitions are transmitted to the provider differs. It applies to
any program whose predictor declares tools, and is gated on the model reporting
function-calling support.

A `Predict` with `tools` and an output of the type `Vector[ToolCall]` has the same
rule. When native function calling is active, the provider gets the tools. Else
the prompt shows each tool as an input field `tools`, and the model writes the
calls as JSON text. With the setting on, such an output with no tools is a
`ConfigurationError`. Give the tools in the `tools` of the predictor. An input
field of the type `tools` holds only text, so it cannot become provider tools.
It gives tools only when native function calling is not active. When it is
active, such an input with no `tools` of the predictor is also this error.

## When to use it

| You want | Reach for |
|---|---|
| The model to fetch data or take an action | `ReAct` with one or more tools |
| A tool from an existing method | `ToolFunction.fromMethod(method)` |
| Structured tool calls on a capable model | `ChatAdapter(native = NativeTools(enabled = true))` |

Next: [Composing programs](composing.md).
