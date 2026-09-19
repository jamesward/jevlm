import zio.test.*

object PromptClassifierContextSpec extends ZIOSpecDefault:
  def spec = suite("PromptClassifier.Context")(
    test("turns an external-data decision into truthful limitation guidance") {
      val context = PromptClassifier.Context(
        requiresExternalData = true,
        answerKind = PromptClassifier.AnswerKind.Prose,
        numericAnswer = None,
      )
      assertTrue(
        context.guidance.exists(_.contains("No live or external data source is available")),
        context.priorityWords.take(4) == Vector("I", "cannot", "access", "live"),
        context.minimumSentenceWords == 6,
      )
    },
    test("turns a numeric Jev decision into answer vocabulary") {
      val context = PromptClassifier.Context(
        requiresExternalData = false,
        answerKind = PromptClassifier.AnswerKind.Numeric,
        numericAnswer = Some("4"),
      )
      assertTrue(
        context.priorityWords == Vector("The", "answer", "is", "4"),
        context.guidance.exists(_.contains("exact answer: 4")),
        context.minimumSentenceWords == 4,
      )
    },
    test("has a neutral unclassified context for pure generation tests") {
      assertTrue(
        !PromptClassifier.Context.unclassified.requiresExternalData,
        PromptClassifier.Context.unclassified.numericAnswer.isEmpty,
      )
    },
  )
