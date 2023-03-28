// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package navigate.web.server.logging

import navigate.model.enums.ServerLogLevel

import java.time.Instant

trait LogMessageBuilder[O] {
  def build(l: ServerLogLevel, t: Instant, msg: String): O
}

object LogMessageBuilder {
  def apply[O](using m: LogMessageBuilder[O]): LogMessageBuilder[O] = m
}