object WordGeneration:
  opaque type Prompt = String

  object Prompt:
    val MaxLength = 2000

    def parse(value: String): Either[String, Prompt] =
      val normalized = value.trim
      if normalized.isEmpty then Left("Enter a message for Jev to respond to.")
      else if normalized.length > MaxLength then Left(s"Messages may contain at most $MaxLength characters.")
      else Right(normalized)

    extension (prompt: Prompt) def value: String = prompt

  enum OutputToken derives CanEqual:
    case Word(value: String)
    case SentenceEnd

  enum Action derives CanEqual:
    case SelectBranch(id: LexicalTree.BranchId)
    case SelectWord(value: String)
    case EndOfSentence
    case EndOfResponse

  enum Emission derives CanEqual:
    case BranchSelected
    case Word(value: String)
    case SentenceEnd

  val MinWordsPerResponse = 1
  val MaxResponseWords = 30
  val MaxDecisionTurns = 68

  private val incompleteTailWords = Set(
    "a", "an", "the", "and", "or", "but", "is", "are", "was", "were", "be", "been", "being",
    "to", "of", "in", "on", "at", "by", "for", "from", "with", "as", "than", "that", "which",
    "because", "if", "when", "while", "based", "using", "through", "cannot", "access",
  )

  final case class State(
    prompt: Prompt,
    promptContext: PromptClassifier.Context,
    tokens: Vector[OutputToken],
    wordsInCurrentSentence: Int,
    decisionTurns: Int,
    selectionPhase: LexicalTree.SelectionPhase,
  ):
    def response: String = render(tokens)
    def responseWords: Vector[String] = tokens.collect:
      case OutputToken.Word(value) => value
    def remainingDecisionTurns: Int = math.max(0, MaxDecisionTurns - decisionTurns)
    def hasFiniteResponsePlan: Boolean = promptContext.responsePlan.nonEmpty
    def nextPlannedWord: Option[String] =
      val normalizedResponse = responseWords.map(_.toLowerCase)
      val normalizedPlan = promptContext.responsePlan.map(_.toLowerCase)
      Option.when(
        normalizedPlan.nonEmpty && normalizedResponse == normalizedPlan.take(normalizedResponse.size)
      )(promptContext.responsePlan.lift(normalizedResponse.size)).flatten
    def responsePlanComplete: Boolean =
      hasFiniteResponsePlan && responseWords.map(_.toLowerCase) == promptContext.responsePlan.map(_.toLowerCase)

  object State:
    def initial(
      prompt: Prompt,
      context: PromptClassifier.Context = PromptClassifier.Context.unclassified,
    ): State = State(prompt, context, Vector.empty, 0, 0, LexicalTree.SelectionPhase.Branches)

  enum Transition derives CanEqual:
    case Continue(state: State, emission: Emission)
    case Done(response: String)

  def wouldRepeatNGram(state: State, candidate: String, size: Int): Boolean =
    val normalized = state.responseWords.map(_.toLowerCase)
    val nextWindow = normalized.takeRight(size - 1) :+ candidate.toLowerCase
    size > 1 && nextWindow.size == size && normalized.sliding(size).contains(nextWindow)

  def hasRepeatedNGram(state: State, size: Int): Boolean =
    val windows = state.responseWords.map(_.toLowerCase).sliding(size).toVector
    size > 1 && windows.distinct.size != windows.size

  def shouldForceEnd(state: State): Boolean =
    val enoughToEnd = state.responseWords.size >= MinWordsPerResponse
    state.responsePlanComplete || (enoughToEnd && (
      state.responseWords.size >= MaxResponseWords ||
      state.remainingDecisionTurns <= 4 ||
      hasRepeatedNGram(state, 3)
    ))

  def canEndCurrentSentence(state: State): Boolean =
    val enoughWords = state.wordsInCurrentSentence >= state.promptContext.minimumSentenceWords
    val tailIsComplete = state.responseWords.lastOption.exists: word =>
      !incompleteTailWords.contains(word.toLowerCase)
    enoughWords && tailIsComplete

  def endingActions(state: State): Vector[Action] =
    if state.selectionPhase != LexicalTree.SelectionPhase.Branches then Vector.empty
    else if state.hasFiniteResponsePlan then
      if !state.responsePlanComplete then Vector.empty
      else if state.wordsInCurrentSentence > 0 then Vector(Action.EndOfSentence)
      else Vector(Action.EndOfResponse)
    else if shouldForceEnd(state) then
      if state.wordsInCurrentSentence >= 2 && state.remainingDecisionTurns > 1 then Vector(Action.EndOfSentence)
      else Vector(Action.EndOfResponse)
    else
      Vector(
        Option.when(canEndCurrentSentence(state))(Action.EndOfSentence),
        Option.when(
          state.responseWords.size >= MinWordsPerResponse &&
            state.wordsInCurrentSentence == 0 &&
            state.tokens.lastOption.contains(OutputToken.SentenceEnd)
        )(Action.EndOfResponse),
      ).flatten

  def descend(state: State, retained: Vector[LexicalTree.RetainedBranch]): Transition =
    Transition.Continue(
      state.copy(
        decisionTurns = state.decisionTurns + 1,
        selectionPhase = LexicalTree.SelectionPhase.Leaves(retained),
      ),
      Emission.BranchSelected,
    )

  def transition(state: State, action: Action): Transition =
    action match
      case Action.SelectBranch(id) =>
        descend(state, Vector(LexicalTree.RetainedBranch(id, 1.0)))
      case Action.SelectWord(value) =>
        Transition.Continue(
          state.copy(
            tokens = state.tokens :+ OutputToken.Word(value),
            wordsInCurrentSentence = state.wordsInCurrentSentence + 1,
            decisionTurns = state.decisionTurns + 1,
            selectionPhase = LexicalTree.SelectionPhase.Branches,
          ),
          Emission.Word(value),
        )
      case Action.EndOfSentence =>
        Transition.Continue(
          state.copy(
            tokens = state.tokens :+ OutputToken.SentenceEnd,
            wordsInCurrentSentence = 0,
            decisionTurns = state.decisionTurns + 1,
            selectionPhase = LexicalTree.SelectionPhase.Branches,
          ),
          Emission.SentenceEnd,
        )
      case Action.EndOfResponse => Transition.Done(state.response)

  def render(tokens: Vector[OutputToken]): String =
    tokens.foldLeft(""):
      case (response, OutputToken.Word(value)) =>
        if response.isEmpty then value else s"$response $value"
      case (response, OutputToken.SentenceEnd) => s"$response."
