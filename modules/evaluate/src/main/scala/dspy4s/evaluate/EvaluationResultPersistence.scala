package dspy4s.evaluate

import dspy4s.core.contracts.DynamicValues
import dspy4s.evaluate.contracts.EvaluationResult

import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

object EvaluationResultPersistence:

  def saveAsJson(result: EvaluationResult, path: String): Either[String, Unit] =
    try
      val rows = result.results.map { eval =>
        val entry        = scala.collection.mutable.LinkedHashMap[String, ujson.Value]()
        val exampleKeys  = DynamicValues.recordKeys(eval.example.values).toSet
        DynamicValues.recordEntries(eval.example.values).foreach { (k, v) =>
          entry += (s"example_$k" -> toJson(DynamicValues.toAny(v)))
        }
        DynamicValues.recordEntries(eval.prediction.values).foreach { (k, v) =>
          val key = if exampleKeys.contains(k) then s"pred_$k" else k
          entry += (key -> toJson(DynamicValues.toAny(v)))
        }
        entry += (result.metricName -> ujson.Num(eval.score))
        ujson.Obj.from(entry)
      }
      val array = ujson.Arr.from(rows)
      val writer = new PrintWriter(Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8))
      try writer.write(ujson.write(array, indent = 2))
      finally writer.close()
      Right(())
    catch
      case error: Throwable => Left(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))

  def saveAsCsv(result: EvaluationResult, path: String): Either[String, Unit] =
    try
      val headerBuilder = scala.collection.mutable.LinkedHashSet[String]()
      result.results.foreach { eval =>
        val exampleKeys = DynamicValues.recordKeys(eval.example.values).toSet
        DynamicValues.recordKeys(eval.example.values).foreach(k => headerBuilder += s"example_$k")
        DynamicValues.recordKeys(eval.prediction.values).foreach { k =>
          val key = if exampleKeys.contains(k) then s"pred_$k" else k
          headerBuilder += key
        }
      }
      headerBuilder += result.metricName
      val header = headerBuilder.toVector

      val writer = new PrintWriter(Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8))
      try
        writer.println(header.map(csvEscape).mkString(","))
        result.results.foreach { eval =>
          val row = header.map { key =>
            if key == result.metricName then eval.score.toString
            else if key.startsWith("example_") then
              val realKey = key.stripPrefix("example_")
              eval.example.get(realKey).map(dv => toCsvString(DynamicValues.toAny(dv))).getOrElse("")
            else if key.startsWith("pred_") then
              val realKey = key.stripPrefix("pred_")
              eval.prediction.get(realKey).map(dv => toCsvString(DynamicValues.toAny(dv))).getOrElse("")
            else
              eval.prediction.get(key).map(dv => toCsvString(DynamicValues.toAny(dv))).getOrElse("")
          }
          writer.println(row.map(csvEscape).mkString(","))
        }
        Right(())
      finally writer.close()
    catch
      case error: Throwable => Left(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))

  private def toJson(value: Any): ujson.Value = value match
    case s: String   => ujson.Str(s)
    case b: Boolean  => ujson.Bool(b)
    case n: Int      => ujson.Num(n.toDouble)
    case n: Long     => ujson.Num(n.toDouble)
    case n: Double   => ujson.Num(n)
    case n: Float    => ujson.Num(n.toDouble)
    case _: None.type => ujson.Null
    case Some(inner) => toJson(inner)
    case m: Map[?, ?] =>
      ujson.Obj.from(m.iterator.collect { case (k: String, v) => k -> toJson(v) })
    case seq: Iterable[?] =>
      ujson.Arr.from(seq.iterator.map(toJson))
    case arr: Array[?] =>
      ujson.Arr.from(arr.iterator.map(toJson))
    case other => ujson.Str(String.valueOf(other))

  private def toCsvString(value: Any): String = value match
    case s: String  => s
    case b: Boolean => b.toString
    case n: Number  => n.toString
    case _: None.type => ""
    case Some(inner) => toCsvString(inner)
    case seq: Iterable[?] => seq.map(toCsvString).mkString("; ")
    case arr: Array[?] => arr.map(toCsvString).mkString("; ")
    case other => String.valueOf(other)

  private def csvEscape(field: String): String =
    if field.contains(",") || field.contains("\"") || field.contains("\n") then
      "\"" + field.replace("\"", "\"\"") + "\""
    else field
