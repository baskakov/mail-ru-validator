package actors

import java.text.SimpleDateFormat
import java.util.Date
import scala.io.Source
import java.io.File
import play.api.libs.Files.TemporaryFile
import play.api.libs.Files

object BatchPath {
  val formatter = new SimpleDateFormat("yyyyMMdd-HHmmSSS")

  def generateId = formatter.format(new Date())

  private def p(id: String) = "mail-ru-validation/" + id

  def requestPath(id: String) = p(id) + "/request.csv"

  def resultPath(id: String) = p(id) + "/response.csv"

  def statusPath(id: String) = p(id) + "/status"

  def batchIterator(id: String) = Source.fromFile(BatchPath.requestPath(id)).getLines()

  def acclaimBatch(file: TemporaryFile, id: String) {
    file.moveTo(new File(requestPath(id)), true)
    val size = batchIterator(id).size
    Files.writeFile(new File(statusPath(id)), "0;"+size.toString)
  }
}
