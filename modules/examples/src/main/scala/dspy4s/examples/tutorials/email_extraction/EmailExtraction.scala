/**
 * Extracting Information from Emails with DSPy
 *
 * Source:   docs/docs/tutorials/email_extraction/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/email_extraction/index.md
 * Status:   translated (the typed signatures + the composed processor, snippets 3/4/5/6). The MLflow
 *           autolog setup (snippets 1/2) is observability glue, not a dspy feature, and is out of scope.
 *
 * Pydantic `BaseModel`s / `str, Enum`s become Scala `case class`es / `enum`s that `derive Schema`, so the
 * `email_type: EmailType` / `key_entities: list[ExtractedEntity]` / `financial_amount: Optional[float]`
 * fields keep their structure across the typed boundary (enum → wire string, case class / list → JSON,
 * `Optional` → `Option`). Python's `class EmailProcessor(dspy.Module)` composing four `ChainOfThought`s
 * becomes a plain class threading their typed outputs through an `Either` for-comprehension. DSPy's
 * per-field `desc=` is not part of the dspy4s `Spec` surface, so those hints are dropped.
 */
package dspy4s.examples.tutorials.email_extraction

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.examples.Demo
import dspy4s.programs.ChainOfThought
import dspy4s.typed.{InputField, OutputField, Signature, Spec}
import zio.blocks.schema.Schema

// ── Snippet 3 (lines 68–95) — the enums + the entity model (top-level for Schema derivation) ──
// | class EmailType(str, Enum): ORDER_CONFIRMATION = "order_confirmation"; ...
enum EmailType derives Schema:
  case order_confirmation, support_request, meeting_invitation, newsletter,
       promotional, invoice, shipping_notification, other

// | class UrgencyLevel(str, Enum): LOW = "low"; MEDIUM = "medium"; HIGH = "high"; CRITICAL = "critical"
enum UrgencyLevel derives Schema:
  case low, medium, high, critical

// | class ExtractedEntity(BaseModel): entity_type: str; value: str; confidence: float
case class ExtractedEntity(entity_type: String, value: String, confidence: Double) derives Schema

// ── Snippet 4 (lines 101–145) — the four signatures (top-level traits for Mirror derivation) ──
// | class ClassifyEmail(dspy.Signature): """Classify the type and urgency of an email based on its content."""
trait ClassifyEmail extends Spec:
  def email_subject: InputField[String]
  def email_body:    InputField[String]
  def sender:        InputField[String]
  def email_type: OutputField[EmailType]
  def urgency:    OutputField[UrgencyLevel]
  def reasoning:  OutputField[String]

// | class ExtractEntities(dspy.Signature): """Extract key entities and information from email content."""
trait ExtractEntities extends Spec:
  def email_content: InputField[String]
  def email_type:    InputField[EmailType]
  def key_entities:     OutputField[List[ExtractedEntity]]
  def financial_amount: OutputField[Option[Double]]
  def important_dates:  OutputField[List[String]]
  def contact_info:     OutputField[List[String]]

// | class GenerateActionItems(dspy.Signature): """Determine what actions are needed ..."""
trait GenerateActionItems extends Spec:
  def email_type:         InputField[EmailType]
  def urgency:            InputField[UrgencyLevel]
  def email_summary:      InputField[String]
  def extracted_entities: InputField[List[ExtractedEntity]]
  def action_required: OutputField[Boolean]
  def action_items:    OutputField[List[String]]
  def deadline:        OutputField[Option[String]]
  def priority_score:  OutputField[Int]

// | class SummarizeEmail(dspy.Signature): """Create a concise summary of the email content."""
trait SummarizeEmail extends Spec:
  def email_subject: InputField[String]
  def email_body:    InputField[String]
  def key_entities:  InputField[List[ExtractedEntity]]
  def summary: OutputField[String]

/** The aggregated result Python builds with `dspy.Prediction(...)` in snippet 5 — a single typed value
  * holding the merged outputs of the four steps. */
case class EmailAnalysis(
    email_type: EmailType,
    urgency: UrgencyLevel,
    summary: String,
    key_entities: List[ExtractedEntity],
    financial_amount: Option[Double],
    important_dates: List[String],
    action_required: Boolean,
    action_items: List[String],
    deadline: Option[String],
    priority_score: Int,
    reasoning: String,
    contact_info: List[String]
)

object EmailExtraction:

  // ── Snippet 5 (lines 151–211) — the composed module ──
  // | class EmailProcessor(dspy.Module):
  // |     def __init__(self):
  // |         self.classifier = dspy.ChainOfThought(ClassifyEmail)
  // |         self.entity_extractor = dspy.ChainOfThought(ExtractEntities)
  // |         self.action_generator = dspy.ChainOfThought(GenerateActionItems)
  // |         self.summarizer = dspy.ChainOfThought(SummarizeEmail)
  // |     def forward(self, email_subject, email_body, sender=""): ...
  final class EmailProcessor:
    private val classifier      = ChainOfThought(Signature.of[ClassifyEmail])
    private val entityExtractor = ChainOfThought(Signature.of[ExtractEntities])
    private val actionGenerator = ChainOfThought(Signature.of[GenerateActionItems])
    private val summarizer      = ChainOfThought(Signature.of[SummarizeEmail])

    def forward(
        emailSubject: String,
        emailBody: String,
        sender: String = ""
    )(using RuntimeContext): Either[DspyError, EmailAnalysis] =
      for
        // Step 1: Classify the email
        classification <- classifier.apply((
                            email_subject = emailSubject,
                            email_body    = emailBody,
                            sender        = sender
                          ))
        // Step 2: Extract entities
        fullContent = s"Subject: $emailSubject\n\nFrom: $sender\n\n$emailBody"
        entities <- entityExtractor.apply((
                      email_content = fullContent,
                      email_type    = classification.output.email_type
                    ))
        // Step 3: Generate summary
        summary <- summarizer.apply((
                     email_subject = emailSubject,
                     email_body    = emailBody,
                     key_entities  = entities.output.key_entities
                   ))
        // Step 4: Determine actions
        actions <- actionGenerator.apply((
                     email_type         = classification.output.email_type,
                     urgency            = classification.output.urgency,
                     email_summary      = summary.output.summary,
                     extracted_entities = entities.output.key_entities
                   ))
      // Step 5: Structure the results
      yield EmailAnalysis(
        email_type       = classification.output.email_type,
        urgency          = classification.output.urgency,
        summary          = summary.output.summary,
        key_entities     = entities.output.key_entities,
        financial_amount = entities.output.financial_amount,
        important_dates  = entities.output.important_dates,
        action_required  = actions.output.action_required,
        action_items     = actions.output.action_items,
        deadline         = actions.output.deadline,
        priority_score   = actions.output.priority_score,
        reasoning        = classification.output.reasoning,
        contact_info     = entities.output.contact_info
      )

  // ── Snippet 6 (lines 217–316) — the demo over sample emails ──
  // | def run_email_processing_demo(): ... processor = EmailProcessor(); for email in sample_emails: ...
  final case class SampleEmail(subject: String, body: String, sender: String)

  val sampleEmails: Vector[SampleEmail] = Vector(
    SampleEmail(
      subject = "Order Confirmation #12345 - Your MacBook Pro is on the way!",
      body =
        """Dear John Smith,
          |
          |Thank you for your order! We're excited to confirm that your order #12345 has been processed.
          |
          |Order Details:
          |- MacBook Pro 14-inch (Space Gray)
          |- Order Total: $2,399.00
          |- Estimated Delivery: December 15, 2024
          |- Tracking Number: 1Z999AA1234567890
          |
          |If you have any questions, please contact our support team at support@techstore.com.
          |
          |Best regards,
          |TechStore Team""".stripMargin,
      sender = "orders@techstore.com"
    ),
    SampleEmail(
      subject = "URGENT: Server Outage - Immediate Action Required",
      body =
        """Hi DevOps Team,
          |
          |We're experiencing a critical server outage affecting our production environment.
          |
          |Impact: All users unable to access the platform
          |Started: 2:30 PM EST
          |
          |Please join the emergency call immediately: +1-555-123-4567
          |
          |This is our highest priority.
          |
          |Thanks,
          |Site Reliability Team""".stripMargin,
      sender = "alerts@company.com"
    ),
    SampleEmail(
      subject = "Meeting Invitation: Q4 Planning Session",
      body =
        """Hello team,
          |
          |You're invited to our Q4 planning session.
          |
          |When: Friday, December 20, 2024 at 2:00 PM - 4:00 PM EST
          |Where: Conference Room A
          |
          |Please confirm your attendance by December 18th.
          |
          |Best,
          |Sarah Johnson""".stripMargin,
      sender = "sarah.johnson@company.com"
    )
  )

  /** Process one email and render the headline fields, mirroring the demo's per-email printout. */
  def describe(email: SampleEmail)(using RuntimeContext): String =
    new EmailProcessor().forward(email.subject, email.body, email.sender) match
      case Left(err) => s"   ⚠️  ${err.message}"
      case Right(r) =>
        val amount   = r.financial_amount.fold("")(a => f"\n   💰 Amount: $$$a%,.2f")
        val deadline = if r.action_required then r.deadline.fold("")(d => s"\n   ⏰ Deadline: $d") else ""
        s"""   📊 Type: ${r.email_type}
           |   🚨 Urgency: ${r.urgency}
           |   📝 Summary: ${r.summary}$amount
           |   ✅ Action Required: ${if r.action_required then "Yes" else "No"}$deadline""".stripMargin

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.email_extraction.emailExtractionMain"
@main def emailExtractionMain(): Unit = Demo.withLm {
  println("🚀 Email Processing Demo")
  println("=" * 50)
  EmailExtraction.sampleEmails.zipWithIndex.foreach { (email, i) =>
    println(s"\n📧 EMAIL ${i + 1}: ${email.subject.take(50)}...")
    println(EmailExtraction.describe(email))
  }
}
