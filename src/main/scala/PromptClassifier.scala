import com.jamesward.zio_typesafe_ai.TypeSafeAI
import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import WordGeneration.Prompt
import zio.*

object PromptClassifier:
  final case class Context(requiresExternalData: Boolean):
    def guidance: Vector[String] =
      if requiresExternalData then Vector(
        "Jev determined that an accurate answer requires current, private, or external data.",
        "No live or external data source is available to this generator.",
        "Do not assert a current value or condition; state the limitation and recommend a reliable live source.",
      )
      else Vector(
        "Jev found no external-data requirement.",
        "Use only the original message, supplied evidence, and timeless knowledge; avoid unsupported specific claims.",
      )

    def responsePlan: Vector[String] =
      if requiresExternalData then Vector(
        "I", "cannot", "access", "the", "live", "or", "external", "data", "needed", "to",
        "answer", "accurately", "so", "please", "check", "a", "reliable", "current", "source",
      )
      else Vector.empty

    def minimumSentenceWords: Int = if requiresExternalData then 6 else 1

  object Context:
    val unclassified: Context = Context(requiresExternalData = false)

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

  def classify(prompt: Prompt): ZIO[TypeSafeAI.Client, TypeSafeAI.Error, Classified] =
    for
      started <- Clock.nanoTime
      result <- TypeSafeAI.ask(
        prompt.value,
        (requiresExternalData = externalDataQuestion),
      ).run
      finished <- Clock.nanoTime
    yield Classified(
      Context(result.answers.requiresExternalData.unwrap >= 0.5),
      result.usage.inputTokens,
      result.usage.outputTokens,
      (finished - started) / 1000000L,
    )
