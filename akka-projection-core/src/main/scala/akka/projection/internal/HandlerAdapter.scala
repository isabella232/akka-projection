/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.internal

import scala.collection.immutable
import scala.compat.java8.FutureConverters._
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

import akka.Done
import akka.annotation.InternalApi
import akka.projection.internal.ActorHandlerInit
import akka.projection.javadsl
import akka.projection.scaladsl

object HandlerAdapter {
  def apply[Envelope](delegate: javadsl.Handler[Envelope]): scaladsl.Handler[Envelope] = {
    delegate match {
      case a: javadsl.ActorHandler[Envelope, Any] @unchecked => new ActorHandlerAdapter[Envelope](a)
      case _                                                 => new HandlerAdapter(delegate)
    }
  }
}

/**
 * INTERNAL API: Adapter from javadsl.Handler to scaladsl.Handler
 */
@InternalApi private[akka] class HandlerAdapter[Envelope] private (delegate: javadsl.Handler[Envelope])
    extends scaladsl.Handler[Envelope] {

  override def process(envelope: Envelope): Future[Done] = {
    delegate.process(envelope).toScala
  }

  override def start(): Future[Done] =
    delegate.start().toScala

  override def stop(): Future[Done] =
    delegate.stop().toScala

}

/**
 * INTERNAL API: Adapter from `javadsl.Handler[java.util.List[Envelope]]` to `scaladsl.Handler[immutable.Seq[Envelope]]`
 */
@InternalApi private[akka] class GroupedHandlerAdapter[Envelope](delegate: javadsl.Handler[java.util.List[Envelope]])
    extends scaladsl.Handler[immutable.Seq[Envelope]] {

  override def process(envelopes: immutable.Seq[Envelope]): Future[Done] = {
    delegate.process(envelopes.asJava).toScala
  }

  override def start(): Future[Done] =
    delegate.start().toScala

  override def stop(): Future[Done] =
    delegate.stop().toScala

}

/**
 * INTERNAL API: Adapter from javadsl.HandlerLifecycle to scaladsl.HandlerLifecycle
 */
@InternalApi
private[akka] class HandlerLifecycleAdapter(delegate: javadsl.HandlerLifecycle) extends scaladsl.HandlerLifecycle {

  /**
   * Invoked when the projection is starting, before first envelope is processed.
   * Can be overridden to implement initialization. It is also called when the `Projection`
   * is restarted after a failure.
   */
  override def start(): Future[Done] =
    delegate.start().toScala

  /**
   * Invoked when the projection has been stopped. Can be overridden to implement resource
   * cleanup. It is also called when the `Projection` is restarted after a failure.
   */
  override def stop(): Future[Done] =
    delegate.stop().toScala
}

/**
 * INTERNAL API: Adapter from javadsl.ActorHandler to scaladsl.ActorHandler
 */
@InternalApi private[akka] class ActorHandlerAdapter[Envelope](delegate: javadsl.ActorHandler[Envelope, Any])
    extends scaladsl.Handler[Envelope]
    with ActorHandlerInit[Any] {

  override private[projection] def behavior = delegate.behavior

  override final def process(envelope: Envelope): Future[Done] =
    delegate.process(getActor(), envelope).toScala

  override def start(): Future[Done] =
    delegate.start().toScala

  override def stop(): Future[Done] =
    delegate.stop().toScala

}