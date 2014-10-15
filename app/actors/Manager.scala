package actors

import akka.actor.{Props, ActorRef, Actor}
import akka.actor.Actor.Receive
import java.util.Date
import play.libs.Akka

import scala.io.Source
import java.io.FileWriter

class Manager extends Actor {

  val cache = new CacheHolder[String, SingleEmailResult]()

  val master: ActorRef = Akka.system.actorOf(Props[Master], "master")

  var activeBatch: Option[ProcessingBatch] = None

  var processingEmails: Map[String, Set[ActorRef]] = Map()

  var batchQueue: List[String] = Nil

  def finishedBatches: List[FinishedBatch] = Nil

  override def receive: Receive = {
    case CheckEmail(email) if processingEmails.contains(email) =>
      processingEmails += email -> (processingEmails(email) + sender)
    case CheckEmail(email) =>
      cache.get(email) match {
        case Some(result) => sender ! result
        case None => {
          processingEmails += email -> Set(sender)
          master ! CheckEmail(email)
        }
      }
    case r@SingleEmailResult(email, _,_,_) =>
      cache.put(email, r)
      processingEmails.get(email).map(_.foreach(_ ! r))
      processingEmails -= email
    case LaunchBatch(id) if activeBatch.isEmpty => setActiveAndLaunchBatch(id)
    case LaunchBatch(id) =>
      batchQueue :+= id
    case BatchEmailResult(id, result) if activeBatch.exists(b => b.id == id) =>
      writeToBatch(id, result)
    case CheckBatchFinished(id) =>
      batchQueue.headOption match {
        case s@Some(head) =>
          setActiveAndLaunchBatch(head)
          batchQueue = batchQueue.tail
        case None => activeBatch = None
      }
    case GetOverallStatus => sender ! OverallStatus(activeBatch, batchQueue.map(s => QueuedBatch(s, 0)), finishedBatches)
    case GetStatus(id) if activeBatch.exists(_.id == id) => sender ! activeBatch.get
    case GetStatus(id) if batchQueue.contains(id) => sender ! QueuedBatch(id, 0)
    case GetStatus(id) if finishedBatches.exists(_.id == id) => sender ! finishedBatches.find(_.id == id).get
    case GetStatus(id) => sender ! NotExisting(id)
  }

  private def setActiveAndLaunchBatch(id: String) = {
    if(activeBatch.isDefined) {
//TODO
    }
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

case class OverallStatus(processing: Option[ProcessingBatch], queue: List[QueuedBatch], finished: List[FinishedBatch])

trait Check {
  def email: String
  def response(exists: Boolean, status: Int, body: String): AnyRef
}

case class CheckEmail(email: String) extends Check {
  def response(exists: Boolean, status: Int, body: String): AnyRef = SingleEmailResult(email, exists, status, body)
}

case class CheckEmailFromBatch(id: String, email: String) extends Check {
  override def response(exists: Boolean, status: Int, body: String): AnyRef = BatchEmailResult(id, SingleEmailResult(email, exists, status, body))
}

case class CheckBatch(id: String, emails: Iterator[String]) {
  def finished = CheckBatchFinished(id)
}

case class CheckBatchFinished(id: String)

trait BatchStatus {
  def id: String
}

case class NotExisting(id: String) extends BatchStatus

case class QueuedBatch(id: String, size: Int) extends BatchStatus

case class ProcessingBatch(id: String, size: Int, processed: Int = 0, start: Date = new Date(), speed: Int = 0) extends BatchStatus

case class FinishedBatch(id: String, size: Int, processed: Int, start: Date, end: Date, speed: Int = 0) extends BatchStatus

case class SingleEmailResult(email: String, exists: Boolean, status: Int, body: String)

case class BatchEmailResult(id: String, result: SingleEmailResult)

