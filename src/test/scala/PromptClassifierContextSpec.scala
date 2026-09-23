import zio.test.*

object PromptClassifierContextSpec extends ZIOSpecDefault:
  def spec = suite("PromptClassifier.Context")(
    test("turns an external-data decision into truthful limitation guidance and a finite response") {
      val context = PromptClassifier.Context(requiresExternalData = true)
      assertTrue(
        context.guidance.exists(_.contains("No live or external data source is available")),
        context.responsePlan.take(4) == Vector("I", "cannot", "access", "the"),
        context.responsePlan.lastOption.contains("source"),
        context.minimumSentenceWords == 6,
      )
    },
    test("keeps ordinary prose open for hierarchical lexical traversal") {
      val context = PromptClassifier.Context.unclassified
      assertTrue(
        !context.requiresExternalData,
        context.responsePlan.isEmpty,
        context.minimumSentenceWords == 1,
      )
    },
  )
