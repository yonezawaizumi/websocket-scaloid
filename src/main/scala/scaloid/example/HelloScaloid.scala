package scaloid.example

import org.scaloid.common._
import android.graphics.Color

import scala.util.{Success, Failure}

class HelloScaloid extends SActivity with TagUtil with Logger with WebSocketStateListener {
  self =>

  implicit val exec = ExecutionContext.exec

  lazy val meToo = new STextView("Me too")
  lazy val webSocket = {
    /*new WebSocketPF(WebSocketParameters(
      "ws://echo.websocket.org",
      5000,
      ""
    ))*/
    new WebSocketCB(WebSocketParameters(
      "ws://echo.websocket.org",
      5000,
      ""
    ), scala.ref.WeakReference(this))
  }

  private var counter = 1

  onCreate {

    contentView = new SVerticalLayout {
      style {
        case b: SButton => b.textColor(Color.RED).onClick(self.onClick())
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

  def onClick(): Unit = {
    info(s"onClick $counter")
    /*info(s"socket open: ${webSocket.isOpen}")
    info(s"socket ready: ${webSocket.isReady}")
    if (counter == 10) {
      webSocket.close()
    } else {
      val future = webSocket.request(s"aaa${counter}bbb")
      future.onSuccess { case s => info(s) }
      future.onFailure { case t => error(t.getMessage) }
    }
    counter += 1*/
    if (counter == 10) {
      webSocket.close()

      val queue = new ShutdownableBlockingDeque[WebSocketData]
      queue.offer(WebSocketDataFinalize)
      queue.offer(WebSocketDataFinalize)
      queue.offer(WebSocketDataFinalize)
      queue.offer(WebSocketDataFinalize)
      queue.shutdown().get.foreach(d => self.info(s"test: ${d.toString}"))
    } else {
      val data = new WebSocketData(s"aaa${counter}bbb") {
        val activity = new scala.ref.WeakReference(self)
        override def onSuccess(response: String): Unit = {
          activity.get.foreach(a => runOnUiThread(a.info(s"socket result $request => $response")))
        }
        override def onFailure(e: Throwable): Unit = {
          activity.get.foreach(a => runOnUiThread(a.error(s"socket failure $request => ${e.toString}")))
        }
      }
      webSocket.request(data)
    }
    counter += 1
  }

  def onSocketResult(e: Throwable): Unit = {
    runOnUiThread(error(s"onSocketResult ${e.toString}"))
  }
}
