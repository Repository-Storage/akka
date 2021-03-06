/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import java.net.{ Inet4Address, Inet6Address, InetAddress, UnknownHostException }

import akka.actor._
import akka.routing.ConsistentHashingRouter.ConsistentHashable
import com.typesafe.config.Config

import scala.collection.{ breakOut, immutable }

abstract class Dns {
  def cached(name: String): Option[Dns.Resolved] = None
  def resolve(name: String)(system: ActorSystem, sender: ActorRef): Option[Dns.Resolved] = {
    val ret = cached(name)
    if (ret.isEmpty)
      IO(Dns)(system).tell(Dns.Resolve(name), sender)
    ret
  }
}

object Dns extends ExtensionId[DnsExt] with ExtensionIdProvider {
  sealed trait Command

  case class Resolve(name: String) extends Command with ConsistentHashable {
    override def consistentHashKey = name
  }

  case class Resolved(name: String, ipv4: immutable.Seq[Inet4Address], ipv6: immutable.Seq[Inet6Address]) extends Command {
    val addrOption: Option[InetAddress] = IpVersionSelector.getInetAddress(ipv4.headOption, ipv6.headOption)

    @throws[UnknownHostException]
    def addr: InetAddress = addrOption match {
      case Some(ipAddress) ⇒ ipAddress
      case None            ⇒ throw new UnknownHostException(name)
    }
  }

  object Resolved {
    def apply(name: String, addresses: Iterable[InetAddress]): Resolved = {
      val ipv4: immutable.Seq[Inet4Address] = addresses.collect({
        case a: Inet4Address ⇒ a
      })(breakOut)
      val ipv6: immutable.Seq[Inet6Address] = addresses.collect({
        case a: Inet6Address ⇒ a
      })(breakOut)
      Resolved(name, ipv4, ipv6)
    }
  }

  def cached(name: String)(system: ActorSystem): Option[Resolved] = {
    Dns(system).cache.cached(name)
  }

  def resolve(name: String)(system: ActorSystem, sender: ActorRef): Option[Resolved] = {
    Dns(system).cache.resolve(name)(system, sender)
  }

  override def lookup() = Dns

  override def createExtension(system: ExtendedActorSystem): DnsExt = new DnsExt(system)

  /**
   * Java API: retrieve the Udp extension for the given system.
   */
  override def get(system: ActorSystem): DnsExt = super.get(system)
}

class DnsExt(val system: ExtendedActorSystem) extends IO.Extension {

  val Settings = new Settings(system.settings.config.getConfig("akka.io.dns"))

  class Settings private[DnsExt] (_config: Config) {
    import _config._

    val Dispatcher: String = getString("dispatcher")
    val Resolver: String = getString("resolver")
    val ResolverConfig: Config = getConfig(Resolver)
    val ProviderObjectName: String = ResolverConfig.getString("provider-object")

    override def toString = s"Settings($Dispatcher, $Resolver, $ResolverConfig, $ProviderObjectName)"
  }

  val provider: DnsProvider = system.dynamicAccess.getClassFor[DnsProvider](Settings.ProviderObjectName).get.newInstance()
  val cache: Dns = provider.cache

  val manager: ActorRef = {
    system.systemActorOf(
      props = Props(provider.managerClass, this).withDeploy(Deploy.local).withDispatcher(Settings.Dispatcher),
      name = "IO-DNS")
  }

  def getResolver: ActorRef = manager
}

object IpVersionSelector {
  def getInetAddress(ipv4: Option[Inet4Address], ipv6: Option[Inet6Address]): Option[InetAddress] =
    System.getProperty("java.net.preferIPv6Addresses") match {
      case "true" ⇒ ipv6 orElse ipv4
      case _      ⇒ ipv4 orElse ipv6
    }
}
