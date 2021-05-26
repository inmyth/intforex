package forex.services.rates.interpreters

import cats.data.EitherT
import cats.effect.Sync
import cats.effect.concurrent.{ Ref, Semaphore }
import cats.implicits._
import forex.domain.Rate
import forex.domain.Rate.Pair
import forex.http.rates.Protocol.GetApiResponse
import forex.services.rates.errors.Error
import forex.services.rates.{ errors, Algebra }
import org.http4s.{ Header, Headers, Request, Uri }

import java.time.{ Duration, OffsetDateTime }
//import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client

class OneFrameLive[F[_]: Sync](sourceUrl: String,
                               refreshRate: Long,
                               lock: Semaphore[F],
                               state: Ref[F, Map[Rate.Pair, Rate]],
                               client: Request[F] => F[Either[Error, List[GetApiResponse]]])
    extends Algebra[F] {

  val oneApiRequest: Error Either Request[F] = Uri
    .fromString(s"$sourceUrl/rates?pair=USDCAD")
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
      b <- EitherT(client(a))
      c = b
        .map(p => {
          val k = Rate.Pair(p.from, p.to)
          k -> Rate(k, p.price, p.timestamp)
        })
        .toMap
    } yield c

  def updateState() =
    for {
      a <- fetchRates()
      _ <- EitherT.liftF[F, Error, Unit](state.set(a))
    } yield ()

  override def get(pair: Rate.Pair): F[Either[errors.Error, Rate]] = {
    for {
      _ <- EitherT.liftF[F, Error, Unit](lock.acquire)
      b <- EitherT.liftF[F, Error, Boolean](shouldUpdate(pair))
      _ <- if (b) updateState() else EitherT.liftF[F, Error, Unit](Sync[F].unit)
      d <- EitherT(
            state.get.map(
              _.get(pair) match {
                case Some(value) => Right(value)
                case None        => Left(Error.UnexpectedError(s"Still cannot get pair $pair"))
              }
            )
          )
      _ <- EitherT.liftF[F, Error, Unit](lock.release)
    } yield d
  }.value
}

object OneFrameLive {

  def apply[F[_]: Sync](sourceUrl: String,
                        refreshRate: Long,
                        lock: Semaphore[F],
                        state: Ref[F, Map[Pair, Rate]],
                        client: Request[F] => F[Either[Error, List[GetApiResponse]]]): OneFrameLive[F] =
    new OneFrameLive(sourceUrl, refreshRate, lock, state, client)

  def liveClient[F[_]: Sync](
      httpClient: Client[F]
  )(request: Request[F]): F[Either[Error, List[GetApiResponse]]] =
    httpClient
      .expect[List[GetApiResponse]](request)
      .attempt
      .map(
        p =>
          p.fold(
            _ => Left(Error.UnexpectedError("Unable to fetch rates from One Api")),
            q => Right(q)
        )
      )

}
