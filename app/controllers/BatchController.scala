package controllers

import play.api.mvc._
import actors._
import BatchPath._
import java.io.File
import akka.util.Timeout
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.ExecutionContext.Implicits.global
import akka.pattern.ask
import play.api._
import play.api.mvc._
import play.libs.Akka
import akka.actor.Props
import scala.concurrent.duration._

object BatchController extends Controller {
  import Theater._

  implicit val timeout = Timeout(10 seconds)

  def list = Action.async{(manager ? GetOverallStatus).mapTo[OverallStatus].map(s => {
    Ok(view.html.index(s))
  })

  def add = Ok(views.html.upload())

  def upload = Action(parse.multipartFormData) { request =>
    request.body.file("emailsFile").map { file =>
      val id = generateId
      BatchPath.acclaimBatch(file.ref, id)
      manager ! LaunchBatch(id)
      Redirect(routes.BatchController.batch.get(id))
      Ok(views.html.redirect())
    }.getOrElse {
      Ok("Error file")
    }
  }

  def get(id: String) = Action.async{(manager ? GetStatus(id)).mapTo[BatchStatus].map({
    case s:ProcessingBatch => Ok(view.html.processing(s))
    case f:FinishedBatch => Ok(view.html.finished(f))
  })}
  
  def resultFile(id: String) = Action {
    Ok.sendFile(new java.io.File(BatchPath.resultPath(id)))
  }

  def requestFile(id: String) = Action {
    Ok.sendFile(new java.io.File(BatchPath.requestPath(id)))
  }
}
