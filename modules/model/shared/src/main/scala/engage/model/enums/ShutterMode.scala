// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package engage.model.enums

import cats.Eq
import lucuma.core.util.Enumerated

trait ShutterMode extends Product with Serializable

object ShutterMode {
  case object FullyOpen extends ShutterMode
  case object Tracking  extends ShutterMode

  implicit val shutterModeEq: Eq[ShutterMode] = Eq.instance {
    case (FullyOpen, FullyOpen) => true
    case (Tracking, Tracking)   => true
    case _                      => false
  }

  implicit val shutterModeEnum: Enumerated[ShutterMode] =
    Enumerated.from(FullyOpen, Tracking).withTag(_ => "ShutterMode")
}
