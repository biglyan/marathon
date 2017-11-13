package mesosphere.marathon
package api.akkahttp.v2

import java.time.Clock

import akka.event.EventStream
import akka.http.scaladsl.model.{ StatusCodes, Uri }
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.{ Directive1, PathMatchers, Route }
import mesosphere.marathon.api.akkahttp.{ Controller, EntityMarshallers, Headers }
import mesosphere.marathon.api.akkahttp.PathMatchers.{ PodsPathIdLike, forceParameter }
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer, CreateRunSpec, UpdateRunSpec }
import mesosphere.marathon.state.PathId
import akka.http.scaladsl.server.PathMatchers
import mesosphere.marathon.api.v2.PodNormalization
import mesosphere.marathon.api.v2.validation.PodsValidation
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.event.PodEvent
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.pod.{ PodDefinition, PodManager }
import mesosphere.marathon.raml.{ PodConversion, Raml }
import mesosphere.marathon.api.v2.Validation._

import async.Async._
import scala.concurrent.ExecutionContext

class PodsController(
    val config: MarathonConf,
    val electionService: ElectionService,
    val podManager: PodManager,
    val groupManager: GroupManager,
    val pluginManager: PluginManager,
    val eventBus: EventStream,
    val clock: Clock)(
    implicit
    val authorizer: Authorizer,
    val authenticator: Authenticator,
    val executionContext: ExecutionContext) extends Controller {

  import mesosphere.marathon.api.akkahttp.Directives._
  import mesosphere.marathon.api.akkahttp.EntityMarshallers._

  val podNormalizer = PodNormalization.apply(PodNormalization.Configuration(
    config.defaultNetworkName.get))

  private def normalize(pod: PodDefinition): PodDefinition = pod.copy(version = clock.now())

  private val forceParameter = parameter('force.as[Boolean].?(false))

  def validatePodDefinition(podDefinition: raml.Pod): Directive1[PodDefinition] = {
    val normalized = normalize(Raml.fromRaml(podNormalizer.normalized(podDefinition)))
    validateEither(normalized)(PodsValidation.pluginValidators(pluginManager)) match {
      case Right(pod) => provide(pod)
      case Left(failure) => reject(EntityMarshallers.ValidationFailed(failure))
    }
  }

  def capability(): Route =
    authenticated.apply { implicit identity =>
      complete((StatusCodes.OK, ""))
    }

  @SuppressWarnings(Array("all")) // async/await
  def create(): Route =
    authenticated.apply { implicit identity =>
      (extractClientIP & forceParameter) { (clientIp, force) =>
        extractRequest { req =>
          entity(as(podUnmarshaller)) { podDef =>
            val normalizedPodDef = podNormalizer.normalized(podDef)
            val pod = Raml.fromRaml(normalizedPodDef).copy(version = clock.now())
            assumeValid(PodsValidation.pluginValidators(pluginManager).apply(pod)) {
              authorized(CreateRunSpec, pod).apply {
                val p = async {
                  val deployment = await(podManager.create(pod, force))

                  // TODO: How should we get the ip?
                  val ip = clientIp.getAddress().get.toString
                  eventBus.publish(PodEvent(ip, req.uri.toString(), PodEvent.Created))

                  deployment
                }
                onSuccess(p) { plan =>
                  // TODO: Set pod id uri
                  val ramlPod: raml.Pod = PodConversion.podRamlWriter.write(pod)
                  val responseHeaders = Seq(
                    Location(Uri(pod.id.toString)),
                    Headers.`Marathon-Deployment-Id`(plan.id)
                  )
                  import EntityMarshallers.podMarshaller
                  complete((StatusCodes.Created, responseHeaders, ramlPod))
                }
              }
            }
          }
        }
      }
    }

  def update(podId: PathId): Route = {
    (entity(as[raml.Pod]) & forceParameter & extractClientIP & extractUri) {
      case (ramlPod, force, host, uri) =>
        validatePodDefinition(ramlPod) { pod =>
          authorized(UpdateRunSpec, pod).apply {
            val deploymentPlan = async {
              val plan = await(podManager.update(pod, force))
              PodEvent(host.toString(), uri.toString(), PodEvent.Updated)
              plan
            }
            onSuccess(deploymentPlan) { plan =>
              import akka.http.scaladsl.marshalling.Marshaller._
              val ramlPod = PodConversion.podRamlWriter.write(pod)
              complete((StatusCodes.Created, Seq(Headers.`Marathon-Deployment-Id`(plan.id)), ramlPod))
            }
          }
        }
    }
  }

  def findAll(): Route = ???

  def find(podId: PathId): Route = ???

  def remove(podId: PathId): Route = ???

  def status(podId: PathId): Route = ???

  def versions(podId: PathId): Route = ???

  def version(podId: PathId, v: String): Route = ???

  def allStatus(): Route = ???

  def killInstance(instanceId: Instance.Id): Route = ???

  def killInstances(podId: PathId): Route = ???

  // format: OFF
  override val route: Route =
    asLeader(electionService) {
      head {
        capability()
      } ~
      get {
        pathEnd {
          findAll()
        } ~
        path("::status" ~ PathEnd) {
          allStatus()
        } ~
        path(PodsPathIdLike ~ PathEnd) { runSpecId: String =>
          withValidatedPathId(runSpecId) { id =>
            find(id)
          }
        } ~
        path(PodsPathIdLike ~ "::status" ~ PathEnd) { runSpecId: String =>
          withValidatedPathId(runSpecId) { id =>
            status(id)
          }
        } ~
        path(PodsPathIdLike ~ "::versions" ~ PathEnd) { runSpecId: String =>
          withValidatedPathId(runSpecId) { id =>
            versions(id)
          }
        } ~
        path(PodsPathIdLike ~ "::versions" / PathMatchers.Segment) { (runSpecId: String, v: String) =>
          withValidatedPathId(runSpecId) { id =>
            version(id, v)
          }
        }
      } ~
      post {
        pathEndOrSingleSlash {
          create()
        }
      } ~
      delete {
        path(PodsPathIdLike ~ PathEnd) { runSpecId: String =>
          withValidatedPathId(runSpecId) { id =>
            remove(id)
          }
        } ~
        path(PodsPathIdLike ~ "::instances" ~ PathEnd) { runSpecId: String =>
          withValidatedPathId(runSpecId) { id =>
            killInstances(id)
          }
        } ~
        path(PodsPathIdLike ~ "::instances" / PathMatchers.Segment) { (runSpecId: String, instanceId: String) =>
          assumeValid(validatePathId(runSpecId) and validateInstanceId(instanceId)) {
            killInstance(Instance.Id(instanceId))
          }
        }
      } ~
      put {
        path(PodsPathIdLike ~ PathEnd) { runSpecId: String =>
          withValidatedPathId(runSpecId) { id =>
            update(id)
          }
        }
      }
    }
  // format: ON

}
