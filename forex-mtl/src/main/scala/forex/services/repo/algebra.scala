package forex.services.repo

import forex.domain.Rate

trait Algebra[F[_]] {

  def get(pair: Rate.Pair): F[Option[Rate]]

  def put(data: Map[Rate.Pair, Rate]): F[Unit]
}
