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

  def updateState(): EitherT[F, Error, Unit] =
    for {
      a <- fetchRates()
      _ <- EitherT.liftF[F, Error, Unit](state.set(a))
    } yield ()

  def process(pair: Rate.Pair): F[Either[Error, Rate]] =
    (for {

      a <- EitherT.right(shouldUpdate(pair))
      _ = Sync[F].delay(println(a))
      _ <- if (a) updateState() else EitherT.right(Sync[F].unit)
      c <- EitherT {
            state.get.map(
              _.get(pair) match {
                case Some(value) => Right(value).withLeft[Error]
                case None        => Left(Error.UnexpectedError(s"Still cannot get pair $pair from state"))
              }
            )
          }
    } yield c).value

  override def get(pair: Rate.Pair): F[Either[errors.Error, Rate]] =
    for {
      _ <- lock.acquire
      a <- process(pair)
      _ <- lock.release
    } yield a

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
            e => Left(Error.UnexpectedError(e.getMessage)),
            q => Right(q)
        )
      )

}
