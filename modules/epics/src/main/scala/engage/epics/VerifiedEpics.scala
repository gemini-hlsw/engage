// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package engage.epics

import cats.effect.std.Dispatcher
import cats.effect.{ Async, Concurrent, Resource }
import cats.syntax.all._
import cats.{ Applicative, FlatMap, Monad, Parallel }
import mouse.boolean._
import fs2.Stream
import engage.epics.Channel.StreamEvent
import engage.epics.EpicsSystem.TelltaleChannel

import scala.concurrent.duration.FiniteDuration

/**
 * <code>VerifiedEpics</code> keeps a program that involves access to EPICS channels, together with
 * a list of the channels involved. Calling the method <code>verifiedRun</code> on a
 * <code>VerifiedEpics</code> instance will produce a program that adds a verification of all the
 * channels involved before executing the original program. Several constructors and combiners are
 * provided.
 */
object VerifiedEpics {

  trait ChannelTracker[F[_], A] {
    val systems: Map[TelltaleChannel[F], Set[RemoteChannel[F]]]
    val run: A
  }

  case class Const[F[_], A](v: A) extends ChannelTracker[F, A] {
    override val systems: Map[TelltaleChannel[F], Set[RemoteChannel[F]]] = Map.empty
    override val run: A                                                  = v
  }

  case class Apply[F[_], A, B](fa: ChannelTracker[F, A], fab: ChannelTracker[F, A => B])
      extends ChannelTracker[F, B] {
    override val systems: Map[TelltaleChannel[F], Set[RemoteChannel[F]]] =
      merge(fa.systems, fab.systems)
    override val run: B                                                  = fab.run(fa.run)
  }

  case class Bind[F[_], A, B](fa: ChannelTracker[F, A], fafb: A => ChannelTracker[F, B])
      extends ChannelTracker[F, B] {
    private val b                                                        = fafb(fa.run)
    override val systems: Map[TelltaleChannel[F], Set[RemoteChannel[F]]] =
      merge(fa.systems, b.systems)
    override val run: B                                                  = b.run
  }

  def merge[F[_]](
    m1: Map[TelltaleChannel[F], Set[RemoteChannel[F]]],
    m2: Map[TelltaleChannel[F], Set[RemoteChannel[F]]]
  ): Map[TelltaleChannel[F], Set[RemoteChannel[F]]] =
    (m1.toList ++ m2.toList).groupMap(_._1)(_._2).view.mapValues(_.reduce(_ ++ _)).toMap

  def pure[F[_], A](v: A): ChannelTracker[F, A] = Const(v)
  def unit[F[_]]: ChannelTracker[F, Unit]       = pure[F, Unit](())

  implicit class Ops[F[_], A](v: ChannelTracker[F, A]) extends AnyRef {
    def ap[B](ff: ChannelTracker[F, A => B]): ChannelTracker[F, B] = Apply(v, ff)
    def map[B](ff: A => B): ChannelTracker[F, B]                   = ap(pure(ff))
  }

  type VerifiedEpics[F[_], G[_], A] = ChannelTracker[F, G[A]]

  case class ApplyF[F[_], G[_]: FlatMap, A, B](
    fa:  VerifiedEpics[F, G, A],
    fab: VerifiedEpics[F, G, A => B]
  ) extends VerifiedEpics[F, G, B] {
    override val systems: Map[TelltaleChannel[F], Set[RemoteChannel[F]]] =
      merge(fa.systems, fab.systems)
    override val run: G[B]                                               = fa.run.flatMap { a =>
      fab.run.map { fab =>
        fab(a)
      }
    }
  }

  case class BindF[F[_], G[_]: FlatMap, A, B](
    fa:   VerifiedEpics[F, G, A],
    fafb: G[A] => ChannelTracker[F, A => G[B]]
  ) extends VerifiedEpics[F, G, B] {
    private val fb                                                       = fafb(fa.run)
    override val systems: Map[TelltaleChannel[F], Set[RemoteChannel[F]]] =
      merge(fa.systems, fb.systems)
    override val run: G[B]                                               = fa.run.flatMap(fb.run)
  }

  case class IfF[F[_], G[_]: FlatMap, A](
    cond:     G[Boolean],
    trueVal:  VerifiedEpics[F, G, A],
    falseVal: VerifiedEpics[F, G, A]
  ) extends VerifiedEpics[F, G, A] {
    override val systems: Map[TelltaleChannel[F], Set[RemoteChannel[F]]] =
      merge(trueVal.systems, falseVal.systems)
    override val run: G[A]                                               = cond.flatMap(_.fold(trueVal.run, falseVal.run))
  }

  case class Get[F[_], A](tt: TelltaleChannel[F], ch: Channel[F, A])
      extends VerifiedEpics[F, F, A] {
    override val systems: Map[TelltaleChannel[F], Set[RemoteChannel[F]]] = Map(tt -> Set(ch))
    override val run: F[A]                                               = ch.get
  }

  case class Put[F[_]: FlatMap, A](tt: TelltaleChannel[F], ch: Channel[F, A], fa: F[A])
      extends VerifiedEpics[F, F, Unit] {
    override val systems: Map[TelltaleChannel[F], Set[RemoteChannel[F]]] = Map(tt -> Set(ch))
    override val run: F[Unit]                                            = fa.flatMap(ch.put)
  }

  case class EventStream[F[_]: Dispatcher: Concurrent, A](tt: TelltaleChannel[F], ch: Channel[F, A])
      extends VerifiedEpics[F, Resource[F, *], Stream[F, StreamEvent[A]]] {
    override val systems: Map[TelltaleChannel[F], Set[RemoteChannel[F]]] = Map(tt -> Set(ch))
    override val run: Resource[F, Stream[F, StreamEvent[A]]]             = ch.eventStream
  }

  def pureF[F[_], G[_]: Applicative, A](v: A): VerifiedEpics[F, G, A]                         = pure[F, G[A]](v.pure[G])
  def unit[F[_], G[_]: Applicative]: VerifiedEpics[F, G, Unit]                                =
    pure[F, G[Unit]](Applicative[G].unit)
  def readChannel[F[_], A](tt: TelltaleChannel[F], ch: Channel[F, A]): VerifiedEpics[F, F, A] =
    Get(tt, ch)
  def writeChannel[F[_]: FlatMap, A](tt: TelltaleChannel[F], ch: Channel[F, A])(
    fa:                                  F[A]
  ): VerifiedEpics[F, F, Unit] = Put(tt, ch, fa)
  def eventStream[F[_]: Dispatcher: Concurrent, A](
    tt: TelltaleChannel[F],
    ch: Channel[F, A]
  ): VerifiedEpics[F, Resource[F, *], Stream[F, StreamEvent[A]]] = EventStream(tt, ch)
  def ifF[F[_], G[_]: FlatMap, A](cond: G[Boolean])(trueVal: => VerifiedEpics[F, G, A])(
    falseVal:                           => VerifiedEpics[F, G, A]
  ): VerifiedEpics[F, G, A] = IfF(cond, trueVal, falseVal)

  implicit class OpsF[F[_], G[_]: Monad, A](v: VerifiedEpics[F, G, A]) {
    def ap[B](ff: VerifiedEpics[F, G, A => B]): VerifiedEpics[F, G, B] = ApplyF[F, G, A, B](v, ff)

    def flatMap[B](ff: G[A] => VerifiedEpics[F, G, B]): VerifiedEpics[F, G, B] =
      new ChannelTracker[F, G[B]] {
        private val fb                                                       = ff(v.run)
        override val systems: Map[TelltaleChannel[F], Set[RemoteChannel[F]]] =
          merge(v.systems, fb.systems)
        override val run: G[B]                                               = v.run.flatMap(_ => fb.run)
      }

    def productR[B](b: VerifiedEpics[F, G, B]): VerifiedEpics[F, G, B] = flatMap(_ => b)

    def *>[B](b: VerifiedEpics[F, G, B]): VerifiedEpics[F, G, B] = productR(b)

  }

  implicit class VerifiedRun[F[_]: Async: Parallel, A](v: VerifiedEpics[F, F, A]) {
    def verifiedRun(connectionTimeout: FiniteDuration): F[A] =
      v.systems
        .map { case (k, v) => EpicsSystem(k, v) }
        .map(_.connectionCheck(connectionTimeout))
        .toList
        .parSequence *> v.run
  }

}
