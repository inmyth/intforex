package forex.services.rates.interpreters

import org.scalatest.flatspec.AnyFlatSpec

import java.time.{ Duration, OffsetDateTime }

class OneFrameLiveTest extends AnyFlatSpec {

  behavior of "isExpired"
  def isExpired(in: OffsetDateTime, now: OffsetDateTime): Boolean = {
    val x = Duration.between(in, now).getSeconds
    x < 0
  }
  it should "return true with expired date" in {

    val d1 = OffsetDateTime.parse("2021-05-23T11:02:00.067Z")
    val d2 = OffsetDateTime.parse("2021-05-23T11:02:04.567Z")
    assert(isExpired(d1, d2))
  }

}
