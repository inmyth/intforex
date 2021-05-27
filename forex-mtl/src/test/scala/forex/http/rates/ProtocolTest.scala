package forex.http.rates

import forex.http.rates.Protocol.GetApiResponse
import org.scalatest.flatspec.AnyFlatSpec
import io.circe.parser.decode
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

class ProtocolTest extends AnyFlatSpec {
  behavior of "Json decoding of GetApiResponse"

  val okData: String =
    """
      |[
      |    {
      |        "from": "USD",
      |        "to": "EUR",
      |        "price": 0.156795108264556892,
      |        "time_stamp": "2021-05-23T11:02:00.067Z"
      |    },
      |    {
      |        "from": "USD",
      |        "to": "CAD",
      |        "price": 0.388066845941813685,
      |        "time_stamp": "2021-05-23T11:02:00.067Z"
      |    }
      |]
      |""".stripMargin

  val dataBadCurrency: String =
    """
      |[
      |    {
      |        "from": "AAA",
      |        "to": "EUR",
      |        "price": 0.156795108264556892,
      |        "time_stamp": "2021-05-23T11:02:00.067Z"
      |    },
      |    {
      |        "from": "USD",
      |        "to": "CAD",
      |        "price": 0.388066845941813685,
      |        "time_stamp": "2021-05-23T11:02:00.067Z"
      |    }
      |]
      |""".stripMargin

  it should "ok parsing good data" in {
    val x = decode[List[GetApiResponse]](okData)
    x.isRight shouldBe true
  }

  it should "ko parsing json with bad currency" in {
    val x = decode[List[GetApiResponse]](dataBadCurrency)
    x.isLeft shouldBe true
  }

}
