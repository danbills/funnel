package funnel
package flask

import scala.concurrent.duration._
import scalaz.\/-
import scalaz.concurrent.{Strategy,Task}
import scalaz.std.option._
import scalaz.syntax.applicative._
import org.scalatest.{FlatSpec,Matchers,BeforeAndAfterAll}
import org.scalatest.OptionValues._
import scalaz.stream._
import scalaz.stream.async.signalOf
import scalaz.stream.async.mutable.Signal
import argonaut._, Argonaut._
import journal.Logger
import funnel.elastic._
import funnel.http.JSON._
import funnel.http.MonitoringServer
import funnel.zeromq._
import sockets._

class FlaskSpec extends FlatSpec with Matchers with BeforeAndAfterAll {
  import java.io.File
  import knobs.{ ClassPathResource, Config, FileResource, Required }
  import dispatch._

  val config: Task[Config] = for {
    a <- knobs.loadImmutable(List(Required(
      FileResource(new File("/usr/share/oncue/etc/flask.cfg")) or
        ClassPathResource("oncue/flask.cfg"))))
    b <- knobs.aws.config
  } yield a ++ b

  val (options, cfg) = config.flatMap { cfg =>
    val name             = cfg.lookup[String]("flask.name")
    val cluster           = cfg.lookup[String]("flask.cluster")
    val elasticURL       = cfg.lookup[String]("flask.elastic-search.url")
    val elasticIx        = cfg.lookup[String]("flask.elastic-search.index-name")
    val elasticTy        = cfg.lookup[String]("flask.elastic-search.type-name")
    val elasticDf        =
      cfg.lookup[String]("flask.elastic-search.partition-date-format").getOrElse("yyyy.MM.dd")
    val elasticTimeout   = cfg.lookup[Int]("flask.elastic-search.connection-timeout-in-ms").getOrElse(5000)
    val esGroups         = cfg.lookup[List[String]]("flask.elastic-search.groups")
    val riemannHost      = cfg.lookup[String]("flask.riemann.host")
    val riemannPort      = cfg.lookup[Int]("flask.riemann.port")
    val ttl              = cfg.lookup[Int]("flask.riemann.ttl-in-minutes").map(_.minutes)
    val riemann          = (riemannHost |@| riemannPort |@| ttl)(RiemannCfg)
    val elastic          = (elasticURL |@| elasticIx |@| elasticTy |@| esGroups)(
      ElasticCfg(_, _, _, elasticDf, "foo", None, _))
    val port             = cfg.lookup[Int]("flask.network.port").getOrElse(5775)
    Task((Options(name, cluster, elastic, riemann, port), cfg))
  }.run

  val flaskUrl = url(s"http://localhost:${options.funnelPort}").setContentType("application/json", "UTF-8")

  val log = Logger[this.type]

  val S = Strategy.Executor(Monitoring.serverPool)
  val P = Monitoring.schedulingPool

  private def makeMS(port: Int): (Counter, MonitoringServer) = {
    val L = ((s: String) => log.debug(s))
    val M = Monitoring.instance(Monitoring.serverPool, L)
    val I = new Instruments(1.minute, M, 200.milliseconds)
    val C = I.counter("my_counter", 0, "My counter")
    val ms = MonitoringServer.start(M, port, 36.hours)
    (C, ms)
  }

  if (Ø.isEnabled) {
    "mirrorDatapoints with 5000 datapoints input" should "be 5000" in {
      val payload = s"""
      [
        {
          "cluster": "datapoints-1.0-us-east",
          "urls": [
            "${Settings.tcp}"
          ]
        }
      ]
      """

      val ready = signalOf(false)(Strategy.Executor(Monitoring.serverPool))

      implicit val B = scalaz.std.anyVal.booleanInstance.conjunction
      implicit val s = scalaz.stream.DefaultScheduler

      val E = Endpoint.unsafeApply(publish &&& bind, Settings.tcp)

      implicit val batransport: Transportable[Array[Byte]] = Transportable { ba =>
        Transported(Schemes.unknown, Versions.v1, None, None, ba)
      }

      val seq: Seq[Array[Byte]] = for(i <- 0 until 5000) yield Datapoint(Key[Double]("now/life", Units.Count: Units, "description", Map("url" -> "http://localhost")), 42.0).asJson.spaces2.getBytes
      val k: Seq[Boolean] = seq.map(_ => true) ++ Seq(false)

      val proc: Process[Task, Array[Byte]] = Process.emitAll(seq) ++ Process.eval_(ready.set(true))
      val alive: Process[Task, Boolean] = Process.emitAll(k)

      val app = new Flask(options, new Instruments(1.minute))

      app.run(Array())
      Http(flaskUrl / "mirror" << payload OK as.String)(concurrent.ExecutionContext.Implicits.global)()

      app.I.monitoring.get(app.mirrorDatapoints.keys.now).discrete.sleepUntil(ready.discrete.once).once.runLast.map(_.get).runAsync { d =>
        d.fold (
          t =>
          throw t,
          v =>
          v.toInt should be (5000)
        )
      }

      Ø.linkP(E)(alive)(socket =>
        proc.through(Ø.write(socket))).runFoldMap(identity).run

      app.S.stop()
    }
  }

  "mirrorDatapoints for 2 minutes with 100 HTTP endpoints, half of which die" should "change" in {
    val n = 100
    val ms = (1024 until 1024 + n).map(makeMS)
    val payload = s"""
    [
      {
        "cluster": "datapoints-1.0-us-east",
        "urls": [
          ${(1024 until 1024 + n).map(p => "\"http://localhost:" + p + "/stream/now\"").mkString(",\n")}
        ]
      }
    ]
    """

    val app = new Flask(options, new Instruments(1.minute))

    app.run(Array())
    Http(flaskUrl / "mirror" << payload OK as.String)(concurrent.ExecutionContext.Implicits.global)()
    Thread.sleep(1000)

    val (cs, ss) = ms.unzip
    (0 until n by 2) foreach(i => Process.repeatEval(Task(cs(i).increment)).run.runAsync(_ => ()))
    (1 until n by 2) foreach(i => ss(i).stop)

    val waitACouple = time.sleep(2.minutes)(S, P) ++ Process.emit(true)

    val mds: Process[Task, Double] = app.I.monitoring.get(app.mirrorDatapoints.keys.now).discrete
    val mdChanges: Process[Task, Double] = waitACouple.wye(mds)(wye.interrupt)(S)
    val changes: IndexedSeq[Double] = mdChanges.runLog.run
    changes should not be empty

    app.S.stop()
    ms.foreach(_._2.stop())
  }

  "An endpoint dying from under Flask" should "result in an empty sources list" in {
    val payload = s"""
    [
      {
        "cluster": "datapoints-1.0-us-east",
        "urls": [
          ${(1024 until 1025).map(p => "\"http://localhost:" + p + "/stream/now\"").mkString(",\n")}
        ]
      }
    ]
    """
    val (c, ms) = makeMS(1024)					// Counter and MonitoringServer
    val s = signalOf(true)(S)					// Signal indicating liveness of Counter
    val b = s.continuous.zip(
      Process.eval(
        Task.delay(c.increment)
      ).repeat
    )								// Increment Counter until Signal closed
    val k = b.take(1000) ++
      Process.eval(
        Task.delay {
          ms.stop
          s.close
        }
      ) ++
      time.sleep((6 * 30 + 10).seconds)(S, P)			// Increment 1,000 times, die, and timeout

    val app = new Flask(options, new Instruments(1.minute))

    app.run(Array())
    Http(flaskUrl / "mirror" << payload OK as.String)(concurrent.ExecutionContext.Implicits.global)()
    Thread.sleep(1000)						// Let Flask mirroring catch up

    k.run.run							// Bang on the Counter, die, and sleep

    val r = Http(flaskUrl / "sources" OK as.String)(concurrent.ExecutionContext.Implicits.global)()
    val sources = Parse.parse(r)
    sources should === (\/-(jEmptyArray))
  }
}
