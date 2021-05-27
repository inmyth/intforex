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
  val pair2: Pair                           = Pair(Currency.fromString("JPY"), Currency.fromString("SGD"))

  def createResponse(t: Timestamp) = List(
    GetApiResponse(
      pair.from,
      pair.to,
      Price(BigDecimal(10)),
      t
    ),
    GetApiResponse(
      pair2.from,
      pair2.to,
      Price(BigDecimal(10)),
      t
    ),
  )

  def createDummy[F[_]: Sync: Concurrent](sourceUrl: String,
                                          refresh: Long,
                                          client: Request[F] => F[Either[Error, List[GetApiResponse]]],
                                          state: F[Ref[F, Map[Pair, Rate]]]): F[Algebra[F]] =
    for {
      s <- state
      lock <- Semaphore[F](1)
    } yield OneFrameLive(sourceUrl, refresh, lock, s, client)

  def createDummyWithEmptyCache[F[_]: Sync: Concurrent](sourceUrl: String,
                                                        refresh: Long,
                                                        client: Request[F] => F[Either[Error, List[GetApiResponse]]],
  ): F[Algebra[F]] =
    for {
      s <- Ref.of[F, Map[Pair, Rate]](Map.empty)
      lock <- Semaphore[F](1)
    } yield OneFrameLive(sourceUrl, refresh, lock, s, client)

  def dummyGoodClient[F[_]: Sync]: Request[F] => F[Either[Error, List[GetApiResponse]]] =
    _ => Sync[F].pure(createResponse(Timestamp.now).asRight[Error])

  def dummyBadClient[F[_]: Sync]: Request[F] => F[Either[Error, List[GetApiResponse]]] =
    _ => Sync[F].pure(Left(Error.UnexpectedError("na")))

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
        a <- Sync[F].pure(createResponse(Timestamp.now).asRight[Error])
      } yield a

  behavior of "OneFrameLive get implementation"

  it should "return result if client runs correctly" in {
    val refresh = 1L
    val x = (for {
      dummy <- createDummyWithEmptyCache[IO]("", refresh, dummyGoodClient)
      a <- dummy.get(pair)
    } yield a).unsafeRunSync()
    x.isRight shouldBe true
    x.map(p => p.pair shouldBe pair)
  }

  it should "return left if error occurs in client" in {
    val refresh = 1L
    val x = (for {
      dummy <- createDummyWithEmptyCache[IO]("", refresh, dummyBadClient)
      a <- dummy.get(pair)
    } yield a).unsafeRunSync()
    x.isLeft shouldBe true
  }

  it should "call OneFrame api once each refresh period (starting from 0)" in {
    val refresh = 1L
    val x = (for {
      a <- Ref.of[IO, Int](0)
      dummy = createDummyWithEmptyCache[IO]("", refresh, counterClient(a))
      b <- dummy
      _ <- b.get(pair)
      _ <- Timer[IO].sleep((refresh * 2) second)
      _ <- b.get(pair)
      c <- a.get
    } yield c).unsafeRunSync()
    x shouldBe 2
  }

  it should "call OneFrame api only once within refresh period even if there are many requests from client" in {
    val refresh  = 1L
    val nRequest = 5
    val x = (for {
      a <- Ref.of[IO, Int](0)
      dummy = createDummyWithEmptyCache[IO]("", refresh, counterClient(a))
      b <- dummy
      _ <- (0 until nRequest)
            .map(_ => b.get(pair))
            .toList
            .sequence
      c <- a.get
    } yield c).unsafeRunSync()
    x shouldBe 1
  }

  it should "not call OneFrame api when cache is available and valid" in {
    val refresh = 1L
    val x = (for {
      a <- Ref.of[IO, Int](0)
      cache = Ref.of[IO, Map[Pair, Rate]](Map(pair -> Rate(pair, Price(BigDecimal(10)), Timestamp.now)))
      b <- createDummy[IO]("", refresh, counterClient(a), cache)
      _ <- b.get(pair)
      c <- a.get
    } yield c).unsafeRunSync()
    x shouldBe 0
  }

  it should "return a second rate without extra call to OneFrame if it's done within refresh rate" in {
    val refresh = 1L
    val x = (for {
      a <- Ref.of[IO, Int](0)
      dummy = createDummyWithEmptyCache[IO]("", refresh, counterClient(a))
      b <- dummy
      _ <- b.get(pair)
      c <- b.get(pair2)
      d <- a.get
    } yield (c, d)).unsafeRunSync()
    x._1.isRight shouldBe true
    x._1.map(p => p.pair shouldBe pair2)
    x._2 shouldBe 1
  }

}
