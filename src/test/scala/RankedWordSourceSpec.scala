import WordGeneration.*
import zio.test.*

object RankedWordSourceSpec extends ZIOSpecDefault:
  private def parsed(value: String): Prompt =
    Prompt.parse(value).fold(error => throw IllegalArgumentException(error), identity)

  private def withWords(words: Vector[String]): State =
    words.foldLeft(State.initial(parsed("how do llm's work?"))):
      case (state, word) => transition(state, Action.SelectWord(word)) match
        case Transition.Continue(next, _) => next
        case Transition.Done(_) => throw IllegalStateException("Word selection unexpectedly completed")


  private val externalDataContext = PromptClassifier.Context(requiresExternalData = true)

  private def rankedPlan(initial: State): Vector[String] =
    initial.promptContext.responsePlan.foldLeft((initial, Vector.empty[String])):
      case ((state, selected), expected) =>
        val candidates = RankedWordSource.candidates(state)
        if candidates.map(_.word) != Vector(expected) then
          throw IllegalStateException(s"Expected only '$expected', got ${candidates.map(_.word)}")
        transition(state, Action.SelectWord(expected)) match
          case Transition.Continue(next, _) => next -> (selected :+ expected)
          case Transition.Done(_) => throw IllegalStateException("Planned word unexpectedly completed response")
    ._2
  def spec = suite("RankedWordSource")(
    test("keeps prompt terms available without ranking them ahead of response vocabulary") {
      val candidates = RankedWordSource.candidates(State.initial(parsed("Explain Scala streams")))
      val promptCandidates = candidates.filter(_.source == RankedWordSource.Source.Prompt)
      assertTrue(
        promptCandidates.map(_.word).toSet == Set("explain", "scala", "streams"),
        candidates.head.source == RankedWordSource.Source.ResponseVocabulary,
      )
    },
    test("ranks bigram successors before prompt and common backoff words") {
      val initial = State.initial(parsed("Describe a useful system"))
      val state = transition(initial, Action.SelectWord("the")) match
        case Transition.Continue(next, _) => next
        case Transition.Done(_) => throw IllegalStateException("Word selection unexpectedly completed")
      val candidates = RankedWordSource.candidates(state)

      assertTrue(
        candidates.take(4).map(_.word) == Vector("next", "model", "system", "input"),
        candidates.head.source == RankedWordSource.Source.Bigram("the"),
      )
    },
    test("preserves unique ranks within the Jev choice budget") {
      val state = State.initial(parsed("The the response response should be concise"))
      val candidates = RankedWordSource.candidates(state)
      val normalized = candidates.map(_.word.toLowerCase)

      assertTrue(
        candidates.nonEmpty,
        RankedWordSource.EnglishDictionarySize > 40000,
        candidates.size <= RankedWordSource.MaxWordOptions,
        normalized.distinct.size == normalized.size,
        candidates.map(_.rank) == (1 to candidates.size).toVector,
      )
    },
    test("offers explanatory LLM vocabulary before echoing prompt words") {
      val state = State.initial(parsed("how do llm's work?"))
      val candidates = RankedWordSource.candidates(state)
      val firstWords = candidates.take(12).map(_.word.toLowerCase)

      assertTrue(
        firstWords.take(3) == Vector("large", "language", "models"),
        firstWords.contains("tokens"),
        !firstWords.contains("how"),
        !candidates.exists(_.word.toLowerCase == "llm's"),
        candidates.exists(_.word.toLowerCase == "llms"),
      )
    },
    test("prioritizes external-data preflight decisions in exact order") {
      val weatherState = State.initial(parsed("what is the weather in denver?"), externalDataContext)

      assertTrue(
        rankedPlan(weatherState) == externalDataContext.responsePlan,
        RankedWordSource.candidates(weatherState).head.source == RankedWordSource.Source.JevPreflight,
      )
    },
    test("uses multi-word context for grammar and removes repeated phrase continuations") {
      val singular = RankedWordSource.candidates(withWords(Vector("A", "large", "language")))
      val cycle = RankedWordSource.candidates(withWords(Vector(
        "models", "work", "by", "predicting", "the", "next", "token", "based", "on", "data", "and",
        "predicting", "the", "next", "token",
      )))

      assertTrue(
        singular.head.word == "model",
        singular.head.source == RankedWordSource.Source.Phrase(Vector("a", "large", "language")),
        !cycle.exists(_.word.equalsIgnoreCase("based")),
      )
    },
  )
