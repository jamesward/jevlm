import WordGeneration.*
import zio.test.*

object WordGenerationSpec extends ZIOSpecDefault:
  private def parsed(value: String): Prompt =
    Prompt.parse(value).fold(error => throw IllegalArgumentException(error), identity)

  private def withWords(words: Vector[String]): State =
    words.foldLeft(State.initial(parsed("Explain language models"))):
      case (state, word) => transition(state, Action.SelectWord(word)) match
        case Transition.Continue(next, _) => next
        case Transition.Done(_) => throw IllegalStateException("Word selection unexpectedly completed")

  def spec = suite("WordGeneration")(
    test("parses and normalizes valid prompts") {
      val result = Prompt.parse("  Explain recursive choice  ")
      assertTrue(result.exists(_.value == "Explain recursive choice"))
    },
    test("rejects empty and oversized prompts") {
      val oversized = "x" * (Prompt.MaxLength + 1)
      assertTrue(
        Prompt.parse("   ").isLeft,
        Prompt.parse(oversized).isLeft,
      )
    },
    test("accumulates words and sentence endings before completing") {
      val initial = State.initial(parsed("Say hello"))
      val afterHello = transition(initial, Action.SelectWord("Hello")) match
        case Transition.Continue(state, Emission.Word("Hello")) => state
        case other => throw IllegalStateException(s"Unexpected transition: $other")
      val afterWorld = transition(afterHello, Action.SelectWord("world")) match
        case Transition.Continue(state, Emission.Word("world")) => state
        case other => throw IllegalStateException(s"Unexpected transition: $other")
      val afterSentence = transition(afterWorld, Action.EndOfSentence) match
        case Transition.Continue(state, Emission.SentenceEnd) => state
        case other => throw IllegalStateException(s"Unexpected transition: $other")
      val completed = transition(afterSentence, Action.EndOfResponse)

      assertTrue(
        afterWorld.response == "Hello world",
        afterWorld.decisionTurns == 2,
        afterWorld.remainingDecisionTurns == MaxDecisionTurns - 2,
        afterSentence.response == "Hello world.",
        afterSentence.wordsInCurrentSentence == 0,
        completed == Transition.Done("Hello world."),
      )
    },
    test("makes ending actions state-dependent to prevent empty and repeated endings") {
      val initial = State.initial(parsed("Explain language models"))
      val afterWords = Vector("Language", "models", "predict", "tokens").foldLeft(initial):
        case (state, word) => transition(state, Action.SelectWord(word)) match
          case Transition.Continue(next, _) => next
          case Transition.Done(_) => throw IllegalStateException("Word selection unexpectedly completed")
      val afterSentence = transition(afterWords, Action.EndOfSentence) match
        case Transition.Continue(next, _) => next
        case Transition.Done(_) => throw IllegalStateException("Sentence ending unexpectedly completed")

      assertTrue(
        endingActions(initial).isEmpty,
        endingActions(afterWords).contains(Action.EndOfSentence),
        !endingActions(afterWords).contains(Action.EndOfResponse),
        !endingActions(afterSentence).contains(Action.EndOfSentence),
        endingActions(afterSentence).contains(Action.EndOfResponse),
      )
    },
    test("does not expose endings for an incomplete tail and ends only after a sentence boundary") {
      val context = PromptClassifier.Context(
        requiresExternalData = true,
        answerKind = PromptClassifier.AnswerKind.Prose,
        numericAnswer = None,
      )
      val incomplete = Vector("The", "weather", "is", "a").foldLeft(State.initial(parsed("what is the weather in denver?"), context)):
        case (state, word) => transition(state, Action.SelectWord(word)) match
          case Transition.Continue(next, _) => next
          case Transition.Done(_) => throw IllegalStateException("Word selection unexpectedly completed")
      val complete = Vector("I", "cannot", "access", "live", "weather", "data").foldLeft(State.initial(parsed("what is the weather in denver?"), context)):
        case (state, word) => transition(state, Action.SelectWord(word)) match
          case Transition.Continue(next, _) => next
          case Transition.Done(_) => throw IllegalStateException("Word selection unexpectedly completed")
      val ended = transition(complete, Action.EndOfSentence) match
        case Transition.Continue(next, _) => next
        case Transition.Done(_) => throw IllegalStateException("Sentence ending unexpectedly completed")

      assertTrue(
        endingActions(incomplete).isEmpty,
        endingActions(complete) == Vector(Action.EndOfSentence),
        endingActions(ended) == Vector(Action.EndOfResponse),
      )
    },
    test("detects repeated trigrams and forces completion before the loop bound") {
      val phraseCycle = withWords(Vector(
        "models", "work", "by", "predicting", "the", "next", "token", "based", "on", "data", "and",
        "predicting", "the", "next", "token",
      ))
      val tooLong = withWords(Vector.fill(MaxResponseWords)("word"))

      val closedCycle = transition(phraseCycle, Action.EndOfSentence) match
        case Transition.Continue(next, _) => next
        case Transition.Done(_) => throw IllegalStateException("Sentence ending unexpectedly completed")

      assertTrue(
        wouldRepeatNGram(phraseCycle, "based", 3),
        !wouldRepeatNGram(phraseCycle, "probability", 3),
        shouldForceEnd(phraseCycle),
        shouldForceEnd(tooLong),
        endingActions(phraseCycle) == Vector(Action.EndOfSentence),
        endingActions(closedCycle) == Vector(Action.EndOfResponse),
      )
    },
  )
