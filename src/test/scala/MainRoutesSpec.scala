import WordGeneration.*
import zio.*
import zio.http.*
import zio.stream.*
import zio.test.*

object MainRoutesSpec extends ZIOSpecDefault:
  private val fakeGenerator = new WordGenerator:
    def stream(prompt: Prompt): ZStream[Any, Nothing, ServerSentEvent[String]] =
      ZStream(
        ServerSentEvent("Hello", eventType = Some("word")),
        ServerSentEvent(".", eventType = Some("sentence")),
        ServerSentEvent("{}", eventType = Some("done")),
      )

  private def run(request: Request): UIO[Response] =
    ZIO.scoped(Main.routes.provide(fakeGenerator).runZIO(request))

  def spec = suite("Main routes")(
    test("streams generated actions as server-sent events") {
      for
        response <- run(Request.post("/api/generate", Body.fromString("Say hello")))
        body <- response.body.asString.orDie
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("event: word"),
        body.contains("data: Hello"),
        body.contains("event: sentence"),
        body.contains("event: done"),
      )
    },
    test("rejects an empty prompt before invoking generation") {
      for
        response <- run(Request.post("/api/generate", Body.fromString("   ")))
        body <- response.body.asString.orDie
      yield assertTrue(
        response.status == Status.BadRequest,
        body == "Enter a message for Jev to respond to.",
      )
    },
  )
