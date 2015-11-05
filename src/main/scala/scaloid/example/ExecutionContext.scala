package scaloid.example

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit, ThreadPoolExecutor}


object ExecutionContext {
  val exec = scala.concurrent.ExecutionContext.fromExecutor(
    new ThreadPoolExecutor(100, 100, 1000, TimeUnit.SECONDS,
      new LinkedBlockingQueue[Runnable]))
}
