// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package engage.web.server.http4s

import cats.syntax.all.*
import edu.gemini.grackle.Value
import edu.gemini.grackle.Value.{FloatValue, IntValue, ObjectValue, StringValue}
import lucuma.core.math.{
  Angle,
  Declination,
  Epoch,
  HourAngle,
  Parallax,
  ProperMotion,
  RadialVelocity,
  RightAscension,
  Wavelength
}
import lucuma.core.math.units.{CentimetersPerSecond, MetersPerSecond}
import coulomb.*
import coulomb.ops.algebra.spire.all.given
import coulomb.policy.spire.standard.given
import coulomb.syntax.*
import coulomb.units.si.*
import coulomb.units.si.given
import coulomb.units.si.prefixes.*
import lucuma.core.math.skycalc.solver.HourAngleSolver
import lucuma.core.model.NonNegDuration

import java.time.Duration

trait GrackleParsers {

  def bigDecimalValue(v: Value): Option[BigDecimal] =
    v match {
      case IntValue(r)    => BigDecimal(r).some
      case FloatValue(r)  => BigDecimal(r).some
      case StringValue(r) => Either.catchNonFatal(BigDecimal(r)).toOption
      case _              => none
    }

  def longValue(v: Value): Option[Long] =
    v match {
      case IntValue(r)    => r.toLong.some
      case FloatValue(r)  => Either.catchNonFatal(r.toLong).toOption
      case StringValue(r) => Either.catchNonFatal(r.toLong).toOption
      case _              => none
    }

  def parseWavelength(units: List[(String, Value)]): Option[Wavelength] =
    units.find(_._2 != Value.AbsentValue) match {
      case Some(("picometers", IntValue(n))) =>
        Wavelength.intPicometers.getOption(n)
      case Some(("angstroms", n))            =>
        bigDecimalValue(n).flatMap(Wavelength.decimalAngstroms.getOption)
      case Some(("nanometers", n))           =>
        bigDecimalValue(n).flatMap(Wavelength.decimalNanometers.getOption)
      case Some(("micrometers", n))          =>
        bigDecimalValue(n).flatMap(Wavelength.decimalMicrometers.getOption)
      case _                                 => None
    }

  def parseRadialVelocity(units: List[(String, Value)]): Option[RadialVelocity] =
    units.find(_._2 != Value.AbsentValue) match {
      case Some(("centimetersPerSecond", IntValue(n))) =>
        RadialVelocity(n.withUnit[CentimetersPerSecond].toValue[BigDecimal].toUnit[MetersPerSecond])
      case Some(("metersPerSecond", n))                =>
        bigDecimalValue(n).flatMap(v => RadialVelocity(v.withUnit[MetersPerSecond]))
      case Some(("kilometersPerSecond", n))            =>
        bigDecimalValue(n).flatMap(v => RadialVelocity.kilometerspersecond.getOption(v))
      case _                                           => None
    }

  def parseRightAscension(units: List[(String, Value)]): Option[RightAscension] =
    units.find(_._2 != Value.AbsentValue) match {
      case Some(("microarcseconds", n))  =>
        longValue(n).map(Angle.fromMicroarcseconds).flatMap(RightAscension.fromAngleExact.getOption)
      case Some(("microseconds", n))     =>
        longValue(n).map(HourAngle.fromMicroseconds).flatMap(RightAscension.fromHourAngle.getOption)
      case Some(("degrees", n))          =>
        bigDecimalValue(n)
          .map(Angle.fromBigDecimalDegrees)
          .flatMap(RightAscension.fromAngleExact.getOption)
      case Some(("hours", n))            =>
        bigDecimalValue(n)
          .map(x => HourAngle.fromDoubleHours(x.toDouble))
          .flatMap(RightAscension.fromHourAngle.getOption)
      case Some(("hms", StringValue(s))) =>
        HourAngle.fromStringHMS.getOption(s).flatMap(RightAscension.fromHourAngle.getOption)
      case _                             => None
    }

  def parseDeclination(units: List[(String, Value)]): Option[Declination] =
    units.find(_._2 != Value.AbsentValue) match {
      case Some(("microarcseconds", n))  =>
        longValue(n).map(Angle.fromMicroarcseconds).flatMap(Declination.fromAngle.getOption)
      case Some(("degrees", n))          =>
        bigDecimalValue(n).map(Angle.fromBigDecimalDegrees).flatMap(Declination.fromAngle.getOption)
      case Some(("dms", StringValue(s))) => Declination.fromStringSignedDMS.getOption(s)
      case _                             => None
    }

  def parseEpoch(str: String): Option[Epoch] = Epoch.fromString.getOption(str)

  def parseRightAscensionVelocity(units: List[(String, Value)]): Option[ProperMotion.RA] =
    units.find(_._2 != Value.AbsentValue) match {
      case Some(("microarcsecondsPerYear", n)) =>
        longValue(n).flatMap(ProperMotion.RA.microarcsecondsPerYear.getOption)
      case Some(("milliarcsecondsPerYear", n)) =>
        bigDecimalValue(n).map(ProperMotion.RA.milliarcsecondsPerYear.reverseGet)
      case _                                   => None
    }

  def parseDeclinationVelocity(units: List[(String, Value)]): Option[ProperMotion.Dec] =
    units.find(_._2 != Value.AbsentValue) match {
      case Some(("microarcsecondsPerYear", n)) =>
        longValue(n).flatMap(ProperMotion.Dec.microarcsecondsPerYear.getOption)
      case Some(("milliarcsecondsPerYear", n)) =>
        bigDecimalValue(n).map(ProperMotion.Dec.milliarcsecondsPerYear.reverseGet)
      case _                                   => None
    }

  def parseProperMotion(l: List[(String, Value)]): Option[ProperMotion] = for {
    dra  <- l.collectFirst { case ("ra", ObjectValue(dral)) =>
              parseRightAscensionVelocity(dral)
            }.flatten
    ddec <- l.collectFirst { case ("dec", ObjectValue(ddecl)) =>
              parseDeclinationVelocity(ddecl)
            }.flatten
  } yield ProperMotion(dra, ddec)

  def parseParallax(units: List[(String, Value)]): Option[Parallax] =
    units.find(_._2 != Value.AbsentValue) match {
      case Some(("microarcseconds", n)) =>
        longValue(n).map(Parallax.fromMicroarcseconds)
      case Some(("milliarcseconds", n)) =>
        bigDecimalValue(n).map(Parallax.milliarcseconds.reverseGet)
      case _                            => None
    }

}
