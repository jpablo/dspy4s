/**
 * Building a Creative Text-Based AI Game with DSPy
 *
 * Source:   docs/docs/tutorials/ai_text_game/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/ai_text_game/index.md
 * Status:   translated (the DSPy core: the three signatures + the GameAI module composing them, from
 *           snippets 1/2). The rest of the tutorial is plain game plumbing around DSPy and is out of
 *           scope: the `Player` / `GameContext` save/load JSON (snippet 1), and the console rendering,
 *           menus, character creation, and `input()` game loop (snippets 3/4). Minimal `Player` /
 *           `GameContext` carriers are kept so the formatted-string inputs match the originals.
 *
 * `dspy.Signature` classes become `Spec` traits; `list[str]` → `OutputField[List[String]]`,
 * `dict[str, int]` → `OutputField[Map[String, Int]]`, `bool`/`int` map directly. The `GameAI`
 * `dspy.Module` (three `ChainOfThought`s) becomes a plain class threading their typed outputs.
 */
package dspy4s.examples.tutorials.ai_text_game

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.examples.Demo
import dspy4s.programs.ChainOfThought
import dspy4s.typed.{InputField, OutputField, Signature, Spec}

// ── Snippet 2 — the three generation signatures (top-level traits for Mirror derivation) ──
// | class StoryGenerator(dspy.Signature): """..."""
// --8<-- [start:signatures]
trait StoryGenerator extends Spec:
  def location:       InputField[String]
  def player_info:    InputField[String]
  def story_progress: InputField[Int]
  def recent_actions: InputField[String]
  def scene_description: OutputField[String]
  def available_actions: OutputField[List[String]]
  def npcs_present:       OutputField[List[String]]
  def items_available:    OutputField[List[String]]
// --8<-- [end:signatures]

// | class DialogueGenerator(dspy.Signature): """..."""
trait DialogueGenerator extends Spec:
  def npc_name:        InputField[String]
  def npc_personality: InputField[String]
  def player_input:    InputField[String]
  def context:         InputField[String]
  def npc_response:         OutputField[String]
  def mood_change:          OutputField[String]
  def quest_offered:        OutputField[Boolean]
  def information_revealed: OutputField[String]

// | class ActionResolver(dspy.Signature): """..."""
trait ActionResolver extends Spec:
  def action:       InputField[String]
  def player_stats: InputField[String]
  def context:      InputField[String]
  def difficulty:   InputField[String]
  def success:             OutputField[Boolean]
  def outcome_description: OutputField[String]
  def stat_changes:        OutputField[Map[String, Int]]
  def items_gained:        OutputField[List[String]]
  def experience_gained:   OutputField[Int]

/** Minimal carriers for the game state Python keeps in `Player` / `GameContext` dataclasses — enough to
  * build the formatted-string inputs the signatures take. (The save/load JSON is out of scope.) */
case class Player(name: String, level: Int = 1, health: Int = 100, skills: List[String] = List("perception")):
  def info: String  = s"Level $level $name, Health: $health, Skills: ${skills.mkString(", ")}"
  def stats: String = s"Level $level, Health $health, Skills: ${skills.mkString(", ")}"

case class GameContext(currentLocation: String, storyProgress: Int = 0):
  def summary: String = s"Location: $currentLocation, Story progress: $storyProgress"

/** Typed results, replacing Python's `dict` returns. */
case class Scene(description: String, actions: List[String], npcs: List[String], items: List[String])
case class Dialogue(response: String, mood: String, quest: Boolean, info: String)
case class ActionOutcome(success: Boolean, description: String, statChanges: Map[String, Int],
    items: List[String], experience: Int)

object AiTextGame:

  // ── Snippet 2 — the GameAI module (a dspy.Module → a plain class) ──
  // | class GameAI(dspy.Module):
  // |     self.story_gen = dspy.ChainOfThought(StoryGenerator)
  // |     self.dialogue_gen = dspy.ChainOfThought(DialogueGenerator)
  // |     self.action_resolver = dspy.ChainOfThought(ActionResolver)
  final class GameAI:
    // --8<-- [start:module]
    private val storyGen       = ChainOfThought(Signature.of[StoryGenerator])
    private val dialogueGen    = ChainOfThought(Signature.of[DialogueGenerator])
    private val actionResolver = ChainOfThought(Signature.of[ActionResolver])
    // --8<-- [end:module]

    /** NPC personality lookup Python builds inline in `handle_dialogue`. */
    private val personalityMap: Map[String, String] = Map(
      "Village Elder" -> "Wise, knowledgeable, speaks in riddles, has ancient knowledge",
      "Merchant"      -> "Greedy but fair, loves to bargain, knows about valuable items",
      "Guard"         -> "Dutiful, suspicious of strangers, follows rules strictly",
      "Thief"         -> "Sneaky, untrustworthy, has information about hidden things",
      "Wizard"        -> "Mysterious, powerful, speaks about magic and ancient forces"
    )

    // --8<-- [start:generate-scene]
    def generateScene(player: Player, context: GameContext, recentActions: String = "")(using RuntimeContext)
        : Either[DspyError, Scene] =
      storyGen.apply((
        location       = context.currentLocation,
        player_info    = player.info,
        story_progress = context.storyProgress,
        recent_actions = recentActions
      )).map(s => Scene(
        description = s.output.scene_description,
        actions     = s.output.available_actions,
        npcs        = s.output.npcs_present,
        items       = s.output.items_available
      ))
    // --8<-- [end:generate-scene]

    def handleDialogue(npcName: String, playerInput: String, context: GameContext)(using RuntimeContext)
        : Either[DspyError, Dialogue] =
      val personality = personalityMap.getOrElse(npcName, "Friendly villager with local knowledge")
      dialogueGen.apply((
        npc_name        = npcName,
        npc_personality = personality,
        player_input    = playerInput,
        context         = context.summary
      )).map(r => Dialogue(
        response = r.output.npc_response,
        mood     = r.output.mood_change,
        quest    = r.output.quest_offered,
        info     = r.output.information_revealed
      ))

    def resolveAction(action: String, player: Player, context: GameContext)(using RuntimeContext)
        : Either[DspyError, ActionOutcome] =
      // Difficulty heuristic, ported from `resolve_action`.
      val lower = action.toLowerCase
      val difficulty =
        if Seq("fight", "battle", "attack").exists(lower.contains) then "hard"
        else if Seq("look", "examine", "talk").exists(lower.contains) then "easy"
        else "medium"
      actionResolver.apply((
        action       = action,
        player_stats = player.stats,
        context      = context.summary,
        difficulty   = difficulty
      )).map(r => ActionOutcome(
        success     = r.output.success,
        description = r.output.outcome_description,
        statChanges = r.output.stat_changes,
        items       = r.output.items_gained,
        experience  = r.output.experience_gained
      ))

  // ── Snippets 3/4 — console rendering, menus, character creation, and the input() game loop ──
  // Out of scope: terminal I/O and game-state bookkeeping around the DSPy calls above. Drive `GameAI`
  // from your own loop, threading the returned `Scene` / `Dialogue` / `ActionOutcome` back into state.

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.ai_text_game.aiTextGameMain"
@main def aiTextGameMain(): Unit = Demo.withLm {
  val ai      = new AiTextGame.GameAI
  val player  = Player(name = "Aria", skills = List("perception", "stealth"))
  val context = GameContext(currentLocation = "the misty village square")
  ai.generateScene(player, context, recentActions = "Just arrived in town.") match
    case Left(err)    => println(s"⚠️  ${err.message}")
    case Right(scene) =>
      println(s"📜 ${scene.description}")
      println(s"🎯 Actions: ${scene.actions.mkString(", ")}")
}
