package funnel

import funnel.elastic.ElasticCfg
import scala.concurrent.duration._
import com.amazonaws.auth.BasicAWSCredentials

case class RiemannCfg(
  host: String,
  port: Int,
  ttl: Duration
)

case class Options(
  name: Option[String],
  elastic: Option[ElasticCfg] = None,
  riemann: Option[RiemannCfg] = None,
  snsErrorTopic: String,
  funnelPort: Int = 5775,
  metricTTL: Option[Duration] = None,
  telemetryPort: Int = 7391
)
