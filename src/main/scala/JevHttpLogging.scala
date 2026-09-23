import com.jamesward.zio_typesafe_ai.TypeSafeAI
import zio.*
import zio.http.{Client as HttpClient}

object JevHttpLogging:
  /** Complete JSON request and canonical response logging, with no HTTP headers. */
  val default: ZLayer[Any, Throwable, TypeSafeAI.Client] =
    HttpClient.default >>>
      TypeSafeAI.Client.live >>>
      TypeSafeAI.Client.observed(TypeSafeAI.ExchangeObserver.logging)
