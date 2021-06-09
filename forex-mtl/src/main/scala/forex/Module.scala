package forex

import cats.effect.concurrent.Semaphore
import cats.effect.{ Concurrent, Timer }
import forex.config.ApplicationConfig
import forex.http.rates.Protocol.GetApiResponse
import forex.http.rates.RatesHttpRoutes
import forex.programs._
import forex.services._
import forex.services.rates.errors.Error
import org.http4s._
import org.http4s.implicits._
import org.http4s.server.middleware.{ AutoSlash, Timeout }

class Module[F[_]: Concurrent: Timer](config: ApplicationConfig,
                                      lock: Semaphore[F],
                                      repo: Repo[F],
                                      client: Request[F] => F[Either[Error, List[GetApiResponse]]]) {

//  private val ratesService: RatesService[F] = RatesServices.dummy[F]

  private val liveService = RatesServices.live[F](
    config.live.url,
    config.live.refresh,
    lock,
    repo,
    client
  )

  //  private val ratesProgram: RatesProgram[F] = RatesProgram[F](ratesService)

  private val ratesProgram: RatesProgram[F] = RatesProgram[F](liveService)

  private val ratesHttpRoutes: HttpRoutes[F] = new RatesHttpRoutes[F](ratesProgram).routes

  type PartialMiddleware = HttpRoutes[F] => HttpRoutes[F]
  type TotalMiddleware   = HttpApp[F] => HttpApp[F]

  private val routesMiddleware: PartialMiddleware = {
    { http: HttpRoutes[F] =>
      AutoSlash(http)
    }
  }

  private val appMiddleware: TotalMiddleware = { http: HttpApp[F] =>
    Timeout(config.http.timeout)(http)
  }

  private val http: HttpRoutes[F] = ratesHttpRoutes

  val httpApp: HttpApp[F] = appMiddleware(routesMiddleware(http).orNotFound)

}
