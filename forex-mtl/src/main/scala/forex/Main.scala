package forex

import cats.effect.concurrent.{ Ref, Semaphore }
import cats.effect.{ Blocker, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Timer }
import cats.implicits._
import forex.config._
import forex.domain.Rate
import forex.domain.Rate.Pair
import forex.http.rates.Protocol.GetApiResponse
import forex.services.rates.errors.Error
import forex.services.rates.interpreters.OneFrameLive
import fs2.Stream
import org.http4s.Request
import org.http4s.client.{ Client, JavaNetClientBuilder }
import org.http4s.server.blaze.BlazeServerBuilder

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object Main extends IOApp {
  val ec = scala.concurrent.ExecutionContext.global

  def runMyApp[F[_]: ConcurrentEffect: Timer: ContextShift]: F[ExitCode] = {
    val blockingPool          = Executors.newFixedThreadPool(5)
    val blocker               = Blocker.liftExecutorService(blockingPool)
    val httpClient: Client[F] = JavaNetClientBuilder[F](blocker).create
    for {
      state <- Ref.of[F, Map[Pair, Rate]](Map.empty)
      lock <- Semaphore[F](1)
      client = OneFrameLive.liveClient[F](httpClient)(_)
    } yield new Application[F].stream(ec, lock, state, client).compile.drain.as(ExitCode.Success)
  }.flatten

  override def run(args: List[String]): IO[ExitCode] = runMyApp[IO]

  //  override def run(args: List[String]): IO[ExitCode] =
  //    new Application[IO].stream(executionContext).compile.drain.as(ExitCode.Success)

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
