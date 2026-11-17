package local.headunit.diplay

import java.net.*

/** Android 4.2 IoBridge.bind mistakes every unscoped Inet6Address for link-local.
 * Bind the established control connection's local address, never an unscoped ::.
 * IPv4 ANY is only the default for standalone callers; Android uses dual-stack sockets.
 */
object LegacySockets {
 @Volatile private var userland: UserlandNcmNetwork? = null

 internal fun installUserland(network: UserlandNcmNetwork) {
  synchronized(this) {
   check(userland == null || userland === network) { "another userland network is already installed" }
   userland = network
  }
 }

 internal fun removeUserland(network: UserlandNcmNetwork) {
  synchronized(this) { if (userland === network) userland = null }
 }

 internal fun isUserlandActive(): Boolean = userland != null

 fun anyAddress(): InetAddress = InetAddress.getByName("0.0.0.0")
 fun udp(address: InetAddress = anyAddress()): DatagramSocket {
  userland?.let { return it.openUdp(address) }
  val socket = DatagramSocket(null)
  try { socket.reuseAddress=true;socket.bind(InetSocketAddress(address,0));return socket }
  catch(e:Exception){socket.close();throw e}
 }
 fun tcp(address: InetAddress = anyAddress(), port: Int = 0): ServerSocket {
  userland?.let { return it.openTcp(address, port) }
  val socket=ServerSocket()
  try { socket.reuseAddress=true;socket.bind(InetSocketAddress(address,port));return socket }
  catch(e:Exception){socket.close();throw e}
 }
}
