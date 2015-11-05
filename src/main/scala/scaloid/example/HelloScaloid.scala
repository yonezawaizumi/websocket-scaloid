package scaloid.example

import org.scaloid.common._
import android.graphics.Color

import scala.util.{Success, Failure}

class HelloScaloid extends SActivity with TagUtil {
  self =>

  implicit val exec = ExecutionContext.exec

  lazy val meToo = new STextView("Me too")
  lazy val webSocket = {
    val ws = new WebSocketPF(WebSocketParameters(
      "ws://echo.websocket.org",
      5000,
      ""
    ))
    ws.state.onComplete {
      case Success(_) => info("socket shutdowned")
      case Failure(t) => error(s"socket failed ${t.getMessage}")
    }
    ws
  }

  private var counter = 1

  onCreate {

    contentView = new SVerticalLayout {
      style {
        case b: SButton => b.textColor(Color.RED).onClick(self.onClick)
        case t: STextView => t textSize 10.dip
        case e: SEditText => e.backgroundColor(Color.YELLOW).textColor(Color.BLACK)
      }
      STextView("I am 10 dip tall")
      meToo.here
      STextView("I am 15 dip tall") textSize 15.dip // overriding
      new SLinearLayout {
        STextView("Button: ")
        SButton(R.string.red)
      }.wrap.here
      SEditText("Yellow input field fills the space").fill
    } padding 20.dip
  }

  def onClick: Unit = {
    info(s"onClick ${counter}")
    if (counter == 10) {
      webSocket.close
    } else {
      val future = webSocket.request(s"aaa${counter}bbb")
      future.onSuccess { case s => info(s) }
      future.onFailure { case t => error(t.getMessage) }
    }
    counter += 1
  }
}
