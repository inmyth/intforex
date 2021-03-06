package forex.http
package rates

import cats.implicits._
import cats.effect.Sync
import forex.programs.RatesProgram
import forex.programs.rates.{ errors, Protocol => RatesProgramProtocol }
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

class RatesHttpRoutes[F[_]: Sync](rates: RatesProgram[F]) extends Http4sDsl[F] {

  import Converters._, QueryParams._, Protocol._

  private[http] val prefixPath = "/rates"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root :? FromQueryParam(from) +& ToQueryParam(to) =>
      (from, to) match {
        case (Some(f), Some(t)) =>
          rates
            .get(RatesProgramProtocol.GetRatesRequest(f, t))
            .flatMap(Sync[F].fromEither)
            .flatMap { rate =>
              Ok(rate.asGetApiResponse)
            }
            .recoverWith {
              case errors.Error.RateLookupFailed(msg) => BadRequest(msg)
              case errors.Error.ServerError(msg)      => InternalServerError(msg)
              case _                                  => InternalServerError("Unknown server error")
            }

        case _ => BadRequest("Parameters missing / unrecognized")
      }

  }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}
