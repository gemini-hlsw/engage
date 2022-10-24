// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package engage.server.epicsdata

import cats.Eq
import lucuma.core.util.Enumerated

trait BinaryOnOff extends Product with Serializable

object BinaryOnOff {
  case object Off extends BinaryOnOff
  case object On  extends BinaryOnOff

  implicit val onoffEnum: Enumerated[BinaryOnOff] = Enumerated.from(Off, On).withTag(_ => "BinaryOnOff")

  implicit val onoffEq: Eq[BinaryOnOff] = Eq.instance {
    case (Off, Off) => true
    case (On, On)   => true
    case _          => false
  }
}
