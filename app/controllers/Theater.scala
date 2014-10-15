package controllers

import play.libs.Akka
import akka.actor.Props
import actors.{Manager, Master}
import akka.util.Timeout

object Theater {
  val manager = Akka.system.actorOf(Props[Manager], "manager")
}
