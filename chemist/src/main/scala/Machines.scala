package oncue.svc.funnel.chemist

import java.net.URL
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scalaz.concurrent.Task
import scalaz.stream.Process
import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.model.{Instance => AWSInstance}
import oncue.svc.funnel.aws._

object Machines {
  def listAll(asg: AmazonAutoScaling, ec2: AmazonEC2): Task[Seq[(String, Seq[Instance])]] =
    instances(g => true)(asg,ec2)

  def listFunnels(asg: AmazonAutoScaling, ec2: AmazonEC2): Task[Seq[(String, Seq[Instance])]] =
    instances(filter.funnels)(asg,ec2)

  def listFlasks(asg: AmazonAutoScaling, ec2: AmazonEC2): Task[Seq[(String, Seq[Instance])]] =
    instances(filter.flasks)(asg,ec2)

  // def listFunnels(asg: AmazonAutoScaling, ec2: AmazonEC2): Task[Seq[URL]] =
    // instances(filter.funnels)(asg,ec2)

  private def instances(f: Group => Boolean
    )(asg: AmazonAutoScaling, ec2: AmazonEC2
    ): Task[List[(String, List[Instance])]] =
    for {
      a <- readAutoScallingGroups(asg, ec2)
      x  = a.filter(f)
      // _ = println(">>>> " +x)
      y  = x.map(g => checkGroupInstances(g).map(g.bucket -> _))
      b <- Task.gatherUnordered(y)
    } yield b

    // readAutoScallingGroups(asg, ec2).map(
    //   _.filter(checkGroupInstances).filter(f).flatMap(_.instances).map(toUrl)
    // )

  private def readAutoScallingGroups(asg: AmazonAutoScaling, ec2: AmazonEC2): Task[Seq[Group]] =
    for {
      g <- ASG.list(asg)
      x <- EC2.reservations(g.flatMap(_.instances.map(_.id)))(ec2)
    } yield {
      val instances: Map[String, AWSInstance] = x.flatMap(_.getInstances.asScala.toList)
                      .groupBy(_.getInstanceId).mapValues(_.head)

      g.map { grp =>

        println(">>>>> " + grp.instances)

        grp.copy(
          instances = grp.instances.map { i =>
            val found = instances.get(i.id)
            val sgs = found.toList.flatMap(_.getSecurityGroups.asScala.toList).map(_.getGroupName)
            i.copy(
              internalHostname = found.map(_.getPrivateDnsName),
              // serioulsy hate APIs that return '""' as their result.
              externalHostname = found.flatMap(x => if(x.getPublicDnsName.nonEmpty) Option(x.getPublicDnsName) else None),
              securityGroups   = sgs
            )
          }.toList
        )
      }
    }

  object filter {
    def funnels(g: Group): Boolean =
      g.securityGroups.exists(_.toLowerCase == "monitor-funnel")

    def flasks(g: Group): Boolean =
      g.application.map(_.startsWith("flask")).getOrElse(false)

    def chemists(g: Group): Boolean =
      g.application.map(_.startsWith("chemist")).getOrElse(false)
  }

  // def periodic(delay: Duration)(asg: AmazonAutoScaling) =
  //   Process.awakeEvery(delay).evalMap(_ => list(asg))

  import scala.io.Source
  import scalaz.\/

  private def fetch(url: URL): Throwable \/ String =
    \/.fromTryCatch {
      val c = url.openConnection
      c.setConnectTimeout(500) // timeout in 500ms to keep the overhead reasonable
      Source.fromInputStream(c.getInputStream).mkString
    }



  /**
   * Goal of this function is to validate that the machine instances specified
   * by the supplied group `g`, are in fact running a funnel instance and it is
   * ready to start sending metrics if we connect to its `/stream` function.
   */
  def checkGroupInstances(g: Group): Task[List[Instance]] = {
    val fetches: Seq[Task[Throwable \/ Instance]] = g.instances.map { i =>
      println("**************************")
      println(i)
      println(i.asURL)
      println("**************************")

      (for {
        a <- Task(i.asURL.flatMap(fetch))
        _  = println(":::::  " + a)
        b <- a.fold(e => Task.fail(e), o => Task.now(o))
      } yield i).attempt
    }

    def discardProblematicInstances(l: List[Throwable \/ Instance]): List[Instance] =
      l.foldLeft(List.empty[Instance]){ (a,b) =>
        b.fold(e => a, i => a :+ i)
      }

    Task.gatherUnordered(fetches).map(discardProblematicInstances(_))
  }
}
