import com.jamesward.zio_decisions.{DecisionModel, Decisions}
import com.jamesward.zio_decisions.typesafe.TypeSafe
import zio.*
import zio.http.{Client as HttpClient}

object JevHttpLogging:
  /** Complete JSON request and canonical response logging, with no HTTP headers. */
  val default: ZLayer[Any, Throwable, DecisionModel] =
    HttpClient.default >>>
      TypeSafe.live >>>
      DecisionModel.observed(Decisions.ExchangeObserver.logging)
