package forex.http.rates

import forex.domain.Currency
import org.http4s.QueryParamDecoder
import org.http4s.dsl.impl.QueryParamDecoderMatcher

import scala.util.Try

object QueryParams {

  private[http] implicit val currencyQueryParam: QueryParamDecoder[Option[Currency]] =
    QueryParamDecoder[String].map(p => Try(Currency.fromString(p)).toOption)

  object FromQueryParam extends QueryParamDecoderMatcher[Option[Currency]]("from")
  object ToQueryParam extends QueryParamDecoderMatcher[Option[Currency]]("to")

}
