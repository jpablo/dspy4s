# The Type Bridge: Scala types ↔ LM-visible types

> How dspy4s translates between Scala's rich nominal type system and the LM's tiny vocabulary of wire-format
> primitives, and what that means for what you can put on the Scala side.

dspy4s sits between two type systems that don't speak the same language:

  - **Scala** has case classes, sealed traits, enums, generics, opaque types, named tuples — all the structure
    you'd expect from a statically-typed language.
  - **The LM** sees text. Adapters package that text with hints like `(bool)` or a JSON schema, but the LM
    fundamentally produces strings.

The job of the type bridge is to project rich Scala types down into a small wire-format vocabulary on the way
out, and lift them back up on the way home.

## Three layers

For any field in a signature, there are three "types" in play:

| Layer | What it stores | Owner |
|---|---|---|
| **Scala type** | The static type in your code (e.g. `Sentiment`, `List[Citation]`, `Boolean`) | You |
| **Codec** (`Shape[A]` + `FieldCodec[A]`) | The encode / decode logic that turns Scala values into a `Map[String, Any]` and back | `dspy4s.typed` |
| **Wire format** (`TypeRef` + `FieldSpec.metadata`) | The label / hint the LM sees in the prompt | `dspy4s.core.contracts` |

The **codec** is the funnel: it knows how to take your `Sentiment.joy` and produce the string `"joy"` that ships
over the wire, then take a `"joy"` back from the LM and decode it into `Sentiment.joy` again. The **wire
format** is the small fixed vocabulary the codec funnels into.

## The wire-format vocabulary

There are exactly five well-known `TypeRef`s (in `core/contracts/SignatureLayout.scala`):

| `TypeRef` | What the LM sees | Typical Scala-side types |
|---|---|---|
| `string` | a free-form string | `String`, Scala enums (flat-encoded to a case name) |
| `int` | an integer | `Int`, `Long` |
| `double` | a number | `Double`, `Float` |
| `bool` | `true` / `false` | `Boolean` |
| `json` | a JSON value (object, array, scalar) | case classes, `List`, `Map`, `Option`, nested ADTs |

Anything outside this set passes through as an opaque `TypeRef(other)` and adapters fall back to treating it as
a string. That's intentional — five primitives covers the LM-shaped vocabulary cleanly; richer types live on
the Scala side.

`TypeRef` is the *label* the adapter shows the LM, not the *Scala type*. A Scala enum has Scala type `Sentiment`
but `TypeRef.string` at the wire, because at the LM level it's just a string token like `"joy"`.

## What adapters do with `TypeRef`

`TypeRef` is what each adapter renders into its own prompt grammar:

  - **`ChatAdapter`** writes parenthetical type hints next to the field name:
    ```
    [[ ## toxic ## ]]
    {toxic} (bool)
    ```
  - **`JSONAdapter`** emits a JSON schema where `bool` becomes `"type": "boolean"`, `int` becomes
    `"type": "integer"`, etc.
  - **`XMLAdapter`** uses attribute-form annotations on the field tags.

So three adapters, three different on-the-wire syntaxes — same `TypeRef` value driving all of them.

## What you can put on the Scala side

Pretty much any type, as long as it has a codec. The wire vocabulary is fixed; the Scala side isn't.

```scala
// 1. Directly maps to a wire primitive
def answer: OutputField[String]   // TypeRef.string
def count:  OutputField[Int]      // TypeRef.int
def score:  OutputField[Double]   // TypeRef.double
def ok:     OutputField[Boolean]  // TypeRef.bool

// 2. Scala enum — flattened to a string
enum Sentiment { case sadness, joy, love }
object Sentiment extends FieldCodec.FlatEnum[Sentiment]
def sentiment: OutputField[Sentiment]
//   Scala type: Sentiment
//   Wire type:  TypeRef.string
//   LM sees:    "joy" (with "allowed values: sadness, joy, love" hint from metadata)

// 3. Collections / Option
def tags: OutputField[List[String]]              // wire: TypeRef.json (a JSON array)
def meta: OutputField[Map[String, Int]]          // wire: TypeRef.json (a JSON object)
def note: OutputField[Option[String]]            // wire: TypeRef.json (string-or-null)

// 4. Case classes (need kyo.Schema in scope; auto-derived for common shapes)
case class Citation(title: String, score: Double) derives kyo.Schema
def cite: OutputField[Citation]
//   Scala type: Citation
//   Wire type:  TypeRef.json
//   LM sees:    {"title": "...", "score": 0.9}

// 5. Nested combinations
case class Classification(sentiment: Sentiment, citations: List[Citation]) derives kyo.Schema
def result: OutputField[Classification]
//   Scala type: Classification
//   Wire type:  TypeRef.json
//   LM sees:    {"sentiment": "joy", "citations": [{"title": "...", "score": 0.9}, ...]}
```

You are not restricted to "primitives only" on the Scala side.

## The actual constraint

It's not "primitives only" — it's "**there must be a `FieldCodec[A]` or `kyo.Schema[A]` for it**." Concretely:

| Type shape | What's needed | Out of the box? |
|---|---|---|
| `String`, `Int`, `Double`, `Boolean` | Built-in `FieldCodec` instances | yes |
| Scala enum | `object E extends FieldCodec.FlatEnum[E]` (one line) | yes (with the boilerplate) |
| Case class | `kyo.Schema[A]` (auto-derived if all fields are supported) | usually |
| `List[A]` / `Vector[A]` / `Set[A]` / `Map[K, V]` / `Option[A]` | Built-in codecs, recurse into element types | yes |
| Sealed-trait ADT with multiple variants | `kyo.Schema` derivation | usually |
| Literal-union types (`"a" \| "b"`) | Not yet — use an enum instead | no |
| Function types, opaque types without a codec, anything that can't round-trip through JSON | Write a custom `FieldCodec` | no, until you write one |

The escape hatch for unsupported types: define `given FieldCodec[YourType] = ...` and the typed surface picks
it up.

## A concrete walkthrough

Take a `Sentiment` enum and trace what happens at each layer:

```scala
enum Sentiment:
  case sadness, joy, love
object Sentiment extends FieldCodec.FlatEnum[Sentiment]

trait Classify extends Spec:
  def sentence:  InputField[String]
  def sentiment: OutputField[Sentiment]

val sig = Signature.of[Classify]
```

The macro that derives `Signature.of[Classify]` produces a `Signature[I, O]` whose `sentiment` field carries:

```
FieldSpec(
  name     = "sentiment",
  role     = Output,
  typeRef  = TypeRef.string,       // wire-format: LM sees a string
  metadata = Map(
    "enum.cases" -> "sadness,joy,love",
    "enum.name"  -> "Sentiment"
  )
)
```

When `Predict[I, O].run(input)` fires, here's the round-trip:

```
┌────────────────────────── encode (Scala → wire) ─────────────────────────────┐
                                                                                
  Scala value     Shape / FieldCodec     Map[String, Any]    Adapter prompt    
                                                                                
  Sentence("Best   ──→ TupleShape ─→     Map(                ──→ ChatAdapter ──→
   movie ever!")    + StringCodec         "sentence" -> ...                     
                                          )                                     
                                                                                
                                                              [[ ## sentence ## ]]
                                                              Best movie ever!  
                                                                                
                                                              [[ ## sentiment ## ]]
                                                              {sentiment} (str) 
                                                              (allowed: sadness, joy, love)
                                                                                
└─────────────────────────────────────────────────────────────────────────────-┘

                              [LM produces: "joy"]

┌────────────────────────── decode (wire → Scala) ─────────────────────────────┐
                                                                                
  Adapter parse    Map[String, Any]    Shape / FieldCodec    Scala value       
                                                                                
  "joy" ──→        Map(             ──→ TupleShape +      ──→ (sentiment =     
                     "sentiment"        FlatEnum decoder       Sentiment.joy)  
                       -> "joy"                                                 
                   )                                                            
                                                                                
└─────────────────────────────────────────────────────────────────────────────-┘
```

The `Sentiment` enum is invisible to the LM — it sees `"joy"` and the "allowed values" hint. The `Sentiment.joy`
case ref only exists on the Scala side, before encode and after decode. `TypeRef` and `metadata` are how the
adapter tells the LM "this slot accepts a string, and these are the legal ones."

## Why is the bridge needed at all

If `TypeRef` were the Scala type, every adapter would need to know about every Scala type in your project. The
LM doesn't care about your `Sentiment` enum — it cares whether to emit a string, a number, a JSON blob.
`TypeRef`'s tiny vocabulary is the **common denominator across adapters and LM providers**. The richness of
"but this string must be one of these enum cases" lives separately in `metadata`.

This split — small fixed external vocabulary + rich Scala types funnelling into it via codecs — is the standard
shape of any Scala ↔ external-system interop layer.

## The same pattern in other libraries

| Layer | Circe (Scala ↔ JSON) | Doobie (Scala ↔ SQL) | dspy4s (Scala ↔ LM) |
|---|---|---|---|
| **Codec** | `Encoder[A]` / `Decoder[A]` | `Get[A]` / `Put[A]` | `Shape[A]` + `FieldCodec[A]` |
| **External primitive vocabulary** | JSON types (string, number, bool, null, array, object) | SQL column types (`VARCHAR`, `INT`, `JSONB`, …) | `TypeRef.{string, int, double, bool, json}` |
| **Schema / metadata** | Implicit (JSON is self-describing per value) | DDL schema, `Meta[A]` for type hints | `FieldSpec.typeRef` + `FieldSpec.metadata` shown in prompt |
| **Derivation** | `circe-generic` (Mirror-based) | Doobie `derive`, sql-typed | kyo-schema + dspy4s macros |

dspy4s is the LM-call instance of the same family of problem.

## Where the LM case is its own thing

A few wrinkles that don't have direct equivalents in JSON / SQL:

1. **The "schema" is a runtime prompt fragment.** Circe schemas live in code; Doobie schemas live in the
   database. dspy4s ships its `TypeRef` and metadata *into the LM at call time* as part of the prompt text. So
   changing a `TypeRef` doesn't just change how you parse — it changes what the LM is told to produce.

2. **The other side is non-deterministic.** A SQL driver returning the wrong type for a column is a bug. An LM
   returning `"true!"` when you asked for a bool is a Tuesday. So dspy4s has to do **post-hoc coercive parsing**
   ([[DynamicPrediction.asBoolean]], `FieldCodec` decoders that accept either the typed value or the string
   form) that JSON / SQL libraries don't bother with.

3. **Allowed-values enforcement is hint-only.** For a Scala enum field you'd send `enum.cases = "joy,sadness"`
   to the LM in the prompt and the LM tries to honor it; then you validate on decode because it might still
   produce `"happy"`. With JSON Schema or SQL `CHECK` constraints the external system enforces; for LMs you can
   only beg.

4. **One `TypeRef`, multiple adapter renderings.** `ChatAdapter`, `JSONAdapter`, and `XMLAdapter` each project
   the same `TypeRef.bool` into their own grammar. The codec is the same; the wire format differs. That's why
   `TypeRef` is a *label*, not a *schema* — each adapter projects it into its own dialect.

## Writing a custom codec

When you have a Scala type that doesn't fall into the supported shapes above, write your own `FieldCodec[A]`
and put it in implicit scope:

```scala
final case class UserId(value: String)

given FieldCodec[UserId] with
  val typeRef: TypeRef = TypeRef.string
  def encode(value: UserId): Any = value.value
  def decode(raw: Any): Either[DspyError, UserId] = raw match
    case s: String => Right(UserId(s))
    case other     => Left(ValidationError(s"UserId must be a String, got: $other"))
```

Now `OutputField[UserId]` works in a trait spec or builder, and the LM sees a plain string on the wire.

## Where to look next

- [TYPED_SIGNATURES_GUIDE.md](TYPED_SIGNATURES_GUIDE.md) — the user-facing guide to defining signatures.
- [ARCHITECTURE.md](ARCHITECTURE.md) — how the typed and dynamic layers fit together at the module level.
- `modules/typed/src/main/scala/dspy4s/typed/Shape.scala` — the three `Shape` implementations
  (`KyoProductShape`, `TupleShape`, `MapShape`).
- `modules/typed/src/main/scala/dspy4s/typed/FieldCodec.scala` — the codec typeclass, the `FlatEnum` helper, and
  the built-in primitive instances.
- `modules/core/src/main/scala/dspy4s/core/contracts/SignatureLayout.scala` — where `TypeRef`, `FieldSpec`, and
  `FieldMetadata` live.
