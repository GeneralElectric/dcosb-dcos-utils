package io.predix.dcosb.dcos

import spray.json.JsValue

import scala.util.Try

object DCOSCommon {

  trait PackageOptionsService {
    def name: String
  }

  /**
    * It is highly recommended that implementing classes of this trait
    * make as many as possible, or all of their fields optional
    * and do validation on the incoming [[OpenServiceBrokerApi.InstanceParameters]] in
    * [[ServiceModule.createServiceInstance]] instead.
    *
    * This is to support sending partial options objects as updates
    * to DC/OS Cosmos via [[CosmosApiClient]]
    */
  trait PackageOptions {
    def service: PackageOptionsService
  }

  case class PkgInfo(pkgName: String, pkgVersion: String, planApiCompatible: Boolean)

  case class Connection(principal: Option[String],
                        privateKey: Option[Array[Byte]],
                        apiHost: String,
                        apiPort: Int)

  case class Service[P <: PackageOptions](pkg: PkgInfo,
                                           pkgOptionsWriter: (P => JsValue),
                                           pkgOptionsReader: (Option[JsValue] => Try[P]),
                                           connection: Connection)

  case class Scheduler(envvars: Map[String, String], labels: Map[String, String])


}
