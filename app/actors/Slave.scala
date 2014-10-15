package actors

import akka.actor.{Actor, ActorRef}
import akka.actor.Actor.Receive
import play.api.libs.ws.{Response, WS}
import scala.util.{Failure, Success}
import play.api.{Play, Logger}
import java.net._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.Source
import scala.concurrent.Future
import play.api.mvc.Request
import com.ning.http.client.{ProxyServer, AsyncHttpClientConfig}
import javax.net.ssl.{SSLContext, X509TrustManager, TrustManager, HttpsURLConnection}
import sun.net.www.protocol.https.HttpsURLConnectionImpl
import java.io.{InputStreamReader, BufferedReader, OutputStreamWriter}
import java.security.cert.X509Certificate
import java.security.SecureRandom
import java.util.Date
import scala.concurrent.ExecutionContext.Implicits.global

case class Resp(status: Int, body: String)

trait SlaveHeritage extends Actor {
  val emailReg = """[_a-z0-9-]+(\.[_a-z0-9-]+)*(\.)*@[a-z0-9-]+(\.[a-z0-9-]+)*(\.[a-z]{2,4})""".r
  var userAgents: Iterator[String] = Iterator.empty
  val bodyExtractor = """\{"body.*"htmlencoded":false\}""".r
  import play.api.Play.current
  def userAgent = {
    if(!userAgents.hasNext) userAgents = Source.fromInputStream(Play.classloader.getResourceAsStream("userAgents.csv")).getLines()
    userAgents.next()
  }

  override def receive: Actor.Receive = {
    case Ready => sender ! Next
    case Ask(method, check) => {
      Logger.info("Ask "+method.id+" "+check.email)
      val s = sender
      call(method, s, check)
    }
  }

  def call(method: Method, sender: ActorRef, check: Check)

  def processResult(result: Future[Resp], s: ActorRef, check: Check, method: Method) = result.onComplete({
    case Success(r) => {
      val body = bodyExtractor.findFirstIn(r.body)
      if (r.body.contains("status\":403")) s ! BlockAnswer(method, check)
      else if (r.body.contains("error\":\"not_available_for_mrim")) s ! MrimAnswer(check)
      else
        body match {
          case Some(b) => s ! Answer(method, check, r.status, b)
          case None => {
            Logger.error("UNEXTRACTED BODY")
            s ! GiveAnotherSlave(check)
          }
        }
    }
    case Failure(e) => {
      Logger.error("Send error")
      e.printStackTrace()
      s ! GiveAnotherSlave(check)
    }
  })
}

object MailRuUrls {
  val url = "https://e.mail.ru/api/v1/user/password/restore"
  val mrimUrl = "https://e.mail.ru/api/v1/user/access/support"
}

class Slave extends SlaveHeritage {




  def call(method: Method, s: ActorRef, check: Check) {
    emailReg.findFirstIn(check.email.toLowerCase) match {
      case Some(extractedEmail) => {
        Logger.info("Extracted email: "+ extractedEmail)
        val methodUrl = method match {
          case Recovery => MailRuUrls.url
          case Access => MailRuUrls.mrimUrl
        }

        val result = WS.url(methodUrl).withHeaders("User-Agent" -> userAgent)
              .withQueryString(("ajax_call","1"),("x-email",""),("htmlencoded","false"),("api","1"),("token",""),("email",extractedEmail)).post("")

        processResult(result.map(r => Resp(r.status, r.body)), s, check, method)
      }
      case None => {
        Logger.info("Can't extract email")
        s ! Answer(method, check, 904, "can't parse string")
      }
    }


  }
}

class RemoteSlave(remoteUrl: String) extends SlaveHeritage {
  def call(method: Method, s: ActorRef, check: Check) {
    emailReg.findFirstIn(check.email.toLowerCase) match {
      case Some(extractedEmail) => {
        Logger.info("Extracted email: "+ extractedEmail)
        val methodUrl = method match {
          case Recovery => remoteUrl +"?email="+extractedEmail
          case Access => remoteUrl + "?mrim=1&email="+extractedEmail
        }
        val result = WS.url(methodUrl).withHeaders("User-Agent" -> userAgent).get()

        processResult(result.map(r => Resp(r.status, r.body)), s, check, method)
      }
      case None => {
        Logger.info("Can't extract email")
        s ! Answer(method, check, 904, "can't parse string")
      }
    }
  }
}

case object Ready

case class Ask(method: Method, check: Check) {
  def email = check.email
}

case class GiveAnotherSlave(check: Check)

case class MrimAnswer(check: Check)

case class BlockAnswer(method: Method, check: Check)

case class Answer(method: Method, check: Check, status: Int, body: String) {
  def notExists: Boolean = body.contains("\"error\":\"not_exists\"")
  def exists: Boolean = status == 200 && body.contains("status\":200")
  def email = check.email
}

sealed trait Method {
  def id: String
}

case object Recovery extends Method {
  val id = "recovery"
}
case object Access extends Method {
  val id = "access"
}

case object Next

