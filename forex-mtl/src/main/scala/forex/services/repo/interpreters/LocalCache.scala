package forex.services.repo.interpreters

import cats.Monad
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import forex.domain.Rate
import forex.domain.Rate.Pair
import forex.services.repo.Algebra

class LocalCache[F[_]: Monad](ref: Ref[F, Map[Pair, Rate]]) extends Algebra[F] {

  override def get(pair: Pair): F[Option[Rate]] = ref.get.map(_.get(pair))

  override def put(data: Map[Pair, Rate]): F[Unit] = ref.set(data)

}

object LocalCache {

//  private def make[F[_]: Sync](): F[Ref[F, Map[Pair, Rate]]] = Ref.of[F, Map[Pair, Rate]](Map.empty)

  // DONT PASS F[Ref[F,...]]. PASS Ref[F,...]
  def apply[F[_]: Sync](ref: Ref[F, Map[Pair, Rate]]): LocalCache[F] = new LocalCache(ref)

}
