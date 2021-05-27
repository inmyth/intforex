package forex.services.rates.interpreters

import cats.effect.concurrent.{ Ref, Semaphore }
import cats.effect.{ Concurrent, ContextShift, IO, Sync, Timer }
import cats.implicits._
import forex.domain.Rate.Pair
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.http.rates.Protocol.GetApiResponse
import forex.services.rates.Algebra
import forex.services.rates.errors.Error
import org.http4s.Request
import org.scalatest.PrivateMethodTester
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

import java.time.OffsetDateTime
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationLong

class OneFrameLiveTest extends AnyFlatSpec with PrivateMethodTester {

  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val contextShift: ContextShift[IO]     = IO.contextShift(executionContext)

  implicit val timer: cats.effect.Timer[IO] = IO.timer(executionContext)
  val pair: Pair                            = Pair(Currency.fromString("USD"), Currency.fromString("CAD"))

  def createDummy[F[_]: Sync: Concurrent](sourceUrl: String,
                                          refresh: Long,
                                          client: Request[F] => F[Either[Error, List[GetApiResponse]]]): F[Algebra[F]] =
    for {
      state <- Ref.of[F, Map[Pair, Rate]](Map.empty)
      lock <- Semaphore[F](1)
    } yield OneFrameLive(sourceUrl, refresh, lock, state, client)

  behavior of "isExpired"
  it should "be true when two OffsetDateTime objects differ more than refresh and false if less" in {
    val refresh   = 5L
    val fun       = PrivateMethod[Boolean](Symbol("isExpired"))
    val inExpired = OffsetDateTime.now().minusSeconds(refresh + 10L)
    val now1      = OffsetDateTime.now()
    val inGood    = OffsetDateTime.now().minusSeconds(refresh - 1L)
    val now2      = OffsetDateTime.now()
    OneFrameLive invokePrivate fun(inExpired, now1, refresh) shouldBe true
    OneFrameLive invokePrivate fun(inGood, now2, refresh) shouldBe false
  }

  def counterClient[F[_]: Sync](
      counter: Ref[F, Int]
  ): Request[F] => F[Either[Error, List[GetApiResponse]]] =
    _ =>
      for {
        _ <- counter.update(_ + 1)
        a <- Sync[F].pure(
              List(
                GetApiResponse(
                  pair.from,
                  pair.to,
                  Price(BigDecimal(10)),
                  Timestamp.now
                )
              ).asRight[Error]
            )
      } yield a

  behavior of "OneFrameLive get implementation"

  it should "call OneFrame api once each refresh period (starting from 0)" in {
    val refresh = 1L
    val x = (for {
      a <- Ref.of[IO, Int](0)
      dummy = createDummy[IO]("", refresh, counterClient[IO](a))
      b <- dummy
      _ <- b.get(pair)
      _ <- Timer[IO].sleep((refresh * 2) second)
      _ <- b.get(pair)
      c <- a.get
    } yield c).unsafeRunSync()
    assert(x == 2)
  }
}
