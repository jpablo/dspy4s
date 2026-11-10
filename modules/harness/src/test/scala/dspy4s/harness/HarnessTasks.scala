package dspy4s.harness

import dspy4s.core.contracts.{:=, DynamicValues}
import dspy4s.core.data.Example

import java.nio.file.{Files, Path}
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One task of the task set: the question, and the expected answer. The data of each task is in the folder that the
  * question names, in one workspace for the whole set, so one harness can run each task (an optimizer needs that).
  */
final case class HarnessTask(name: String, question: String, expected: String)

/** A task set for live runs and for an optimizer (CODE_HARNESS_DESIGN.md, 9). The data comes from fixed rules, and the
  * code of the test computes each expected answer from the same data, so no answer is typed by hand.
  */
object HarnessTasks:

  private val only = "Give only the answer, with no other words."

  // ── The data ──

  private val orders = Vector(
    (1, "paid", 120),
    (2, "failed", 35),
    (3, "paid", 80),
    (4, "failed", 15),
    (5, "refunded", 60),
    (6, "failed", 50),
    (7, "paid", 200),
    (8, "failed", 5)
  )

  /** The lines of a log: a level and an error code for each line, from fixed rules. */
  private val logLines: Vector[(String, String)] = Vector.tabulate(300) { i =>
    val level = if i % 7 == 0 || i % 11 == 0 then "ERROR" else if i % 3 == 0 then "WARN" else "INFO"
    // E1 occurs 27 times and E3 26 times, so the count must be exact.
    val code = if level == "ERROR" then s"E${(i * i + i) % 5 + 1}" else "-"
    (level, code)
  }

  private val logText: String = logLines.zipWithIndex.map { case ((level, code), i) =>
    f"2024-05-01T10:${i / 60}%02d:${i % 60}%02d $level code=$code request=${1000 + i}"
  }.mkString("", "\n", "\n")

  private val words = Vector("river", "stone", "light", "the", "a", "and", "of", "field", "stone", "wind")

  private val wordText: String = Vector.tabulate(240)(i => words((i * 7 + i / 5) % words.size)).grouped(12)
    .map(_.mkString(" ")).mkString("", "\n", "\n")

  private val names = Vector("Maria", "Chen", "Olu", "Bjorn", "Ana", "Kenji", "Lea", "Diego", "Farah", "Ivan")

  private val users                = Vector(1 -> "Maria", 2 -> "Chen", 3 -> "Olu", 4 -> "Ana")
  private val buys                 = Vector((1, 30), (2, 45), (3, 10), (1, 25), (4, 50), (2, 20), (3, 70), (4, 5), (1, 15))
  private val numbers: Vector[Int] = Vector.tabulate(3000)(i => (i * 37 + 11) % 1000)

  private val readme: String = """# Project
      |Intro text.
      |## Install
      |Steps.
      |### Linux
      |## Use
      |Text with ## inside, not a heading.
      |## Test
      |#### Deep
      |## Release
      |""".stripMargin

  private val emails: Vector[String] = Vector(
    "ana@example.org",
    "bo@mail.test",
    "cy@example.org",
    "di@lab.example.org",
    "ed@mail.test",
    "fo@example.org",
    "gu@uni.test"
  )

  // ── The answers ──

  private def nthPrime(n: Int): Int =
    Iterator.from(2).filter(k => (2 to math.sqrt(k.toDouble).toInt).forall(k % _ != 0))
      .drop(n - 1).next()

  private def fib(n: Int): Long = Iterator.iterate((1L, 1L))((a, b) => (b, a + b)).drop(n - 1).next()._1

  private val errorCodes: Vector[String] = logLines.collect { case ("ERROR", code) => code }

  private val topCode: String = errorCodes.groupBy(identity).maxBy((code, all) => (all.size, code))._1

  private val stop = Set("the", "a", "and", "of")

  private val topWord: String =
    wordText.split("\\s+").filter(w => w.nonEmpty && !stop(w)).groupBy(identity).maxBy((w, all) => (all.size, w))._1

  private val topBuyer: String =
    val totals = buys.groupMapReduce(_._1)(_._2)(_ + _)
    users.toMap.apply(totals.maxBy(_._2)._1)

  // ── The data of the harder tasks ──

  private val inventory = """{"warehouses": [
      |  {"name": "north", "items": [{"name": "bolt", "qty": 120}, {"name": "nut", "qty": 300}]},
      |  {"name": "south", "items": [{"name": "bolt", "qty": 75}, {"name": "screw", "qty": 40}]},
      |  {"name": "east", "items": [{"name": "nut", "qty": 10}, {"name": "bolt", "qty": 5}]}
      |]}
      |""".stripMargin

  private val items =
    Vector("Apple", " apple ", "BANANA", "banana", "Cherry", "cherry ", "date", "Elder", "elder", "fig")

  private val contacts = Vector(
    "Ana 555-123-4567",
    "Bo 5551234567",
    "Cy 555-987-6543 (work)",
    "Di (555) 123-4567",
    "Ed 555-12-34567",
    "Fo 444-555-6666",
    "Gu call 333-222-1111 or later",
    "Hu 12-345-67890",
    "Io 999-888-7777"
  )

  private val events = Vector(
    ("launch", "2024-01-03", "2024-01-20"),
    ("audit", "2024-02-01", "2024-03-15"),
    ("move", "2024-03-10", "2024-03-31"),
    ("review", "2024-04-01", "2024-05-02")
  )

  private val bigRows: Vector[(Int, String, Int)] =
    Vector.tabulate(5000)(i => (i, Vector("A", "B", "C")(i % 3), (i * 13) % 97))

  private val phone = """(?<![\d-])\d{3}-\d{3}-\d{4}(?![\d-])""".r

  private val median: String =
    val sorted = numbers.sorted
    val value  = (BigDecimal(sorted(1499)) + BigDecimal(sorted(1500))) / 2
    value.bigDecimal.stripTrailingZeros.toPlainString

  private val codeOrder: String =
    errorCodes.groupBy(identity).toVector.sortBy((code, all) => (-all.size, code)).map(_._1).mkString(", ")

  /** The tasks. Each question names the folder of its data. */
  val all: Vector[HarnessTask] = Vector(
    HarnessTask(
      "squares",
      s"What is the sum of the squares of the integers from 1 to 20? $only",
      (1 to 20).map(i => i * i).sum.toString
    ),
    HarnessTask("prime", s"What is the 100th prime number? $only", nthPrime(100).toString),
    HarnessTask(
      "fibonacci",
      s"The first two Fibonacci numbers are 1 and 1. What is the 30th Fibonacci number? $only",
      fib(30).toString
    ),
    HarnessTask(
      "dates",
      s"How many days are there from 2024-02-10 to 2024-03-05? $only",
      ChronoUnit.DAYS.between(LocalDate.of(2024, 2, 10), LocalDate.of(2024, 3, 5)).toString
    ),
    HarnessTask(
      "csv",
      s"The file t05/orders.csv has orders. What is the total amount of the orders with the status failed? $only",
      orders.collect { case (_, "failed", amount) => amount }.sum.toString
    ),
    HarnessTask("log-count", s"How many lines of t06/app.log have the level ERROR? $only", errorCodes.size.toString),
    HarnessTask(
      "log-code",
      s"In t06/app.log, which error code occurs most often in the lines with the level ERROR? Give the code, for example E1. $only",
      topCode
    ),
    HarnessTask(
      "find-file",
      s"One file in the folder t08/docs, or in a folder in it, has the word zebra. Give its path relative to t08/docs. $only",
      "b/c/notes.txt"
    ),
    HarnessTask(
      "json",
      s"The file t09/config.json has a list of services. What is the sum of their ports? $only",
      (8080 + 5432 + 6379).toString
    ),
    HarnessTask(
      "edit",
      s"In t10/settings.txt, change the timeout to 30 with the edit function. Then read the file, and give its line with the timeout. $only",
      "timeout=30"
    ),
    HarnessTask(
      "word",
      s"Which word occurs most often in t11/text.txt? Do not count the words the, a, and, of. $only",
      topWord
    ),
    HarnessTask(
      "sort",
      s"Use a shell command to sort the lines of t12/names.txt. Which name is third in alphabetical order? $only",
      names.sorted.apply(2)
    ),
    HarnessTask(
      "join",
      s"t13/users.csv has the users, and t13/orders.csv has their orders. Which user has the largest total amount? Give the name. $only",
      topBuyer
    ),
    HarnessTask(
      "headings",
      s"How many lines of t14/README.md are level-2 headings, that is, lines that start with ## and a space? $only",
      readme.linesIterator.count(_.startsWith("## ")).toString
    ),
    HarnessTask(
      "python",
      s"Run the script t15/calc.py with python3, and give what it prints. $only",
      (1 to 10).map(i => i * i).sum.toString
    ),
    HarnessTask(
      "big-sum",
      s"t16/numbers.txt has one number on each line. What is their sum? $only",
      numbers.map(_.toLong).sum.toString
    ),
    HarnessTask(
      "big-count",
      s"How many numbers in t16/numbers.txt are greater than 900? $only",
      numbers.count(_ > 900).toString
    ),
    HarnessTask(
      "store",
      s"In one cell, compute the squares of 1 to 10, and store them with the key squares. In a later cell, load them and give their sum. $only",
      (1 to 10).map(i => i * i).sum.toString
    ),
    HarnessTask("capital", s"What is the capital of Australia? $only", "Canberra"),
    HarnessTask(
      "domains",
      s"How many different email domains are in t20/emails.txt? A domain is the part after @. $only",
      emails.map(_.split("@")(1)).distinct.size.toString
    ),
    HarnessTask(
      "log-window",
      s"How many lines of t06/app.log with the level ERROR have a time from 10:02:00 to 10:03:59, both included? $only",
      logLines.zipWithIndex.count { case ((level, _), i) => level == "ERROR" && i >= 120 && i <= 239 }.toString
    ),
    HarnessTask(
      "code-order",
      s"List the error codes of the ERROR lines of t06/app.log from the most frequent to the least frequent, separated by a comma and a space. $only",
      codeOrder
    ),
    HarnessTask(
      "nested-json",
      s"t21/inventory.json has warehouses with items. What is the total qty of the items with the name bolt, in all warehouses? $only",
      "200"
    ),
    HarnessTask(
      "many-files",
      s"The folder t22 has the files data_01.txt to data_12.txt, with one number on each line. What is the sum of the numbers in the files whose number is even, such as data_02.txt? $only",
      (1 to 12).filter(_ % 2 == 0).map(k => k + 2 * k + 3 * k).sum.toString
    ),
    HarnessTask(
      "write-sorted",
      s"Write the names of t12/names.txt in reverse alphabetical order to t23/out.txt, one name on each line. Then read t23/out.txt, and give its first line. $only",
      names.sorted.reverse.head
    ),
    HarnessTask(
      "distinct-items",
      s"How many different items does t24/items.txt have, when you ignore the case and the spaces at the ends of each line? $only",
      items.map(_.trim.toLowerCase).distinct.size.toString
    ),
    HarnessTask(
      "median",
      s"What is the median of the numbers in t16/numbers.txt? There is an even number of them, so take the mean of the two middle values. $only",
      median
    ),
    HarnessTask(
      "phones",
      s"How many lines of t25/contacts.txt have a phone number in the exact form 555-123-4567, three digits, a hyphen, three digits, a hyphen, and four digits, with no other digit or hyphen next to it? $only",
      contacts.count(line => phone.findFirstIn(line).isDefined).toString
    ),
    HarnessTask(
      "longest-event",
      s"t26/events.csv has events with a start date and an end date. Which event has the most days from its start to its end? Give its name. $only",
      events.maxBy((_, start, end) => ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)))._1
    ),
    HarnessTask(
      "big-csv",
      s"t27/big.csv has 5000 rows with the columns id, group and value. What is the sum of the values of the rows of the group B? $only",
      bigRows.collect { case (_, "B", value) => value.toLong }.sum.toString
    ),
    HarnessTask(
      "llm",
      s"Use llmQuery to ask a model for the French word for thank you, and give that word in lower case. $only",
      "merci"
    ),
    HarnessTask(
      "rlm",
      s"Use rlm to give a child the task to compute the 100th prime number as an Int, and give the value of the child. $only",
      nthPrime(100).toString
    )
  )

  /** Writes the data of each task under `root`. */
  def writeWorkspace(root: Path): Unit =
    def put(path: String, text: String): Unit =
      val file = root.resolve(path)
      Files.createDirectories(file.getParent): Unit
      Files.writeString(file, text): Unit
    put(
      "t05/orders.csv",
      orders.map((id, status, amount) => s"$id,$status,$amount").mkString("id,status,amount\n", "\n", "\n")
    )
    put("t06/app.log", logText)
    put("t08/docs/a.txt", "apple banana\n")
    put("t08/docs/b/readme.txt", "cherry date\n")
    put("t08/docs/b/c/notes.txt", "a zebra in the field\n")
    put("t08/docs/d/notes.txt", "elephant fig\n")
    put(
      "t09/config.json",
      """{"services": [{"name": "api", "port": 8080}, {"name": "db", "port": 5432}, {"name": "cache", "port": 6379}]}""" +
        "\n"
    )
    put("t10/settings.txt", "name=demo\ntimeout=10\nretries=3\n")
    put("t11/text.txt", wordText)
    put("t12/names.txt", names.mkString("", "\n", "\n"))
    put("t13/users.csv", users.map((id, name) => s"$id,$name").mkString("id,name\n", "\n", "\n"))
    put("t13/orders.csv", buys.map((user, amount) => s"$user,$amount").mkString("user_id,amount\n", "\n", "\n"))
    put("t14/README.md", readme)
    put("t15/calc.py", "print(sum(i * i for i in range(1, 11)))\n")
    put("t16/numbers.txt", numbers.mkString("", "\n", "\n"))
    put("t20/emails.txt", emails.map(e => s"Contact: $e").mkString("", "\n", "\n"))
    put("t21/inventory.json", inventory)
    (1 to 12).foreach(k => put(f"t22/data_$k%02d.txt", Vector(k, 2 * k, 3 * k).mkString("", "\n", "\n")))
    put("t23/README.txt", "The task write-sorted writes its file here.\n")
    put("t24/items.txt", items.mkString("", "\n", "\n"))
    put("t25/contacts.txt", contacts.mkString("", "\n", "\n"))
    put("t26/events.csv", events.map((n, a, b) => s"$n,$a,$b").mkString("name,start,end\n", "\n", "\n"))
    put("t27/big.csv", bigRows.map((i, g, v) => s"$i,$g,$v").mkString("id,group,value\n", "\n", "\n"))

  /** The text of an answer for a compare: no space at the ends, no period at the end, and no pair of quotes around it.
    * The period goes first, so `"2870".` becomes `2870`, and then a period inside the quotes goes too.
    */
  private def normal(text: String): String =
    val bare  = text.trim.stripSuffix(".").trim
    val inner = Vector("\"", "`", "'").foldLeft(bare) { (s, q) =>
      if s.length >= 2 && s.startsWith(q) && s.endsWith(q) then s.drop(1).dropRight(1) else s
    }
    inner.trim.stripSuffix(".").trim

  /** True when `got` is the expected answer: the same text with no case, or the same number. */
  def correct(task: HarnessTask, got: String): Boolean =
    val (a, b) = (normal(got), normal(task.expected))
    a.equalsIgnoreCase(b) || a.filterNot(_.isWhitespace).equalsIgnoreCase(b.filterNot(_.isWhitespace)) ||
    (a.toDoubleOption, b.toDoubleOption).match
      case (Some(x), Some(y)) => x == y
      case _                  => false

  /** The examples of the task set for an optimizer: the input `question` and the label `answer`. */
  def examples: Vector[Example] =
    all.map(t =>
      Example(
        values = DynamicValues.record("question" := t.question, "answer" := t.expected),
        inputKeys = Set("question")
      )
    )
