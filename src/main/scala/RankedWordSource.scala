import WordGeneration.*
import scala.io.Source as IOSource
import scala.util.Using

object RankedWordSource:
  final case class Candidate(word: String, rank: Int, source: Source)

  enum Source derives CanEqual:
    case JevPreflight
    case Phrase(context: Vector[String])
    case Bigram(previousWord: String)
    case Topic
    case DictionaryRelated
    case ResponseVocabulary
    case Prompt
    case Common

    def description: String = this match
      case JevPreflight          => "required wording supported by the native Jev preflight decision; treat its constraint as authoritative"
      case Phrase(context)       => s"a strong multi-word grammatical continuation after '${context.mkString(" ")}'"
      case Bigram(previousWord)  => s"a strong grammatical continuation after '$previousWord'"
      case Topic                 => "relevant explanatory vocabulary for the user's topic"
      case DictionaryRelated     => "an English dictionary word morphologically related to the prompt"
      case ResponseVocabulary    => "useful general response vocabulary"
      case Prompt                => "a normalized topic term from the user's original message"
      case Common                => "frequency-ranked English dictionary fallback"

  val MaxWordOptions = 96
  val MaxTreeCandidates = 512

  private val englishDictionary: Vector[String] =
    Using.resource(IOSource.fromResource("dictionaries/frequency-words-en.txt")): source =>
      source.getLines().toVector

  val EnglishDictionarySize: Int = englishDictionary.size

  private val responseVocabulary = Vector(
    "A", "The", "This", "It", "They", "You", "We", "In", "To", "For",
    "is", "are", "works", "means", "uses", "learns", "creates", "provides", "helps", "allows",
    "by", "from", "through", "with", "because", "when", "while", "based", "on", "and",
    "data", "information", "patterns", "examples", "context", "input", "output", "process", "system", "result",
    "model", "models", "language", "tokens", "words", "text", "training", "trained", "predict", "generates",
    "different", "possible", "likely", "next", "each", "many", "more", "most", "then", "instead",
    "essentially", "generally", "first", "however", "therefore", "simple", "complex", "useful", "specific", "response",
  )

  private val phraseSuccessors = Vector(
    Vector("a", "large", "language") -> Vector("model"),
    Vector("large", "language", "models") -> Vector("work", "are", "learn", "predict"),
    Vector("large", "language", "model") -> Vector("works", "is", "learns", "predicts"),
    Vector("models", "work", "by") -> Vector("predicting", "learning", "using", "processing"),
    Vector("model", "works", "by") -> Vector("predicting", "learning", "using", "processing"),
    Vector("by", "predicting", "the") -> Vector("next", "most", "likely"),
    Vector("predicting", "the", "next") -> Vector("token", "word"),
    Vector("the", "next", "token") -> Vector("based", "from", "using"),
    Vector("next", "token", "based") -> Vector("on"),
    Vector("token", "based", "on") -> Vector("context", "patterns", "the", "its"),
    Vector("on", "the", "training") -> Vector("data", "examples", "process"),
    Vector("the", "training", "data") -> Vector("and", "it", "provided", "used"),
  )

  private val successors: Map[String, Vector[String]] = Map(
    "a" -> Vector("large", "model", "system", "useful", "simple", "new", "response", "way"),
    "and" -> Vector("then", "the", "it", "models", "uses", "generates", "provides", "can"),
    "are" -> Vector("trained", "models", "based", "used", "able", "the", "a", "not"),
    "based" -> Vector("on", "upon"),
    "because" -> Vector("the", "it", "this", "models", "they", "training", "data"),
    "by" -> Vector("predicting", "learning", "using", "processing", "analyzing", "choosing", "comparing"),
    "can" -> Vector("be", "predict", "generate", "use", "learn", "provide", "help", "also"),
    "data" -> Vector("to", "and", "from", "contains", "provides", "during", "that", "is"),
    "for" -> Vector("the", "a", "each", "this", "that", "training", "prediction", "you"),
    "generates" -> Vector("text", "a", "the", "responses", "tokens", "words", "output"),
    "how" -> Vector("the", "a", "this", "it", "models", "systems", "to"),
    "in" -> Vector("the", "a", "this", "training", "context", "order", "which", "many"),
    "is" -> Vector("a", "the", "trained", "based", "used", "essentially", "not", "able"),
    "it" -> Vector("uses", "learns", "predicts", "generates", "is", "can", "then", "does"),
    "language" -> Vector("models", "model", "and", "patterns", "data", "processing"),
    "large" -> Vector("language", "amounts", "models", "datasets", "neural"),
    "learns" -> Vector("patterns", "from", "to", "how", "relationships", "during"),
    "model" -> Vector("learns", "predicts", "works", "is", "uses", "generates", "processes"),
    "models" -> Vector("work", "learn", "predict", "are", "use", "generate", "process"),
    "neural" -> Vector("networks", "network", "models"),
    "networks" -> Vector("trained", "learn", "process", "that", "with", "use"),
    "next" -> Vector("token", "word", "step", "part", "most", "likely"),
    "on" -> Vector("the", "data", "examples", "context", "a", "this", "patterns"),
    "patterns" -> Vector("in", "from", "and", "that", "to", "within"),
    "predict" -> Vector("the", "a", "which", "what", "next", "tokens", "words"),
    "predicting" -> Vector("the", "a", "which", "what", "each", "next", "tokens"),
    "the" -> Vector("next", "model", "system", "input", "output", "training", "most", "same"),
    "this" -> Vector("means", "allows", "process", "model", "is", "works", "can", "helps"),
    "to" -> Vector("predict", "generate", "learn", "choose", "produce", "process", "the", "a"),
    "token" -> Vector("based", "from", "and", "in", "the", "using", "then"),
    "tokens" -> Vector("based", "and", "from", "into", "that", "to", "are"),
    "trained" -> Vector("on", "using", "with", "to", "from", "by"),
    "training" -> Vector("data", "examples", "process", "teaches", "allows", "uses"),
    "uses" -> Vector("the", "patterns", "data", "context", "a", "training", "probability"),
    "we" -> Vector("can", "use", "see", "provide", "also", "need", "then"),
    "with" -> Vector("the", "a", "data", "context", "training", "each", "more"),
    "work" -> Vector("by", "because", "through", "with", "when", "on"),
    "works" -> Vector("by", "because", "through", "with", "when", "on"),
    "you" -> Vector("can", "provide", "use", "see", "may", "should", "will"),
  )

  private val llmVocabulary = Vector(
    "large", "language", "models", "model", "tokens", "predict", "next", "neural", "networks",
    "training", "trained", "data", "patterns", "context", "probability", "text", "generate", "responses",
  )

  private val tokenPattern = "[\\p{L}\\p{N}][\\p{L}\\p{N}'’_-]*".r
  private val llmTerms = Set("llm", "llms", "ai", "model", "models", "gpt", "transformer", "transformers")

  private def normalizePromptWord(value: String): String =
    val lower = value.toLowerCase.replace('’', '\'')
    if lower.endsWith("'s") && lower.length > 2 then lower.dropRight(2) + "s" else lower

  private def promptWords(prompt: Prompt): Vector[String] =
    tokenPattern.findAllIn(prompt.value).map(normalizePromptWord).filter(_.length > 2).toVector.distinct

  private def dictionaryRelated(promptTerms: Vector[String]): Vector[String] =
    promptTerms.filter(_.length >= 4).flatMap: term =>
      val stem = term.take(5)
      englishDictionary.iterator
        .filter(word => word != term && word.startsWith(stem))
        .take(4)
        .toVector

  def candidates(state: State, limit: Int = MaxWordOptions): Vector[Candidate] =
    val previousWord = state.responseWords.lastOption
    val normalizedWords = state.responseWords.map(_.toLowerCase)
    val usedWords = normalizedWords.toSet
    val recentWords = normalizedWords.takeRight(3).toSet
    val normalizedPrompt = promptWords(state.prompt)
    val hostWords = state.nextPlannedWord.toVector.map(_ -> Source.JevPreflight)

    val phraseWords = phraseSuccessors.collectFirst:
      case (context, words) if normalizedWords.endsWith(context) =>
        words.map(_ -> Source.Phrase(context))
    .toVector.flatten
    val bigramWords = previousWord.toVector.flatMap: previous =>
      successors.getOrElse(previous.toLowerCase, Vector.empty).map(_ -> Source.Bigram(previous))
    val topicWords = Option.when(normalizedPrompt.exists(llmTerms.contains))(llmVocabulary)
      .toVector.flatten
      .filterNot(word => usedWords.contains(word.toLowerCase))
      .map(_ -> Source.Topic)
    val relatedWords = dictionaryRelated(normalizedPrompt).map(_ -> Source.DictionaryRelated)
    val responseWords = responseVocabulary.map(_ -> Source.ResponseVocabulary)
    val originalWords = normalizedPrompt.filterNot(usedWords.contains).map(_ -> Source.Prompt)
    val candidateStream =
      if state.hasFiniteResponsePlan then hostWords.iterator
      else
        phraseWords.iterator ++
          bigramWords.iterator ++
          topicWords.iterator ++
          responseWords.iterator ++
          relatedWords.iterator ++
          originalWords.iterator ++
          englishDictionary.iterator.map(_ -> Source.Common)
    val ranked = candidateStream
      .filterNot((word, _) => recentWords.contains(word.toLowerCase))
      .filterNot((word, _) => wouldRepeatNGram(state, word, 3))
      .distinctBy((word, _) => word.toLowerCase)
      .take(math.max(1, limit))
      .toVector

    ranked.zipWithIndex.map: (entry, index) =>
      Candidate(entry._1, index + 1, entry._2)
