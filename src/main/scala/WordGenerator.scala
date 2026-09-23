import com.jamesward.zio_typesafe_ai.TypeSafeAI
import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import WordGeneration.*
import zio.*
import zio.http.ServerSentEvent
import zio.json.*
import zio.schema.{Schema, derived}
import zio.stream.*

trait WordGenerator:
  def stream(prompt: Prompt): ZStream[Any, Nothing, ServerSentEvent[String]]

object WordGenerator:
  final case class Summary(
    turns: Int,
    inputTokens: Int,
    outputTokens: Int,
    jevLatencyMs: Long,
    estimatedCostUsd: BigDecimal,
  ) derives JsonCodec


  final case class BudgetView(
    maxJevRounds: Int,
    usedJevRounds: Int,
    remainingJevRounds: Int,
    maxCommittedWords: Int,
    committedWords: Int,
  ) derives Schema

  final case class RetainedBranchView(id: String, probability: Double) derives Schema

  final case class TraversalView(
    phase: String,
    retainedBranches: Vector[RetainedBranchView],
  ) derives Schema

  final case class GenerationView(
    objective: String,
    originalUserMessage: String,
    factualConstraints: Vector[String],
    accumulatedResponse: String,
    currentSentenceTail: Vector[String],
    wordsInCurrentSentence: Int,
    traversal: TraversalView,
    budget: BudgetView,
    qualityRules: Vector[String],
  ) derives Schema

  private final case class Completed(
    loop: LoopResult[String],
    preflight: PromptClassifier.Classified,
  )
  val live: URLayer[TypeSafeAI.Client, WordGenerator] =
    ZLayer.fromFunction(Live.apply)

  private final case class Live(client: TypeSafeAI.Client) extends WordGenerator:
    def stream(prompt: Prompt): ZStream[Any, Nothing, ServerSentEvent[String]] =
      ZStream.unwrapScoped:
        for
          events <- Queue.unbounded[Option[ServerSentEvent[String]]]
          _ <- produce(prompt, events)
            .ensuring(events.offer(None).ignore)
            .forkScoped
          _ <- ZIO.addFinalizer(events.shutdown)
        yield ZStream.fromQueue(events).takeUntil(_.isEmpty).collectSome

    private def produce(
      prompt: Prompt,
      events: Queue[Option[ServerSentEvent[String]]],
    ): UIO[Unit] =
      runLoop(prompt, event => events.offer(Some(event)).unit).foldCauseZIO(
        cause =>
          ZIO.logErrorCause("Jev word-generation loop failed", cause) *>
            events.offer(Some(serverEvent("error", "Jev could not complete this response. Please try again."))).unit,
        result =>
          val inputTokens = result.preflight.inputTokens + result.loop.usage.inputTokens
          val outputTokens = result.preflight.outputTokens + result.loop.usage.outputTokens
          val summary = Summary(
            result.loop.turns.size,
            inputTokens,
            outputTokens,
            result.preflight.latencyMs + result.loop.latencyMs,
            JevPricing.pricing.estimateUsd(inputTokens, outputTokens),
          )
          events.offer(Some(serverEvent("done", summary.toJson))).unit,
      )

    private def runLoop(
      prompt: Prompt,
      emit: ServerSentEvent[String] => UIO[Unit],
    ) =
      (for
        preflight <- PromptClassifier.classify(prompt)
        loop <- TypeSafeAI.loopWithTurn(State.initial(prompt, preflight.context))(
          state => Content(stateView(state)),
          state => ZIO.succeed(loopOptions(state)),
        ):
          (state, action, turn) =>
            action match
              case Action.SelectBranch(_) =>
                descend(state, LexicalTree.retainBranches(state, turn)) match
                  case Transition.Continue(next, _) => ZIO.succeed(LoopStep.Continue(next))
                  case Transition.Done(output)       => ZIO.succeed(LoopStep.Done(output))
              case Action.SelectWord(selected) =>
                val word = LexicalTree.bestWord(state, turn).getOrElse(selected)
                handleTransition(transition(state, Action.SelectWord(word)), emit)
              case Action.EndOfSentence =>
                handleTransition(transition(state, action), emit)
              case Action.EndOfResponse =>
                handleTransition(transition(state, action), emit)
        .maxIterations(MaxDecisionTurns)
        .run
      yield Completed(loop, preflight))
        .provideEnvironment(ZEnvironment(client))

    private def loopOptions(state: State): NonEmptyChunk[LoopOption[Action]] =
      val wordOptions =
        if state.hasFiniteResponsePlan then
          RankedWordSource.candidates(state).map: candidate =>
            LoopOption.text(
              f"word_${candidate.rank}%03d",
              Action.SelectWord(candidate.word),
              candidateDescription(state, candidate),
            )
        else state.selectionPhase match
          case LexicalTree.SelectionPhase.Branches =>
            if shouldForceEnd(state) then Vector.empty
            else LexicalTree.branchOptions(state)
          case LexicalTree.SelectionPhase.Leaves(_) =>
            LexicalTree.leafOptions(state)

      val endingOptions = endingActions(state).map:
        case Action.EndOfSentence => LoopOption.text(
          "end_sentence",
          Action.EndOfSentence,
          "END_OF_SENTENCE: the current words already form a complete thought. Choose this immediately when the user request is satisfied, even by one word; do not pad a complete answer.",
        )
        case Action.EndOfResponse => LoopOption.text(
          "end_response",
          Action.EndOfResponse,
          "END_OF_RESPONSE: stop now when the accumulated response already satisfies the user. Prefer a concise complete response over adding another sentence or unrelated words.",
        )
        case Action.SelectBranch(_) | Action.SelectWord(_) =>
          throw IllegalStateException("A lexical selection is not an ending action")
      val options = wordOptions ++ endingOptions

      options match
        case head +: tail => NonEmptyChunk.fromIterable(head, tail)
        case _ => NonEmptyChunk(LoopOption.text(
          "fallback_word",
          Action.SelectWord("the"),
          "NEXT WORD 'the': emergency common-English fallback.",
        ))

    private def handleTransition(
      result: Transition,
      emit: ServerSentEvent[String] => UIO[Unit],
    ): UIO[LoopStep[State, String]] =
      result match
        case Transition.Continue(next, Emission.BranchSelected) =>
          ZIO.succeed(LoopStep.Continue(next))
        case Transition.Continue(next, Emission.Word(value)) =>
          emit(serverEvent("word", value)).as(LoopStep.Continue(next))
        case Transition.Continue(next, Emission.SentenceEnd) =>
          emit(serverEvent("sentence", ".")).as(LoopStep.Continue(next))
        case Transition.Done(response) => ZIO.succeed(LoopStep.Done(response))

    private def candidateDescription(
      state: State,
      candidate: RankedWordSource.Candidate,
    ): String =
      val resultingTail = (state.responseWords.takeRight(7) :+ candidate.word).mkString(" ")
      s"NEXT WORD '${candidate.word}'. Resulting response tail: '$resultingTail'. " +
        s"Ranking evidence: ${candidate.source.description}. Do not choose it if the tail is redundant or ungrammatical."

    private def stateView(state: State): GenerationView =
      val traversal = state.selectionPhase match
        case LexicalTree.SelectionPhase.Branches =>
          TraversalView("choose_branch", Vector.empty)
        case LexicalTree.SelectionPhase.Leaves(retained) =>
          TraversalView(
            "choose_leaf",
            retained.map(branch => RetainedBranchView(branch.id.value, branch.probability)),
          )

      GenerationView(
        objective = "Choose actions that build a useful, direct, concise response one committed word at a time.",
        originalUserMessage = state.prompt.value,
        factualConstraints = state.promptContext.guidance,
        accumulatedResponse = state.response,
        currentSentenceTail = state.responseWords.takeRight(12),
        wordsInCurrentSentence = state.wordsInCurrentSentence,
        traversal = traversal,
        budget = BudgetView(
          maxJevRounds = MaxDecisionTurns,
          usedJevRounds = state.decisionTurns,
          remainingJevRounds = state.remainingDecisionTurns,
          maxCommittedWords = MaxResponseWords,
          committedWords = state.responseWords.size,
        ),
        qualityRules = Vector(
          "Judge the complete resulting response, not merely a locally plausible word pair.",
          "Answer the user rather than repeating the question.",
          "Never repeat an idea or phrase already present in the response.",
          "Prefer grammatical agreement using the full sentence context.",
          "Finish as soon as the response adequately answers the user.",
        ),
      )

    private def serverEvent(eventType: String, data: String): ServerSentEvent[String] =
      ServerSentEvent(data, eventType = Some(eventType))
