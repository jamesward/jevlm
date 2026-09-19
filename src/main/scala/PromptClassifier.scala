import com.jamesward.zio_typesafe_ai.TypeSafeAI
import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import WordGeneration.Prompt
import zio.*

object PromptClassifier:
  enum AnswerKind:
    case Prose
    case Numeric

  final case class Context(
    requiresExternalData: Boolean,
    answerKind: AnswerKind,
    numericAnswer: Option[String],
  ):
    def guidance: Vector[String] =
      if requiresExternalData then Vector(
        "Jev's preflight classifier determined that an accurate answer requires current, private, or external data.",
        "No live or external data source is available to this generator.",
        "Do not assert a current value or condition; state the limitation and recommend a reliable live source.",
      )
      else numericAnswer match
        case Some(answer) => Vector(
          s"Jev's preflight numeric decision selected the exact answer: $answer.",
          "Use that numeric answer directly and do not substitute another value.",
        )
        case None => Vector(
          "Jev's preflight classifier found no external-data requirement or exact numeric answer.",
          "Avoid unsupported specific claims.",
        )

    def priorityWords: Vector[String] =
      if requiresExternalData then
        Vector("I", "cannot", "access", "live", "or", "external", "data", "Check", "a", "reliable", "current", "source")
      else numericAnswer.fold(Vector.empty[String]): answer =>
        Vector("The", "answer", "is", answer)

    def minimumSentenceWords: Int =
      if requiresExternalData then 6
      else if answerKind == AnswerKind.Numeric then 4
      else 4

  object Context:
    val unclassified: Context = Context(
      requiresExternalData = false,
      answerKind = AnswerKind.Prose,
      numericAnswer = None,
    )

  final case class Classified(
    context: Context,
    inputTokens: Int,
    outputTokens: Int,
    latencyMs: Long,
  )

  private val externalDataQuestion = Question.Noul(
    "Does answering this user message accurately require current, real-time, location-specific, private, or externally retrieved information that is not contained in the message itself?",
    NoulCriteria(
      whenTrue = "A truthful answer needs live or external facts, such as current weather, prices, scores, news, time, status, location, or private user information.",
      whenFalse = "The request can be answered from timeless general knowledge or facts explicitly present in the message.",
    ),
  )

  private val answerKindCriteria = ChoiceCriteria(
    "prose" -> "The task needs a prose explanation, instruction, refusal, clarification, or other non-numeric response.",
    "numeric" -> "The task asks for one exact numeric result, including arithmetic, unit conversion, or counting characters in literal text.",
  ).fold(error => throw IllegalStateException(error), identity)

  private val answerKindQuestion = Question.Choice(
    "Which response shape does the user request?",
    answerKindCriteria,
  )

  private val numericValues: Vector[String] =
    ((-20 to 220).map(_.toString) ++ Vector("365", "1000", "2000", "10000")).distinct.toVector
  private val numericById: Map[String, String] =
    numericValues.zipWithIndex.map((value, index) => s"number_$index" -> value).toMap
  private val numericCriteria = ChoiceCriteria(
    numericById.toSeq.map((id, value) => id -> s"The exact numeric answer is $value.")*
  ).fold(error => throw IllegalStateException(error), identity)

  private val numericQuestion = Question.Choice(
    "If the user requests an exact numeric result, choose it. Otherwise choose the best placeholder; this answer is ignored for prose tasks.",
    numericCriteria,
  )

  def classify(prompt: Prompt): ZIO[TypeSafeAI.Client, TypeSafeAI.Error, Classified] =
    for
      started <- Clock.nanoTime
      result <- TypeSafeAI.ask(
        prompt.value,
        (
          requiresExternalData = externalDataQuestion,
          answerKind = answerKindQuestion,
          numericAnswer = numericQuestion,
        ),
      ).run
      finished <- Clock.nanoTime
    yield
      val answerKind = result.answers.answerKind.choice match
        case "numeric" => AnswerKind.Numeric
        case _         => AnswerKind.Prose
      val numericAnswer = Option.when(answerKind == AnswerKind.Numeric)(
        numericById.get(result.answers.numericAnswer.choice)
      ).flatten
      Classified(
        Context(
          requiresExternalData = result.answers.requiresExternalData.unwrap >= 0.5,
          answerKind = answerKind,
          numericAnswer = numericAnswer,
        ),
        result.usage.inputTokens,
        result.usage.outputTokens,
        (finished - started) / 1000000L,
      )
