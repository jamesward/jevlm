import com.jamesward.zio_typesafe_ai.TypeSafeAI
import WordGeneration.*
import zio.*
import zio.http.*

object Main extends ZIOAppDefault:
  val routes: Routes[WordGenerator, Nothing] = Routes(
    Method.GET / Root -> handler(Response.html(UI.index)),
    Method.GET / "api" / "health" -> handler(Response.json("{\"status\":\"ok\"}")),
    Method.POST / "api" / "generate" -> Handler.fromFunctionZIO[Request]: request =>
      (for
        body <- request.body.asString.mapError(error => s"Could not read the message: ${error.getMessage}")
        prompt <- ZIO.fromEither(Prompt.parse(body))
        generator <- ZIO.service[WordGenerator]
      yield Response.fromServerSentEvents(generator.stream(prompt)))
        .catchAll: message =>
          ZIO.logWarning(s"Rejected generation request: $message") *>
            ZIO.succeed(Response.text(message).status(Status.BadRequest)),
  )

  private val serverLayer =
    ZLayer.fromZIO:
      ZIO.systemWith(_.env("PORT")).map: maybePort =>
        maybePort.flatMap(_.toIntOption).fold(Server.default)(Server.defaultWithPort)
    .flatten

  def run =
    RequiredEnvironment.validateLive.flatMap: _ =>
      Server.serve(routes).provide(
        serverLayer,
        Client.default,
        TypeSafeAI.Client.live,
        WordGenerator.live,
      )
