package forex.services.rates

import cats.Applicative
import cats.effect.Sync
import cats.effect.concurrent.Semaphore
import forex.http.rates.Protocol.GetApiResponse
import forex.services.Repo
import forex.services.rates.errors.Error
import forex.services.rates.interpreters._
import org.http4s.Request

object Interpreters {
  def dummy[F[_]: Applicative]: Algebra[F] = new OneFrameDummy[F]()

  def live[F[_]: Sync](sourceUrl: String,
                       refreshRate: Long,
                       lock: Semaphore[F],
                       repo: Repo[F],
                       client: Request[F] => F[Either[Error, List[GetApiResponse]]]): Algebra[F] =
    new OneFrameLive[F](sourceUrl, refreshRate, lock, repo, client)
}
