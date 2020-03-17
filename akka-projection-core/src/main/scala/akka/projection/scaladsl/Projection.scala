/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.scaladsl

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import akka.Done
import akka.NotUsed
import akka.actor.ActorSystem
import akka.actor.ClassicActorSystemProvider
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source

object Projection {
  def atLeastOnce[Envelope, Event, Offset](
      systemProvider: ClassicActorSystemProvider,
      sourceProvider: SourceProvider[Offset, Envelope],
      envelopeExtractor: EnvelopeExtractor[Envelope, Event, Offset],
      handler: ProjectionHandler[Event],
      offsetStore: OffsetStore[Offset],
      afterNumberOfEvents: Int,
      orAfterDuration: FiniteDuration)(implicit ec: ExecutionContext): Projection[Envelope, Event, Offset] = {
    new Projection(
      systemProvider,
      sourceProvider,
      envelopeExtractor,
      handler,
      OffsetStore.AtLeastOnce(afterNumberOfEvents, orAfterDuration),
      offsetStore)
  }

  def atMostOnce[Envelope, Event, Offset](
      systemProvider: ClassicActorSystemProvider,
      sourceProvider: SourceProvider[Offset, Envelope],
      envelopeExtractor: EnvelopeExtractor[Envelope, Event, Offset],
      handler: ProjectionHandler[Event],
      offsetStore: OffsetStore[Offset])(implicit ec: ExecutionContext): Projection[Envelope, Event, Offset] = {
    new Projection(systemProvider, sourceProvider, envelopeExtractor, handler, OffsetStore.AtMostOnce, offsetStore)
  }

  def onceAndOnlyOnce[Envelope, Event, Offset](
      systemProvider: ClassicActorSystemProvider,
      sourceProvider: SourceProvider[Offset, Envelope],
      envelopeExtractor: EnvelopeExtractor[Envelope, Event, Offset],
      handler: ProjectionHandler[Event] with OffsetManagedByProjectionHandler[Offset])(
      implicit ec: ExecutionContext): Projection[Envelope, Event, Offset] = {
    new Projection(
      systemProvider,
      sourceProvider,
      envelopeExtractor,
      handler,
      OffsetStore.OnceAnOnlyOnce,
      OffsetStore.noOffsetStore)
  }

}

class Projection[Envelope, Event, Offset] private (
    systemProvider: ClassicActorSystemProvider,
    sourceProvider: SourceProvider[Offset, Envelope],
    envelopeExtractor: EnvelopeExtractor[Envelope, Event, Offset],
    handler: ProjectionHandler[Event],
    offsetStrategy: OffsetStore.Strategy,
    offsetStore: OffsetStore[Offset])(implicit ec: ExecutionContext) {

  def start(): Unit = {
    implicit val system: ActorSystem = systemProvider.classicSystem

    val offset: Future[Option[Offset]] =
      handler match {
        case offsetHandler: OffsetManagedByProjectionHandler[Offset] =>
          offsetHandler.readOffset()
        case _ =>
          offsetStore.readOffset()
      }

    val source: Source[(Offset, Event), NotUsed] =
      Source
        .futureSource(offset.map(sourceProvider.source))
        .map(env => envelopeExtractor.extractOffset(env) -> envelopeExtractor.extractPayload(env))
        .mapMaterializedValue(_ => NotUsed)

    def currentOffsetFlow: Flow[(Offset, Event), (Offset, Event), NotUsed] =
      handler match {
        case offsetHandler: OffsetManagedByProjectionHandler[Offset] =>
          Flow[(Offset, Event)].map {
            case (offset, event) =>
              offsetHandler.setCurrentOffset(offset)
              offset -> event
          }
        case _ =>
          Flow[(Offset, Event)]
      }

    def groupedCurrentOffsetFlow: Flow[immutable.Seq[(Offset, Event)], immutable.Seq[(Offset, Event)], NotUsed] =
      handler match {
        case offfsetHandler: OffsetManagedByProjectionHandler[Offset] =>
          Flow[immutable.Seq[(Offset, Event)]].map { group =>
            offfsetHandler.setCurrentOffset(group.last._1)
            group
          }
        case _ =>
          Flow[immutable.Seq[(Offset, Event)]]
      }

    val handlerFlow: Flow[(Offset, Event), Offset, NotUsed] =
      handler match {
        case h: AbstractSingleEventHandler[Event] =>
          Flow[(Offset, Event)].via(currentOffsetFlow).mapAsync(parallelism = 1) {
            case (offset, event) => h.onEvent(event).map(_ => offset)
          }
        case h: AbstractGroupedEventsHandler[Event] =>
          Flow[(Offset, Event)].groupedWithin(h.n, h.d).filterNot(_.isEmpty).via(groupedCurrentOffsetFlow).mapAsync(1) {
            group =>
              h.onEvents(group.map(_._2)).map(_ => group.last._1)
          }
      }

    (offsetStrategy match {
      case OffsetStore.NoOffsetStorage =>
        source.via(handlerFlow).map(_ => Done)

      case OffsetStore.AtMostOnce =>
        source
          .mapAsync(parallelism = 1) {
            case (offset, event) => offsetStore.saveOffset(offset).map(_ => offset -> event)
          }
          .via(handlerFlow)
          .map(_ => Done)

      case OffsetStore.AtLeastOnce(1, d) =>
        source
          .mapAsync(parallelism = 1) {
            case (offset, event) => offsetStore.saveOffset(offset).map(_ => offset -> event)
          }
          .via(handlerFlow)
          .mapAsync(1) { offset =>
            offsetStore.saveOffset(offset)
          }

      case OffsetStore.AtLeastOnce(n, d) =>
        source
          .via(handlerFlow)
          .groupedWithin(n, d)
          .collect { case grouped if grouped.nonEmpty => grouped.last }
          .mapAsync(parallelism = 1) { offset =>
            offsetStore.saveOffset(offset)
          }

      case OffsetStore.OnceAnOnlyOnce =>
        source.via(handlerFlow).map(_ => Done)

    }).runWith(Sink.ignore)

  }
}