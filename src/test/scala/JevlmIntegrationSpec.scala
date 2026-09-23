import com.jamesward.zio_evals.*
import WordGeneration.*
import zio.*
import zio.http.{Client as HttpClient, ServerSentEvent}
import zio.json.*
import zio.json.ast.Json
import zio.stream.*
import zio.test.*
import zio.test.TestAspect.*

object JevlmIntegrationSpec extends ZIOSpecDefault:
  private val arm = EvalArm.modelOnly("jevlm", "Jev word-stream generator")
  private val modelId = "jev-word-stream"

  private final case class Streamed(answer: String, summary: WordGenerator.Summary)

  private final case class JevlmAgentLoop(generator: WordGenerator) extends AgentLoop:
    def run(
      prompt: String,
      ignoredModelId: String,
      mcpServers: List[McpServerConfig],
      policy: AgentPolicy,
    ): Task[AgentRunResult] =
      for
        parsed <- ZIO.fromEither(Prompt.parse(prompt)).mapError(IllegalArgumentException(_))
        started <- Clock.nanoTime
        streamEvents <- generator.stream(parsed).runCollect
          .timeoutFail(RuntimeException("Jevlm generation timed out"))(3.minutes)
        finished <- Clock.nanoTime
        streamed <- decode(streamEvents)
      yield AgentRunResult(
        answer = streamed.answer,
        iterations = streamed.summary.turns + 1,
        toolCalls = 0,
        inputTokens = streamed.summary.inputTokens.toLong,
        outputTokens = streamed.summary.outputTokens.toLong,
        latencyMs = (finished - started) / 1000000L,
        events = List(
          TranscriptEvent.AgentMessage(streamed.answer),
          TranscriptEvent.Note(
            s"Jevlm choices=${streamed.summary.turns}, jevLatencyMs=${streamed.summary.jevLatencyMs}, " +
              s"estimatedCostUsd=${streamed.summary.estimatedCostUsd}",
          ),
        ),
      )

    def runStructured(
      prompt: String,
      modelId: String,
      mcpServers: List[McpServerConfig],
      policy: AgentPolicy,
      schema: Json,
    ): Task[String] =
      ZIO.fail(UnsupportedOperationException("Jevlm produces response text, not judge-side structured output"))

    private def decode(events: Chunk[ServerSentEvent[String]]): Task[Streamed] =
      val error = events.collectFirst:
        case event if event.eventType.contains("error") => event.data
      val answer = events.foldLeft(""):
        case (text, event) if event.eventType.contains("word") =>
          if text.isEmpty then event.data else s"$text ${event.data}"
        case (text, event) if event.eventType.contains("sentence") => s"$text."
        case (text, _) => text
      val summary = events.collectFirst:
        case event if event.eventType.contains("done") => event.data.fromJson[WordGenerator.Summary]

      error match
        case Some(message) => ZIO.fail(RuntimeException(message))
        case None => ZIO.fromEither(
          summary.toRight("Jevlm stream ended without completion metadata").flatMap(value => value)
        ).mapError(RuntimeException(_)).map(Streamed(answer, _))

  private def evaluate(definition: JevlmEvalCases.Definition) =
    for
      generator <- ZIO.service[WordGenerator]
      judge <- JevJudge.make(passThreshold = 0.55)
      results <- EvalRunner.run(
        definition.spec,
        arms = List(arm),
        modelIds = List(modelId),
        samples = 1,
        agentLoop = JevlmAgentLoop(generator),
        judge = judge,
      )
      result <- ZIO.fromOption(results.headOption).orElseFail(RuntimeException("zio-evals returned no arm result"))
      answer = result.samples.headOption.map(_.answer).getOrElse("")
    yield assertTrue(
      result.verdict == EvalVerdict.Pass,
      result.checksPassed,
      answer.nonEmpty,
    )

  def spec = suite("Jevlm focused live Jev evaluation")(
    JevlmEvalCases.paid.map: definition =>
      test(f"${definition.id}%03d [${definition.category}] ${definition.prompt}") {
        evaluate(definition)
      }
    *
  ).provideSomeShared[Scope](
    JevHttpLogging.default,
    WordGenerator.live,
  ) @@ ifEnvSet("TYPESAFE_API_KEY")
    @@ withLiveClock
    @@ withLiveSystem
    @@ timeout(5.minutes)
    @@ parallelN(4)
