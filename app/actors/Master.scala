package actors

import java.util.Date

import akka.actor.{Props, ActorRef, Actor}
import akka.actor.Actor.Receive
import play.api.Logger
import play.libs.Akka
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class Master extends Actor {

  var pause = 4000
  var cooldown = 8000

  var currentBatch: Option[CheckBatch] = None

  var giveAnotherSlave: List[(ActorRef, Check)] = Nil

  var singleEmails: List[CheckEmail] = Nil

  val selfSlave = context.actorOf(Props[Slave], "selfie")

  val slaves = (("SELF",selfSlave) +: SlaveHolder.list.map(url => url -> context.actorOf(Props(new RemoteSlave(url)))))// ++ proxies.map(p => p -> proxyFactory(context, p))

  var vacant: List[ActorRef] = slaves.map(_._2)

  def triggerVacant {
    vacant.foreach(_ ! Ready)
    vacant = Nil
  }

  override def receive: Receive = {
    case c@CheckBatch(id: String, iterator: Iterator[String]) => {
      currentBatch = Some(c)
      triggerVacant
    }
    case c@CheckEmail(email) => {
      singleEmails :+= c
      triggerVacant
    }
    case GiveAnotherSlave(check) => {
      giveAnotherSlave :+= (sender, check)
      Akka.system.scheduler.scheduleOnce(cooldown milliseconds, sender, Ready)
    }
    case MrimAnswer(check) => {
      sender ! Ask(Access, check)
    }
    case m@SingleEmailResult => {
      context.parent ! m
      sender ! Ready
    }
    case m@BatchEmailResult(id, c) => {
      context.parent ! m
      sender ! Ready
    }
    case Next if singleEmails.nonEmpty => {
      val check = singleEmails.head
      singleEmails = singleEmails.tail
      Akka.system.scheduler.scheduleOnce(pause milliseconds, sender, Ask(Recovery, check))
    }
    case Next if giveAnotherSlave.nonEmpty => {
      val (notSendTo, check) = giveAnotherSlave.find(_._1 != sender).getOrElse(giveAnotherSlave.head)
      giveAnotherSlave = giveAnotherSlave.tail
      val target = if(notSendTo == Some(sender) && vacant.nonEmpty) {
        val target = vacant.head
        vacant = vacant.tail :+ sender
        Logger.info("Sending to another sender")
        target
      }
      else sender
      Akka.system.scheduler.scheduleOnce(pause milliseconds, target, Ask(Recovery, check))
    }
    case Next if currentBatch.exists(_.emails.nonEmpty) =>
      Akka.system.scheduler.scheduleOnce(pause milliseconds, sender,
        Ask(Recovery, CheckEmailFromBatch(currentBatch.get.id, currentBatch.get.emails.next())))
    case Next =>
      vacant :+= sender
  }
}
