package dspy4s.typed

import dspy4s.core.contracts.{FieldMetadata, TypeRef}
import munit.FunSuite

enum P7Emotion:
  case sadness, joy, love

object P7Emotion extends ValueDecoder.FlatEnum[P7Emotion]

case class P7Score(sentiment: P7Emotion, confidence: Double)

def p7ScalarEmotion(sentence: String): P7Emotion =
  P7Emotion.joy

def p7NamedEmotion(sentence: String): (sentiment: P7Emotion) =
  (sentiment = P7Emotion.joy)

def p7ScoredEmotion(sentence: String, hint: String): (sentiment: P7Emotion, confidence: Double) =
  (sentiment = P7Emotion.love, confidence = 0.9)

def p7TupleEmotion(sentence: String): (P7Emotion, Double) =
  (P7Emotion.joy, 0.9)

def p7CaseClassEmotion(sentence: String): P7Score =
  P7Score(P7Emotion.joy, 0.9)

/** Method/function signature macro surface. */
class Phase7FunctionMacroSuite extends FunSuite:

  test("method signature derives inputs from parameters and scalar output as result") {
    val sig = TypedSignature.from(p7ScalarEmotion)

    assertEquals(sig.untyped.name, "p7ScalarEmotion")
    assertEquals(sig.untyped.inputFields.map(_.name), Vector("sentence"))
    assertEquals(sig.untyped.outputFields.map(_.name), Vector("result"))
    assertEquals(sig.untyped.signatureString, "sentence -> result")

    val encoded = sig.inputShape.encode((sentence = "I missed the train"))
    assertEquals(encoded, Map[String, Any]("sentence" -> "I missed the train"))

    val decoded = sig.outputShape.decode(Map("result" -> "joy")).toOption.get
    val result: P7Emotion = decoded.result
    assertEquals(result, P7Emotion.joy)
  }

  test("method signature keeps named tuple output field names") {
    val sig = TypedSignature.from(p7NamedEmotion)
    assertEquals(sig.untyped.outputFields.map(_.name), Vector("sentiment"))

    val decoded = sig.outputShape.decode(Map("sentiment" -> "sadness")).toOption.get
    val sentiment: P7Emotion = decoded.sentiment
    assertEquals(sentiment, P7Emotion.sadness)
  }

  test("method signature supports multiple inputs and multiple named tuple outputs") {
    val sig = TypedSignature.from(p7ScoredEmotion)
    assertEquals(sig.untyped.inputFields.map(_.name), Vector("sentence", "hint"))
    assertEquals(sig.untyped.outputFields.map(_.name), Vector("sentiment", "confidence"))

    val encoded = sig.inputShape.encode((sentence = "hi", hint = "warm"))
    assertEquals(encoded, Map[String, Any]("sentence" -> "hi", "hint" -> "warm"))

    val decoded = sig.outputShape.decode(Map("sentiment" -> "love", "confidence" -> "0.75")).toOption.get
    val sentiment: P7Emotion = decoded.sentiment
    val confidence: Double = decoded.confidence
    assertEquals(sentiment, P7Emotion.love)
    assertEquals(confidence, 0.75)
  }

  test("method signature supports case class output products") {
    val sig = TypedSignature.from(p7CaseClassEmotion)
    assertEquals(sig.untyped.outputFields.map(_.name), Vector("sentiment", "confidence"))

    val decoded = sig.outputShape.decode(Map("sentiment" -> "joy", "confidence" -> 0.8))
    assertEquals(decoded, Right(P7Score(P7Emotion.joy, 0.8)))
  }

  test("method signature supports unnamed tuple output products") {
    val sig = TypedSignature.from(p7TupleEmotion)
    assertEquals(sig.untyped.outputFields.map(_.name), Vector("_1", "_2"))

    val decoded = sig.outputShape.decode(Map("_1" -> "joy", "_2" -> "0.8"))
    assertEquals(decoded, Right((P7Emotion.joy, 0.8)))
  }

  test("method signature propagates enum metadata") {
    val sig = TypedSignature.from(p7NamedEmotion)
    val field = sig.untyped.outputFields.head
    assertEquals(field.typeRef, TypeRef.string)
    assertEquals(field.metadata.get(FieldMetadata.EnumCases), Some("sadness,joy,love"))
    assertEquals(field.metadata.get(FieldMetadata.EnumName), Some("P7Emotion"))
  }

  test("compile error: missing input ValueDecoder") {
    val errors = compileErrors("""
      class NotDecodable
      def badInput(value: NotDecodable): String = ""
      val sig = dspy4s.typed.TypedSignature.from(badInput)
    """)
    assert(errors.contains("No ValueDecoder"),
      s"expected helpful error about missing ValueDecoder, got:\n$errors")
  }

  test("compile error: Unit output") {
    val errors = compileErrors("""
      def noOutput(sentence: String): Unit = ()
      val sig = dspy4s.typed.TypedSignature.from(noOutput)
    """)
    assert(errors.contains("not Unit"),
      s"expected helpful error about Unit output, got:\n$errors")
  }
