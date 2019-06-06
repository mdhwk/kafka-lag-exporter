/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import java.time.Clock

import akka.actor.Cancellable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import com.lightbend.kafkalagexporter.KafkaClient.KafkaClientContract
import com.lightbend.kafkalagexporter.LookupTable.Table.{LagIsZero, Prediction, TooFewPoints}

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

object ConsumerGroupCollector {
  import Domain._

  sealed trait Message
  sealed trait Collect extends Message
  final case object Collect extends Collect
  sealed trait Stop extends Message
  final case object Stop extends Stop
  final case class StopWithError(throwable: Throwable) extends Message
  final case class OffsetsSnapshot(
                                     timestamp: Long,
                                     groups: List[String],
                                     latestOffsets: PartitionOffsets,
                                     lastGroupOffsets: GroupOffsets
                                  ) extends Message {
    def diff(other: OffsetsSnapshot): (List[TopicPartition], List[String], List[GroupTopicPartition]) = {
      val evictedTps = latestOffsets.keySet.diff(other.latestOffsets.keySet).toList
      val evictedGroups = groups.diff(other.groups)
      val evictedGtps = lastGroupOffsets.keySet.diff(other.lastGroupOffsets.keySet).toList
      (evictedTps, evictedGroups, evictedGtps)
    }
  }

  final case class CollectorConfig(
                                    pollInterval: FiniteDuration,
                                    lookupTableSize: Int,
                                    cluster: KafkaCluster,
                                    clock: Clock = Clock.systemUTC()
                                  )

  final case class CollectorState(
                                   lastSnapshot: Option[OffsetsSnapshot] = None,
                                   topicPartitionTables: Domain.TopicPartitionTable,
                                   scheduledCollect: Cancellable = Cancellable.alreadyCancelled
                                 )

  private final case class GroupPartitionLag(gtp: GroupTopicPartition, offsetLag: Long, timeLag: Double)

  def init(config: CollectorConfig,
           clientCreator: KafkaCluster => KafkaClientContract,
           reporter: ActorRef[MetricsSink.Message]): Behavior[Message] = Behaviors.supervise[Message] {
    Behaviors.setup { context =>
      context.log.info("Spawned ConsumerGroupCollector for cluster: {}", config.cluster.name)

      context.self ! Collect

      val collectorState = CollectorState(topicPartitionTables = Domain.TopicPartitionTable(config.lookupTableSize))
      collector(config, clientCreator(config.cluster), reporter, collectorState)
    }
  }.onFailure(SupervisorStrategy.restartWithBackoff(1 seconds, 10 seconds, 0.2))

  def collector(config: CollectorConfig,
                client: KafkaClientContract,
                reporter: ActorRef[MetricsSink.Message],
                state: CollectorState): Behavior[Message] =
    (new CollectorBehavior).collector(config, client, reporter, state)

  // TODO: Ideally this wouldn't be in a class, like the other behaviors, but at this time there's no other way to
  // TODO: assert state transition changes. See `ConsumerGroupCollectorSpec` which uses mockito to assert the state
  // TODO: transition change
  class CollectorBehavior {
    def collector(config: CollectorConfig,
                  client: KafkaClientContract,
                  reporter: ActorRef[MetricsSink.Message],
                  state: CollectorState): Behavior[Message] = Behaviors.receive {
      case (context, _: Collect) =>
        implicit val ec: ExecutionContextExecutor = context.executionContext

        def getOffsetSnapshot(groups: List[String], groupTopicPartitions: List[Domain.GroupTopicPartition]): Future[OffsetsSnapshot] = {
          val now = config.clock.instant().toEpochMilli
          val distinctPartitions = groupTopicPartitions.map(_.tp).toSet

          val groupOffsetsFuture = client.getGroupOffsets(now, groups, groupTopicPartitions)
          val latestOffsetsTry = client.getLatestOffsets(now, distinctPartitions)

          for {
            groupOffsets <- groupOffsetsFuture
            Success(latestOffsets) <- Future.successful(latestOffsetsTry)
          } yield OffsetsSnapshot(now, groups, latestOffsets, groupOffsets)
        }

        context.log.debug("Collecting offsets")

        val f = for {
          (groups, groupTopicPartitions) <- client.getGroups()
          offsetSnapshot <- getOffsetSnapshot(groups, groupTopicPartitions)
        } yield offsetSnapshot

        f.onComplete {
          case Success(newOffsets) =>
            context.self ! newOffsets
          case Failure(t) =>
            context.self ! StopWithError(t)
        }(ec)

        Behaviors.same
      case (context, snapshot: OffsetsSnapshot) =>
        val (evictedTps, evictedGroups, evictedGtps) = state
          .lastSnapshot
          .map(_.diff(snapshot))
          .getOrElse((Nil, Nil, Nil))

        context.log.debug("Updating lookup tables")
        refreshLookupTable(state, snapshot, evictedTps)

        context.log.debug("Reporting offsets")
        reportLatestOffsetMetrics(config, reporter, state.topicPartitionTables)
        reportConsumerGroupMetrics(config, reporter, snapshot, state.topicPartitionTables)

        context.log.debug("Clearing evicted metrics")
        reportEvictedMetrics(config, reporter, evictedTps, evictedGroups, evictedGtps)

        context.log.debug("Polling in {}", config.pollInterval)
        val newState = state.copy(
          lastSnapshot = Some(snapshot),
          scheduledCollect = context.scheduleOnce(config.pollInterval, context.self, Collect)
        )

        collector(config, client, reporter, newState)
      case (context, _: Stop) =>
        state.scheduledCollect.cancel()
        Behaviors.stopped { () =>
          client.close()
          context.log.info("Gracefully stopped polling and Kafka client for cluster: {}", config.cluster.name)
        }
      case (_, StopWithError(t)) =>
        state.scheduledCollect.cancel()
        client.close()
        throw new Exception("A failure occurred while retrieving offsets.  Shutting down.", t)
    }

    /**
      * Refresh Lookup table.  Remove topic partitions that are no longer relevant and update tables with new Point's.
      */
    private def refreshLookupTable(state: CollectorState, snapshot: OffsetsSnapshot, evictedTps: List[TopicPartition]): Unit = {
      state.topicPartitionTables.clear(evictedTps)
      for((tp, point) <- snapshot.latestOffsets) state.topicPartitionTables(tp).addPoint(point)
    }

    private def reportConsumerGroupMetrics(
                                            config: CollectorConfig,
                                            reporter: ActorRef[MetricsSink.Message],
                                            offsetsSnapshot: OffsetsSnapshot,
                                            tables: TopicPartitionTable
                                          ): Unit = {
      val groupLag: immutable.Iterable[GroupPartitionLag] = for {
        (gtp, groupPoint) <- offsetsSnapshot.lastGroupOffsets
        mostRecentPoint <- tables(gtp.tp).mostRecentPoint().toOption
      } yield {
        val timeLag = tables(gtp.tp).lookup(groupPoint.offset) match {
          case Prediction(pxTime) => (groupPoint.time.toDouble - pxTime) / 1000
          case LagIsZero => 0d
          case TooFewPoints => Double.NaN
        }

        val offsetLag = mostRecentPoint.offset - groupPoint.offset

        reporter ! Metrics.GroupPartitionValueMessage(Metrics.LastGroupOffsetMetric, config.cluster.name, gtp, groupPoint.offset)
        reporter ! Metrics.GroupPartitionValueMessage(Metrics.OffsetLagMetric, config.cluster.name, gtp, offsetLag)
        reporter ! Metrics.GroupPartitionValueMessage(Metrics.TimeLagMetric, config.cluster.name, gtp, timeLag)

        GroupPartitionLag(gtp, offsetLag, timeLag)
      }

      for((group, values) <- groupLag.groupBy(_.gtp.id)) {
        val maxOffsetLag = values.maxBy(_.offsetLag)
        val maxTimeLag = values.maxBy(_.timeLag)

        reporter ! Metrics.GroupValueMessage(Metrics.MaxGroupOffsetLagMetric, config.cluster.name, group, maxOffsetLag.offsetLag)
        reporter ! Metrics.GroupValueMessage(Metrics.MaxGroupTimeLagMetric, config.cluster.name, group, maxTimeLag.timeLag)
      }
    }

    private def reportLatestOffsetMetrics(
                                           config: CollectorConfig,
                                           reporter: ActorRef[MetricsSink.Message],
                                           tables: TopicPartitionTable
                                         ): Unit = {
      for {
        (tp, table: LookupTable.Table) <- tables.all
        point <- table.mostRecentPoint()
      } reporter ! Metrics.TopicPartitionValueMessage(Metrics.LatestOffsetMetric, config.cluster.name, tp, point.offset)
    }

    private def reportEvictedMetrics(
                                      config: CollectorConfig,
                                      reporter: ActorRef[MetricsSink.Message],
                                      tps: List[Domain.TopicPartition],
                                      groups: List[String],
                                      gtps: List[Domain.GroupTopicPartition]): Unit = {
      tps.foreach(tp => reporter ! Metrics.TopicPartitionRemoveMetricMessage(Metrics.LatestOffsetMetric, config.cluster.name, tp))
      groups.foreach { group =>
        reporter ! Metrics.GroupRemoveMetricMessage(Metrics.MaxGroupOffsetLagMetric, config.cluster.name, group)
        reporter ! Metrics.GroupRemoveMetricMessage(Metrics.MaxGroupTimeLagMetric, config.cluster.name, group)
      }
      gtps.foreach { gtp =>
        reporter ! Metrics.GroupPartitionRemoveMetricMessage(Metrics.LastGroupOffsetMetric, config.cluster.name, gtp)
        reporter ! Metrics.GroupPartitionRemoveMetricMessage(Metrics.OffsetLagMetric, config.cluster.name, gtp)
        reporter ! Metrics.GroupPartitionRemoveMetricMessage(Metrics.TimeLagMetric, config.cluster.name, gtp)
      }
    }
  }
}