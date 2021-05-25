package forex.http
package rates

import forex.domain.Currency.show
import forex.domain.Rate.Pair
import forex.domain._
import io.circe._
import io.circe.generic.decoding.DerivedDecoder.deriveDecoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{ deriveConfiguredDecoder, deriveConfiguredEncoder }
import io.circe.generic.semiauto.deriveDecoder

object Protocol {

  implicit val configuration: Configuration = Configuration.default.withSnakeCaseMemberNames

  final case class GetApiRequest(
      from: Currency,
      to: Currency
  )

  final case class GetApiResponse(
      from: Currency,
      to: Currency,
      price: Price,
      timestamp: Timestamp
  )

  implicit val currencyEncoder: Encoder[Currency] =
    Encoder.instance[Currency] { show.show _ andThen Json.fromString }

  implicit val pairEncoder: Encoder[Pair] =
    deriveConfiguredEncoder[Pair]

  implicit val rateEncoder: Encoder[Rate] =
    deriveConfiguredEncoder[Rate]

  implicit val responseEncoder: Encoder[GetApiResponse] =
    deriveConfiguredEncoder[GetApiResponse]

//  implicit val responseDecoder: Decoder[GetApiResponse] = deriveConfiguredDecoder[GetApiResponse]

  implicit val responseDecoder: Decoder[GetApiResponse] = (c: HCursor) =>
    for {
      from <- c.downField("from").as[Currency]
      to <- c.downField("to").as[Currency]
      price <- c.downField("price").as[Price]
      ts <- c.downField("time_stamp").as[Timestamp]
    } yield {
      GetApiResponse(from, to, price, ts)
  }
}
