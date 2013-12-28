package kvstore

import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Cancellable
import scala.concurrent.duration._
import scala.language.postfixOps

object Replicator {
  case class Replicate(key: String, valueOption: Option[String], id: Long)
  case class Replicated(key: String, id: Long)
  
  case class Snapshot(key: String, valueOption: Option[String], seq: Long)
  case class SnapshotAck(key: String, seq: Long)
  
  case class Reminder(seq: Long, numRetries: Long)
  case class OperationTimeout(seq: Long)

  def props(replica: ActorRef): Props = Props(new Replicator(replica))
}

class Replicator(val replica: ActorRef) extends Actor {
  import Replicator._
  import Replica._
  import context.dispatcher
  
  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  // map from sequence number to pair of sender and request
  var acks = Map.empty[Long, (ActorRef, Replicate)]
  // map of reminders
  var reminders = Map.empty[Long, Cancellable]
  // map of timeouts
  var timeouts = Map.empty[Long, Cancellable]
  // a sequence of not-yet-sent snapshots (you can disregard this if not implementing batching)
  var pending = Vector.empty[Snapshot]
  
  var _seqCounter = 0L
  def nextSeq = {
    val ret = _seqCounter
    _seqCounter += 1
    ret
  }
  
  def safeCancelAndRemove(m: Map[Long, Cancellable], seq: Long): Map[Long, Cancellable] = {
    m.get(seq) match {
      case Some(c) => c.cancel()
      case None =>
    }
    m - seq
  }
  
  /* TODO Behavior for the Replicator. */
  def receive: Receive = {
    
    case r @ Replicate(key, valueOption, id) =>
      val seq = nextSeq
      acks = acks + (seq -> (sender, r))
      replica ! Snapshot(key, valueOption, seq)
      val remind = context.system.scheduler.scheduleOnce(100 milliseconds, self, Reminder(seq, 0))
      reminders = reminders + (seq -> remind)      
      val timeout = context.system.scheduler.scheduleOnce(1 second, self, OperationTimeout(seq))
      timeouts = timeouts + (seq -> timeout)
      
    case SnapshotAck(key, seq) =>
      val (s, r) = acks(seq)
      s ! Replicated(key, r.id)
      acks = acks - seq
      reminders = safeCancelAndRemove(reminders, seq)
      timeouts = safeCancelAndRemove(timeouts, seq)
      
    case Reminder(seq, numRetries) =>
      reminders = safeCancelAndRemove(reminders, seq)
      if (acks contains seq) {
        val (s, r) = acks(seq)
        replica ! Snapshot(r.key, r.valueOption, r.id)
        val remind = context.system.scheduler.scheduleOnce(100 milliseconds, self, Reminder(seq, numRetries + 1))
        reminders = reminders + (seq -> remind)
      }
      
    case OperationTimeout(seq) =>
      timeouts = safeCancelAndRemove(timeouts, seq)
      acks.get(seq) match {
        case Some((s, r)) => 
          sender ! OperationFailed(r.id)
        case None =>
      }
      
  }

  override def postStop() = {
    reminders foreach (_._2.cancel())
    timeouts foreach (_._2.cancel())
  }

}
