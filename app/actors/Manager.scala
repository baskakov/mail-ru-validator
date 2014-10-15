package actors

import akka.actor.{ActorRef, Actor}
import akka.actor.Actor.Receive
import java.util.Date
import scala.io.Source
import java.io.FileWriter

class Manager extends Actor {

  val cache = new CacheHolder[String, SingleEmailResult]()

  val master: ActorRef = ???

  var activeBatch: Option[ProcessingBatch] = None

  var processingEmails: Map[String, Set[ActorRef]] = Map()

  var batchQueue: List[String] = Nil

  def finishedBatches: List[FinishedBatch] = ???

  override def receive: Receive = {
    case CheckEmail(email) if processingEmails.contains(email) =>
      processingEmails += email -> (processingEmails(email) + sender)
    case CheckEmail(email) =>
      cache.get(email) match {
        case Some(result) => sender ! result
        case None => processingEmails += email -> Set(sender)
      }
    case r@SingleEmailResult(email, _,_,_) =>
      cache.put(email, r)
      processingEmails.get(email).map(_.foreach(_ ! r))
      processingEmails -= email
    case LaunchBatch(id) if activeBatch.isEmpty => setActiveAndLaunchBatch(id)
    case LaunchBatch(id) =>
      batchQueue += id
    case BatchEmailResult(id, result) if activeBatch.exists(b => b.id == id) =>
      writeToBatch(id, result)
      if(isCurrentBatchFinished) {
        batchQueue.headOption match {
          case s@Some(head) => setActiveAndLaunchBatch(head)
          case None => activeBatch = None
        }
      }
    case GetOverallStatus => sender ! OverallStatus(activeBatch, batchQueue, finishedBatches)
  }

  private def setActiveAndLaunchBatch(id: String) = {
    activeBatch = Some(ProcessingBatch(id, batchEmails(id).size))
    master ! CheckBatch(id, batchEmails(id))
  }

  private def writeToBatch(id: String, result: SingleEmailResult) = {
    val fw = new FileWriter(BatchPath.resultPath(id), true)
    val line = (result.email :: {if(!result.exists) "0" else "1"} :: result.body :: Nil).mkString(";") + "\r\n"
    try {
      fw.write(line)
    }
    finally fw.close()
  }

  private def isCurrentBatchFinished = activeBatch.exists(b => b.processed == b.size)

  private def batchEmails(id: String) = BatchPath.batchIterator(id)
}

case class LaunchBatch(id: String)

case class StopBatch(id: String)

case class GetStatus(id: String)

case object GetOverallStatus

case class OverallStatus(active: Option[ProcessingBatch], queue: List[QueuedBatch], finished: List[FinishedBatch])

case class CheckEmail(email: String)

case class CheckBatch(id: String, emails: Iterator[String])

trait BatchStatus {
  def id: String
}

case class QueuedBatch(id: String, size: Int) extends BatchStatus

case class ProcessingBatch(id: String, size: Int, processed: Int = 0, start: Date = new Date(), speed: Int = 0) extends BatchStatus

case class FinishedBatch(id: String, size: Int, processed: Int, start: Date, end: Date) extends BatchStatus

case class SingleEmailResult(email: String, exists: Boolean, status: Int, body: String)

case class BatchEmailResult(id: String, result: SingleEmailResult)

