package forex.services.repo.interpreters

import forex.domain.Rate
import forex.services.repo.Algebra

class RemoteCache[F[_]] extends Algebra[F] {

  override def put(data: Map[Rate.Pair, Rate]): F[Unit] = ???

  override def get(pair: Rate.Pair): F[Option[Rate]] = ???
}
