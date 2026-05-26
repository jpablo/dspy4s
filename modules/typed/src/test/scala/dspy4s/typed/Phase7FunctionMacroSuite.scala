package dspy4s.typed

import zio.blocks.schema.Schema

import dspy4s.core.contracts.{FieldMetadata, TypeRef}
import munit.FunSuite

enum P7Emotion:
  case sadness, joy, love

object P7Emotion extends FieldCodec.FlatEnum[P7Emotion]

case class P7Score(sentiment: P7Emotion, confidence: Double) derives Schema

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
    val sig = Signature.from(p7ScalarEmotion)

    assertEquals(sig.layout.name, "p7ScalarEmotion")
    assertEquals(sig.layout.inputFields.map(_.name), Vector("sentence"))
    assertEquals(sig.layout.outputFields.map(_.name), Vector("result"))
    assertEquals(sig.layout.signatureString, "sentence -> result")

    val encoded = sig.inputShape.encode((sentence = "I missed the train"))
    assertEquals(encoded, Map[String, Any]("sentence" -> "I missed the train"))

    val decoded = sig.outputShape.decode(Map("result" -> "joy")).toOption.get
    val result: P7Emotion = decoded.result
    assertEquals(result, P7Emotion.joy)
  }

  test("method signature keeps named tuple output field names") {
    val sig = Signature.from(p7NamedEmotion)
    assertEquals(sig.layout.outputFields.map(_.name), Vector("sentiment"))

    val decoded = sig.outputShape.decode(Map("sentiment" -> "sadness")).toOption.get
    val sentiment: P7Emotion = decoded.sentiment
    assertEquals(sentiment, P7Emotion.sadness)
  }

  test("method signature supports multiple inputs and multiple named tuple outputs") {
    val sig = Signature.from(p7ScoredEmotion)
    assertEquals(sig.layout.inputFields.map(_.name), Vector("sentence", "hint"))
    assertEquals(sig.layout.outputFields.map(_.name), Vector("sentiment", "confidence"))

    val encoded = sig.inputShape.encode((sentence = "hi", hint = "warm"))
    assertEquals(encoded, Map[String, Any]("sentence" -> "hi", "hint" -> "warm"))

    val decoded = sig.outputShape.decode(Map("sentiment" -> "love", "confidence" -> "0.75")).toOption.get
    val sentiment: P7Emotion = decoded.sentiment
    val confidence: Double = decoded.confidence
    assertEquals(sentiment, P7Emotion.love)
    assertEquals(confidence, 0.75)
  }

  test("method signature supports case class output products") {
    val sig = Signature.from(p7CaseClassEmotion)
    assertEquals(sig.layout.outputFields.map(_.name), Vector("sentiment", "confidence"))

    val decoded = sig.outputShape.decode(Map("sentiment" -> "joy", "confidence" -> 0.8))
    assertEquals(decoded, Right(P7Score(P7Emotion.joy, 0.8)))
  }

  test("method signature supports unnamed tuple output products".ignore) {
    // Step 1 migration: zio-blocks Schema doesn't auto-derive for vanilla Scala Tuple2; this test
    // exercises a path that will be revisited during Step 2 (TupleShape migration). The macro fires
    // at compile time so the body is commented out -- restore once Tuple2 has a Schema.
    fail("deferred to Step 2 of zio-blocks migration")
    // val sig = Signature.from(p7TupleEmotion)
    // assertEquals(sig.layout.outputFields.map(_.name), Vector("_1", "_2"))
    // val decoded = sig.outputShape.decode(Map("_1" -> "joy", "_2" -> "0.8"))
    // assertEquals(decoded, Right((P7Emotion.joy, 0.8)))
  }

  test("method signature propagates enum metadata") {
    val sig = Signature.from(p7NamedEmotion)
    val field = sig.layout.outputFields.head
    assertEquals(field.typeRef, TypeRef.string)
    assertEquals(field.metadata.get(FieldMetadata.EnumCases), Some("sadness,joy,love"))
    assertEquals(field.metadata.get(FieldMetadata.EnumName), Some("P7Emotion"))
  }

  test("type-only function signature supports anonymous input and scalar output") {
    val sig = Signature.fromType[String => P7Emotion]

    assertEquals(sig.layout.name, "Signature")
    assertEquals(sig.layout.inputFields.map(_.name), Vector("input"))
    assertEquals(sig.layout.outputFields.map(_.name), Vector("result"))
    assertEquals(sig.layout.signatureString, "input -> result")

    val encoded = sig.inputShape.encode((input = "I missed the train"))
    assertEquals(encoded, Map[String, Any]("input" -> "I missed the train"))

    val decoded = sig.outputShape.decode(Map("result" -> "joy")).toOption.get
    val result: P7Emotion = decoded.result
    assertEquals(result, P7Emotion.joy)
  }

  test("type-only function signature keeps named inputs and named tuple outputs") {
    val sig =
      Signature.fromType[
        (sentence: String, hint: String) => (sentiment: P7Emotion, confidence: Double)
      ](
        name = "ScoredEmotion",
        instructions = "Classify emotion with confidence."
      )

    assertEquals(sig.layout.name, "ScoredEmotion")
    assertEquals(sig.instructions, Some("Classify emotion with confidence."))
    assertEquals(sig.layout.inputFields.map(_.name), Vector("sentence", "hint"))
    assertEquals(sig.layout.outputFields.map(_.name), Vector("sentiment", "confidence"))
    assertEquals(sig.layout.signatureString, "sentence, hint -> sentiment, confidence")

    val encoded = sig.inputShape.encode((sentence = "hi", hint = "warm"))
    assertEquals(encoded, Map[String, Any]("sentence" -> "hi", "hint" -> "warm"))

    val decoded = sig.outputShape.decode(Map("sentiment" -> "love", "confidence" -> "0.75")).toOption.get
    val sentiment: P7Emotion = decoded.sentiment
    val confidence: Double = decoded.confidence
    assertEquals(sentiment, P7Emotion.love)
    assertEquals(confidence, 0.75)
  }

  test("type-only function signature names multiple anonymous inputs by position") {
    val sig = Signature.fromType[(String, String) => P7Emotion]("Emotion")

    assertEquals(sig.layout.inputFields.map(_.name), Vector("input1", "input2"))
    assertEquals(sig.layout.outputFields.map(_.name), Vector("result"))
    assertEquals(sig.layout.signatureString, "input1, input2 -> result")

    val encoded = sig.inputShape.encode((input1 = "hi", input2 = "warm"))
    assertEquals(encoded, Map[String, Any]("input1" -> "hi", "input2" -> "warm"))
  }

  test("type-only function signature supports case class output products") {
    val sig = Signature.fromType[(sentence: String) => P7Score]("ScoredEmotion")
    assertEquals(sig.layout.inputFields.map(_.name), Vector("sentence"))
    assertEquals(sig.layout.outputFields.map(_.name), Vector("sentiment", "confidence"))

    val decoded = sig.outputShape.decode(Map("sentiment" -> "joy", "confidence" -> 0.8))
    assertEquals(decoded, Right(P7Score(P7Emotion.joy, 0.8)))
  }

  test("compile error: missing input FieldCodec") {
    val errors = compileErrors("""
      class NotDecodable
      def badInput(value: NotDecodable): String = ""
      val sig = dspy4s.typed.Signature.from(badInput)
    """)
    assert(errors.contains("No FieldCodec"),
      s"expected helpful error about missing FieldCodec, got:\n$errors")
  }

  test("compile error: Unit output") {
    val errors = compileErrors("""
      def noOutput(sentence: String): Unit = ()
      val sig = dspy4s.typed.Signature.from(noOutput)
    """)
    assert(errors.contains("not Unit"),
      s"expected helpful error about Unit output, got:\n$errors")
  }

  test("compile error: fromType Unit output") {
    val errors = compileErrors("""
      val sig = dspy4s.typed.Signature.fromType[String => Unit]("Bad")
    """)
    assert(errors.contains("not Unit"),
      s"expected helpful error about Unit output, got:\n$errors")
  }
