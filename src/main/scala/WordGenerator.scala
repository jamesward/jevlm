import com.jamesward.zio_typesafe_ai.TypeSafeAI
import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import WordGeneration.*
import zio.*
import zio.http.ServerSentEvent
import zio.json.*
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
        loop <- TypeSafeAI.loop(State.initial(prompt, preflight.context))(
          state => Content(stateView(state)),
          state => ZIO.succeed(loopOptions(state)),
        ):
          (state, action) =>
            transition(state, action) match
              case Transition.Continue(next, emission) =>
                val event = emission match
                  case Emission.Word(value) => serverEvent("word", value)
                  case Emission.SentenceEnd => serverEvent("sentence", ".")
                emit(event).as(LoopStep.Continue(next))
              case Transition.Done(response) => ZIO.succeed(LoopStep.Done(response))
        .maxIterations(MaxDecisionTurns)
        .run
      yield Completed(loop, preflight))
        .provideEnvironment(ZEnvironment(client))

    private def loopOptions(state: State): NonEmptyChunk[LoopOption[Action]] =
      val wordOptions =
        if shouldForceEnd(state) then Vector.empty
        else RankedWordSource.candidates(state).map: candidate =>
          LoopOption.text(
            f"word_${candidate.rank}%03d",
            Action.SelectWord(candidate.word),
            candidateDescription(state, candidate),
          )
      val endingOptions = endingActions(state).map:
        case Action.EndOfSentence => LoopOption.text(
          "end_sentence",
          Action.EndOfSentence,
          "END_OF_SENTENCE: close the current complete thought with a period. Choose this before END_OF_RESPONSE when forced completion is active.",
        )
        case Action.EndOfResponse => LoopOption.text(
          "end_response",
          Action.EndOfResponse,
          "END_OF_RESPONSE: stop now. Prefer this whenever the response already answers the user; never add a repetitive sentence.",
        )
        case Action.SelectWord(_) => throw IllegalStateException("A word is not an ending action")
      val options = wordOptions ++ endingOptions

      options match
        case head +: tail => NonEmptyChunk.fromIterable(head, tail)
        case _ => NonEmptyChunk(LoopOption.text(
          "fallback_word",
          Action.SelectWord("the"),
          "NEXT WORD 'the': emergency common-English fallback.",
        ))

    private def candidateDescription(
      state: State,
      candidate: RankedWordSource.Candidate,
    ): String =
      val resultingTail = (state.responseWords.takeRight(7) :+ candidate.word).mkString(" ")
      s"NEXT WORD '${candidate.word}'. Resulting response tail: '$resultingTail'. " +
        s"Ranking evidence: ${candidate.source.description}. Do not choose it if the tail is redundant or ungrammatical."

    private def stateView(state: State): String =
      s"""Generate a useful, direct, concise response to the original user message one action at a time.
         |Choose exactly one supplied action by considering the entire resulting response, not merely a locally plausible word pair.
         |
         |Decision budget: ${MaxDecisionTurns} total turns.
         |Turns already used: ${state.decisionTurns}.
         |Turns remaining, including END_OF_SENTENCE and END_OF_RESPONSE: ${state.remainingDecisionTurns}.
         |You MUST finish within this budget. Select END_OF_RESPONSE before the remaining count reaches zero.
         |
         |Quality rules:
         |- Answer the user rather than repeating their question.
         |- Never repeat an idea or phrase already present in the response.
         |- Prefer grammatical agreement using the full sentence context.
         |- Usually use one to three concise sentences.
         |- If the accumulated response already answers the question, select END_OF_RESPONSE now.
         |
         |Native Jev preflight decisions:
         |${state.promptContext.guidance.map(instruction => s"- $instruction").mkString("\n")}
         |
         |Original user message:
         |${state.prompt.value}
         |
         |Current accumulated response (${state.responseWords.size} words; ${state.wordsInCurrentSentence} in current sentence):
         |${state.response}""".stripMargin

    private def serverEvent(eventType: String, data: String): ServerSentEvent[String] =
      ServerSentEvent(data, eventType = Some(eventType))
