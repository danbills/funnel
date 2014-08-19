package oncue.svc.funnel.chemist

import org.scalatest.{FlatSpec, Matchers, BeforeAndAfterAll}
import intelmedia.ws.funnel.Monitoring
import intelmedia.ws.funnel.http.MonitoringServer
import oncue.svc.funnel.aws.{Group,Instance}

class MachinesSpec extends FlatSpec with Matchers with BeforeAndAfterAll {

  val S1 = MonitoringServer.start(Monitoring.default, 5775)

  val I1 = Instance(
    id = "foo1",
    zone = "us-east-1a",
    externalHostname = Some("localhost"))

  val I2 = Instance(
    id = "foo2",
    zone = "us-east-1b",
    externalHostname = Some("localhost"))

  val G1 = Group(
    name = "test",
    instances = Seq(I1,I2)
  )

  val G2 = Group(
    name = "test",
    instances = Seq(I1,I2.copy(externalHostname = Some("qndsfoindsfonsidf.com")))
  )

  override def afterAll(){
    S1.stop()
  }

  it must "return two instances 2/2 can be reached" in {
    Machines.checkGroupInstances(G1).run.sortBy(_.id) should equal (List(I1,I2))
  }
  it must "return one instance if 1/2 cannot be reached reached" in {
    Machines.checkGroupInstances(G2).run.sortBy(_.id) should equal (List(I1))
  }

}