package forex.services.rates.interpreters

import cats.Monad
import cats.data.EitherT
import cats.effect.Sync
import cats.effect.concurrent.Semaphore
import cats.implicits._
import forex.domain.Rate
import forex.domain.Rate.Pair
import forex.http.rates.Protocol.GetApiResponse
import forex.services.Repo
import forex.services.rates.errors.Error
import forex.services.rates.errors.Error.UnexpectedError
import forex.services.rates.interpreters.OneFrameLive.isExpired
import forex.services.rates.{ errors, Algebra }
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client
import org.http4s.{ Header, Headers, Request, Uri }

import java.time.{ Duration, OffsetDateTime }

class OneFrameLive[F[_]: Monad](sourceUrl: String,
                                refreshRate: Long,
                                lock: Semaphore[F],
                                repo: Repo[F],
                                client: Request[F] => F[Either[Error, List[GetApiResponse]]])
    extends Algebra[F] {

  val oneApiRequest: Either[Error, Request[F]] = Uri
    .fromString(
      s"$sourceUrl/rates?pair=AUDCAD&pair=AUDCHF&pair=AUDEUR&pair=AUDGBP&pair=AUDNZD&pair=AUDJPY&pair=AUDSGD&pair=AUDUSD&pair=CADAUD&pair=CADCHF&pair=CADEUR&pair=CADGBP&pair=CADNZD&pair=CADJPY&pair=CADSGD&pair=CADUSD&pair=CHFAUD&pair=CHFCAD&pair=CHFEUR&pair=CHFGBP&pair=CHFNZD&pair=CHFJPY&pair=CHFSGD&pair=CHFUSD&pair=EURAUD&pair=EURCAD&pair=EURCHF&pair=EURGBP&pair=EURNZD&pair=EURJPY&pair=EURSGD&pair=EURUSD&pair=GBPAUD&pair=GBPCAD&pair=GBPCHF&pair=GBPEUR&pair=GBPNZD&pair=GBPJPY&pair=GBPSGD&pair=GBPUSD&pair=NZDAUD&pair=NZDCAD&pair=NZDCHF&pair=NZDEUR&pair=NZDGBP&pair=NZDJPY&pair=NZDSGD&pair=NZDUSD&pair=JPYAUD&pair=JPYCAD&pair=JPYCHF&pair=JPYEUR&pair=JPYGBP&pair=JPYNZD&pair=JPYSGD&pair=JPYUSD&pair=SGDAUD&pair=SGDCAD&pair=SGDCHF&pair=SGDEUR&pair=SGDGBP&pair=SGDNZD&pair=SGDJPY&pair=SGDUSD&pair=USDAUD&pair=USDCAD&pair=USDCHF&pair=USDEUR&pair=USDGBP&pair=USDNZD&pair=USDJPY&pair=USDSGD"
    )
    .fold(
      _ => Left(Error.UnexpectedError("Cannot parse one api url")),
      p => Right(Request(uri = p, headers = Headers(List(Header("token", "10dc303535874aeccc86a8251e6992f5")))))
    )

  def shouldUpdate(r: Option[Rate]): Boolean =
    r.fold(true)(p => isExpired(p.timestamp.value, OffsetDateTime.now(), refreshRate))

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
      _ <- EitherT.right(repo.put(a))
    } yield ()

  def process(pair: Rate.Pair): F[Either[Error, Rate]] =
    (for {
      a <- EitherT.right(repo.get(pair))
      b <- EitherT.right(Monad[F].pure(shouldUpdate(a)))
      _ <- if (b) updateState() else EitherT.right(Monad[F].unit)
      c <- EitherT.right(repo.get(pair))
      d <- EitherT {
            Monad[F].pure(
              c match {
                case Some(value) => Right(value).withLeft[Error]
                case None        => Left(UnexpectedError(s"Still cannot get pair $pair"))
              }
            )
          }
    } yield d).value

  override def get(pair: Rate.Pair): F[Either[errors.Error, Rate]] =
    for {
      _ <- lock.acquire
      a <- process(pair)
      _ <- lock.release
    } yield a

}

object OneFrameLive {

  private def isExpired(in: OffsetDateTime, now: OffsetDateTime, refreshRate: Long): Boolean =
    Duration.between(in, now).getSeconds > refreshRate

  def apply[F[_]: Sync](sourceUrl: String,
                        refreshRate: Long,
                        lock: Semaphore[F],
                        repo: Repo[F],
                        client: Request[F] => F[Either[Error, List[GetApiResponse]]]): OneFrameLive[F] =
    new OneFrameLive(sourceUrl, refreshRate, lock, repo, client)

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
