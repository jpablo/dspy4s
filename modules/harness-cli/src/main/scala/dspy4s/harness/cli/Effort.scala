package dspy4s.harness.cli

import dspy4s.harness.kernel.ModelChoice
import zio.blocks.schema.json.Json

/** The reasoning effort of the model of a session (CLI_SCREEN.md, 14.4): the provider option `reasoning_effort` in the
  * options of the model. Each call to the policy sends the options of the model, and the adapter of a model with native
  * reasoning takes the effort from them. The endpoint of the probe of 14.6 takes `none`, `minimal`, `low`, `medium`,
  * `high` and `xhigh`, and `none` turns the reasoning off.
  */
object Effort:

  /** The key of the effort in the options of a model. */
  val Key: String = "reasoning_effort"

  /** The variable of the effort of a new session. */
  val Variable: String = "DSPY_REASONING_EFFORT"

  /** The effort of a new session with native reasoning and no `DSPY_REASONING_EFFORT`: the default of the adapter. */
  val Default: String = "low"

  /** The level that turns the reasoning off. The list of an endpoint does not give it, so the client adds it (14.3). */
  val Off: String = "none"

  /** The levels of a model whose endpoint gives no list, apart from `Off`. */
  val Common: Vector[String] = Vector("low", "medium", "high")

  /** The effort in the options of `model`, or none. */
  def of(model: ModelChoice): Option[String] = model.options.get(Key).collect { case Json.String(level) => level }

  /** `model` with the effort `level`. The other options stay. */
  def set(model: ModelChoice, level: String): ModelChoice =
    model.copy(options = model.options.updated(Key, Json.String(level)))

  /** The options of the model of a new session of the variables `env`: the effort of `DSPY_REASONING_EFFORT`, else
    * `Default` when `DSPY_NATIVE_REASONING` is `true`, else no option. So the configuration holds the effort that the
    * requests send, and the footer shows it. The endpoint refuses a level that it does not know at the first call.
    */
  def startOptions(env: Map[String, String]): Map[String, Json] =
    val stated = env.get(Variable).map(_.trim).filter(_.nonEmpty)
    val native = env.get("DSPY_NATIVE_REASONING").contains("true")
    stated.orElse(Option.when(native)(Default)).fold(Map.empty[String, Json])(level => Map(Key -> Json.String(level)))
