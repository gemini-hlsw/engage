// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package engage.web.server

import cats.effect.Sync
import cats.syntax.all._
import lucuma.core.enums.Site
import pureconfig._
import pureconfig.error._
import pureconfig.generic.ProductHint
import pureconfig.generic.auto._
import pureconfig.module.catseffect.syntax._
import pureconfig.module.http4s._
import engage.model.config._

package config {
  final case class SiteValueUnknown(site: String)         extends FailureReason {
    def description: String = s"site '$site' invalid"
  }
  final case class ModeValueUnknown(mode: String)         extends FailureReason {
    def description: String = s"mode '$mode' invalid"
  }
  final case class StrategyValueUnknown(strategy: String) extends FailureReason {
    def description: String = s"strategy '$strategy' invalid"
  }
}

package object config {

  implicit val siteReader: ConfigReader[Site] = ConfigReader.fromCursor[Site] { cf =>
    cf.asString.flatMap {
      case "GS" => Site.GS.asRight
      case "GN" => Site.GN.asRight
      case s    => cf.failed(SiteValueUnknown(s))
    }
  }

  implicit val modeReader: ConfigReader[Mode] = ConfigReader.fromCursor[Mode] { cf =>
    cf.asString.flatMap {
      case "production" => Mode.Production.asRight
      case "dev"        => Mode.Development.asRight
      case s            => cf.failed(ModeValueUnknown(s))
    }
  }

  implicit val controlStrategyReader: ConfigReader[ControlStrategy] =
    ConfigReader.fromCursor[ControlStrategy] { cf =>
      cf.asString.flatMap { c =>
        ControlStrategy.fromString(c) match {
          case Some(x) => x.asRight
          case _       => cf.failed(StrategyValueUnknown(c))
        }
      }
    }

  implicit val tlsInfoHint: ProductHint[TLSConfig]                             =
    ProductHint[TLSConfig](ConfigFieldMapping(KebabCase, KebabCase))
  implicit val webServerConfigurationHint: ProductHint[WebServerConfiguration] =
    ProductHint[WebServerConfiguration](ConfigFieldMapping(KebabCase, KebabCase))
  implicit val authenticationConfigHint: ProductHint[AuthenticationConfig]     =
    ProductHint[AuthenticationConfig](ConfigFieldMapping(KebabCase, KebabCase))
  implicit val engageServerHint: ProductHint[EngageEngineConfiguration]        =
    ProductHint[EngageEngineConfiguration](ConfigFieldMapping(KebabCase, KebabCase))
  implicit val systemsControlHint: ProductHint[SystemsControlConfiguration]    =
    ProductHint[SystemsControlConfiguration](ConfigFieldMapping(KebabCase, KebabCase))

  def loadConfiguration[F[_]: Sync](config: ConfigObjectSource): F[EngageConfiguration] =
    config.loadF[F, EngageConfiguration]()

}
