package forex.services.rates

import cats.Applicative
import cats.effect.Sync
import cats.effect.concurrent.{ Ref, Semaphore }
import forex.domain.Rate
import forex.domain.Rate.Pair
import forex.http.rates.Protocol.GetApiResponse
import forex.services.rates.errors.Error
import interpreters._
import org.http4s.Request

object Interpreters {
  def dummy[F[_]: Applicative]: Algebra[F] = new OneFrameDummy[F]()

  def live[F[_]: Sync](sourceUrl: String,
                       refreshRate: Long,
                       lock: Semaphore[F],
                       state: Ref[F, Map[Pair, Rate]],
                       client: Request[F] => F[Either[Error, List[GetApiResponse]]]): Algebra[F] =
    new OneFrameLive[F](sourceUrl, refreshRate, lock, state, client)
}
