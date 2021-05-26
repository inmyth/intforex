package forex.services.rates.interpreters

import cats.implicits._
import cats.data.EitherT
import cats.effect.Sync
import cats.effect.concurrent.{ Ref, Semaphore }
import forex.domain.Rate
import forex.domain.Rate.Pair
import forex.http.rates.Protocol.GetApiResponse
import forex.services.rates.errors.Error
import org.http4s.client.Client
import org.http4s.{ Header, Headers, Request, Uri }
import org.http4s.circe.CirceEntityDecoder._

import java.time.{ Duration, OffsetDateTime }

class OneFrameLive[F[_]: Sync](
    lock: Semaphore[F],
    state: Ref[F, Map[Rate.Pair, Rate]],
    client: Client[F]
) {
  val refreshRate = 5L

  val oneApiRequest: Either[Error, Request[F]] = Uri
    .fromString("https://localhost:8080/rates?pair=USDCAD")
    .fold(
      _ => Left(Error.UnexpectedError("Cannot parse one api url")),
      p => Right(Request(uri = p, headers = Headers(List(Header("token", "10dc303535874aeccc86a8251e6992f5")))))
    )

  def isExpired(in: OffsetDateTime, now: OffsetDateTime): Boolean = Duration.between(in, now).getSeconds > refreshRate

  def shouldUpdate(k: Pair): F[Boolean] =
    state.get.map(_.get(k).fold(true)(p => isExpired(p.timestamp.value, OffsetDateTime.now())))

  def fetchRates(): EitherT[F, Error, Map[Pair, Rate]] =
    for {
      a <- EitherT.fromEither[F](oneApiRequest)
      b <- EitherT.liftF[F, Error, List[GetApiResponse]](client.expect[List[GetApiResponse]](a))
      c = b
        .map(p => {
          val k = Rate.Pair(p.from, p.to)
          k -> Rate(k, p.price, p.timestamp)
        })
        .toMap
    } yield c

}

object OneFrameLive {

  def liveClient[F[_]: Sync](client: Client[F])(request: Request[F]) =
    client.expect[List[GetApiResponse]](request).attempt
}
