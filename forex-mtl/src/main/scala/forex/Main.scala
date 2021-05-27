package forex

import cats.effect.concurrent.{ Ref, Semaphore }
import cats.effect.{ ConcurrentEffect, ExitCode, IO, IOApp, Timer }
import cats.implicits._
import forex.config._
import forex.domain.Rate
import forex.domain.Rate.Pair
import forex.http.rates.Protocol.GetApiResponse
import forex.services.rates.errors.Error
import forex.services.rates.interpreters.OneFrameLive
import fs2.Stream
import org.http4s.Request
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.server.blaze.BlazeServerBuilder

import scala.concurrent.{ ExecutionContext, ExecutionContextExecutor }

object Main extends IOApp {
  val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  def runMyApp[F[_]: ConcurrentEffect: Timer]: F[ExitCode] =
    BlazeClientBuilder[F](ec).resource.use { httpClient =>
      (for {
        state <- Ref.of[F, Map[Pair, Rate]](Map.empty)
        lock <- Semaphore[F](1)
        client = OneFrameLive.liveClient[F](httpClient)(_)
      } yield (state, lock, client))
        .flatMap(p => new Application[F].stream(ec, p._2, p._1, p._3).compile.drain.as(ExitCode.Success))
    }

  override def run(args: List[String]): IO[ExitCode] = runMyApp[IO]

}

class Application[F[_]: ConcurrentEffect: Timer] {

  def stream(ec: ExecutionContext,
             lock: Semaphore[F],
             state: Ref[F, Map[Pair, Rate]],
             client: Request[F] => F[Either[Error, List[GetApiResponse]]]): Stream[F, Unit] =
    for {
      config <- Config.stream("app")
      module = new Module[F](config, lock, state, client)
      _ <- BlazeServerBuilder[F](ec)
            .bindHttp(config.http.port, config.http.host)
            .withHttpApp(module.httpApp)
            .serve
    } yield ()

}
