/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.scaladsl

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import akka.Done

object OffsetStore {
  sealed trait Strategy
  case object NoOffsetStorage extends Strategy
  case object AtMostOnce extends Strategy
  final case class AtLeastOnce(afterNumberOfEvents: Int, orAfterDuration: FiniteDuration) extends Strategy
  case object OnceAnOnlyOnce extends Strategy

  private val _noOffsetStore = new OffsetStore[Any] {
    override def readOffset(): Future[Option[Any]] = Future.successful(None)
    override def saveOffset(offset: Any): Future[Done] = Future.successful(Done)
  }

  def noOffsetStore[Offset]: OffsetStore[Offset] =
    _noOffsetStore.asInstanceOf[OffsetStore[Offset]]

}

trait OffsetStore[Offset] {
  def readOffset(): Future[Option[Offset]]
  def saveOffset(offset: Offset): Future[Done]
}

trait OffsetManagedByProjectionHandler[Offset] {
  def readOffset(): Future[Option[Offset]]
  def setCurrentOffset(offset: Offset): Unit
}