/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import akka.actor.{ Actor, ActorLogging, ActorRef, PoisonPill, Props }
import akka.cluster.Cluster
import akka.cluster.sharding.ShardRegion.Passivate
import akka.pattern.{ BackoffOpts, BackoffSupervisor }
import akka.testkit.{ AkkaSpec, ImplicitSender }

object SupervisionSpec {
  val config =
    ConfigFactory.parseString("""
    akka.actor.provider = "cluster"
    akka.loglevel = INFO
    """)

  case class Msg(id: Long, msg: Any)
  case class Response(self: ActorRef)
  case object StopMessage

  val idExtractor: ShardRegion.ExtractEntityId = {
    case Msg(id, msg) => (id.toString, msg)
  }

  val shardResolver: ShardRegion.ExtractShardId = {
    case Msg(id, _) => (id % 2).toString
  }

  class PassivatingActor extends Actor with ActorLogging {

    override def preStart(): Unit = {
      log.info("Starting")
    }

    override def postStop(): Unit = {
      log.info("Stopping")
    }

    override def receive: Receive = {
      case "passivate" =>
        log.info("Passivating")
        context.parent ! Passivate(StopMessage)
        // simulate another message causing a stop before the region sends the stop message
        // e.g. a persistent actor having a persist failure while processing the next message
        context.stop(self)
      case "hello" =>
        sender() ! Response(self)
      case StopMessage =>
        log.info("Received stop from region")
        context.parent ! PoisonPill
    }
  }

}

class SupervisionSpec extends AkkaSpec(SupervisionSpec.config) with ImplicitSender {

  import SupervisionSpec._

  "Supervision for a sharded actor (deprecated)" must {

    "allow passivation" in {

      val supervisedProps =
        BackoffOpts
          .onStop(
            Props(new PassivatingActor()),
            childName = "child",
            minBackoff = 1.seconds,
            maxBackoff = 30.seconds,
            randomFactor = 0.2)
          .withFinalStopMessage(_ == StopMessage)
          .props

      Cluster(system).join(Cluster(system).selfAddress)
      val region = ClusterSharding(system).start(
        "passy",
        supervisedProps,
        ClusterShardingSettings(system),
        idExtractor,
        shardResolver)

      region ! Msg(10, "hello")
      val response = expectMsgType[Response](5.seconds)
      watch(response.self)

      region ! Msg(10, "passivate")
      expectTerminated(response.self)

      // This would fail before as sharded actor would be stuck passivating
      region ! Msg(10, "hello")
      expectMsgType[Response](20.seconds)
    }
  }

  "Supervision for a sharded actor" must {

    "allow passivation" in {

      val supervisedProps = BackoffSupervisor.props(
        BackoffOpts
          .onStop(
            Props(new PassivatingActor()),
            childName = "child",
            minBackoff = 1.seconds,
            maxBackoff = 30.seconds,
            randomFactor = 0.2)
          .withFinalStopMessage(_ == StopMessage))

      Cluster(system).join(Cluster(system).selfAddress)
      val region = ClusterSharding(system).start(
        "passy",
        supervisedProps,
        ClusterShardingSettings(system),
        idExtractor,
        shardResolver)

      region ! Msg(10, "hello")
      val response = expectMsgType[Response](5.seconds)
      watch(response.self)

      region ! Msg(10, "passivate")
      expectTerminated(response.self)

      // This would fail before as sharded actor would be stuck passivating
      region ! Msg(10, "hello")
      expectMsgType[Response](20.seconds)
    }
  }

}
