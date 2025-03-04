package net.corda.nodeapi.internal

import net.corda.core.messaging.ClientRpcSslOptions
import net.corda.core.serialization.internal.nodeSerializationEnv
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.nodeapi.BrokerRpcSslOptions
import net.corda.nodeapi.internal.config.CertificateStore
import net.corda.nodeapi.internal.config.DEFAULT_SSL_HANDSHAKE_TIMEOUT
import net.corda.nodeapi.internal.config.MutualSslConfiguration
import net.corda.nodeapi.internal.config.SslConfiguration
import org.apache.activemq.artemis.api.core.TransportConfiguration
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyConnectorFactory
import org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants
import java.nio.file.Path

class ArtemisTcpTransport {
    companion object {
        val CIPHER_SUITES = listOf(
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
        )

        val TLS_VERSIONS = listOf("TLSv1.2")

        const val SSL_HANDSHAKE_TIMEOUT_NAME = "SSLHandshakeTimeout"
        const val TRACE_NAME = "trace"

        // Turn on AMQP support, which needs the protocol jar on the classpath.
        // Unfortunately we cannot disable core protocol as artemis only uses AMQP for interop.
        // It does not use AMQP messages for its own messages e.g. topology and heartbeats.
        private const val P2P_PROTOCOLS = "CORE,AMQP"

        private const val RPC_PROTOCOLS = "CORE"

        private fun defaultArtemisOptions(hostAndPort: NetworkHostAndPort, protocols: String) = mapOf(
                // Basic TCP target details.
                TransportConstants.HOST_PROP_NAME to hostAndPort.host,
                TransportConstants.PORT_PROP_NAME to hostAndPort.port,
                TransportConstants.PROTOCOLS_PROP_NAME to protocols,
                TransportConstants.USE_GLOBAL_WORKER_POOL_PROP_NAME to (nodeSerializationEnv != null),
                TransportConstants.REMOTING_THREADS_PROPNAME to (if (nodeSerializationEnv != null) -1 else 1),
                // turn off direct delivery in Artemis - this is latency optimisation that can lead to
                //hick-ups under high load (CORDA-1336)
                TransportConstants.DIRECT_DELIVER to false)

        private val defaultSSLOptions = mapOf(
                TransportConstants.ENABLED_CIPHER_SUITES_PROP_NAME to CIPHER_SUITES.joinToString(","),
                TransportConstants.ENABLED_PROTOCOLS_PROP_NAME to TLS_VERSIONS.joinToString(","))

        private fun SslConfiguration.addToTransportOptions(options: MutableMap<String, Any>) {
            keyStore?.let {
                with (it) {
                    path.requireOnDefaultFileSystem()
                    options.putAll(get().toKeyStoreTransportOptions(path))
                }
            }
            trustStore?.let {
                with (it) {
                    path.requireOnDefaultFileSystem()
                    options.putAll(get().toTrustStoreTransportOptions(path))
                }
            }
            options[TransportConstants.SSL_PROVIDER] = if (useOpenSsl) TransportConstants.OPENSSL_PROVIDER else TransportConstants.DEFAULT_SSL_PROVIDER
            options[SSL_HANDSHAKE_TIMEOUT_NAME] = handshakeTimeout ?: DEFAULT_SSL_HANDSHAKE_TIMEOUT
        }

        private fun CertificateStore.toKeyStoreTransportOptions(path: Path) = mapOf(
                TransportConstants.SSL_ENABLED_PROP_NAME to true,
                TransportConstants.KEYSTORE_TYPE_PROP_NAME to "JKS",
                TransportConstants.KEYSTORE_PATH_PROP_NAME to path,
                TransportConstants.KEYSTORE_PASSWORD_PROP_NAME to password,
                TransportConstants.NEED_CLIENT_AUTH_PROP_NAME to true)

        private fun CertificateStore.toTrustStoreTransportOptions(path: Path) = mapOf(
                TransportConstants.SSL_ENABLED_PROP_NAME to true,
                TransportConstants.TRUSTSTORE_TYPE_PROP_NAME to "JKS",
                TransportConstants.TRUSTSTORE_PATH_PROP_NAME to path,
                TransportConstants.TRUSTSTORE_PASSWORD_PROP_NAME to password,
                TransportConstants.NEED_CLIENT_AUTH_PROP_NAME to true)

        private fun ClientRpcSslOptions.toTransportOptions() = mapOf(
                TransportConstants.SSL_ENABLED_PROP_NAME to true,
                TransportConstants.TRUSTSTORE_TYPE_PROP_NAME to trustStoreProvider,
                TransportConstants.TRUSTSTORE_PATH_PROP_NAME to trustStorePath,
                TransportConstants.TRUSTSTORE_PASSWORD_PROP_NAME to trustStorePassword)

        private fun BrokerRpcSslOptions.toTransportOptions() = mapOf(
                TransportConstants.SSL_ENABLED_PROP_NAME to true,
                TransportConstants.KEYSTORE_TYPE_PROP_NAME to "JKS",
                TransportConstants.KEYSTORE_PATH_PROP_NAME to keyStorePath,
                TransportConstants.KEYSTORE_PASSWORD_PROP_NAME to keyStorePassword,
                TransportConstants.NEED_CLIENT_AUTH_PROP_NAME to false)

        fun p2pAcceptorTcpTransport(hostAndPort: NetworkHostAndPort,
                                    config: MutualSslConfiguration?,
                                    enableSSL: Boolean = true,
                                    trace: Boolean = false): TransportConfiguration {
            val options = mutableMapOf<String, Any>()
            if (enableSSL) {
                config?.addToTransportOptions(options)
            }
            return createAcceptorTransport(hostAndPort, P2P_PROTOCOLS, options, enableSSL, trace)
        }

        fun p2pConnectorTcpTransport(hostAndPort: NetworkHostAndPort,
                                     config: MutualSslConfiguration?,
                                     enableSSL: Boolean = true,
                                     keyStoreType: String? = null): TransportConfiguration {
            val options = mutableMapOf<String, Any>()
            if (enableSSL) {
                config?.addToTransportOptions(options)
                options += asMap(keyStoreType)
            }
            return createConnectorTransport(hostAndPort, P2P_PROTOCOLS, options, enableSSL)
        }

        fun rpcAcceptorTcpTransport(hostAndPort: NetworkHostAndPort,
                                    config: BrokerRpcSslOptions?,
                                    enableSSL: Boolean = true,
                                    trace: Boolean = false): TransportConfiguration {
            val options = mutableMapOf<String, Any>()
            if (config != null && enableSSL) {
                config.keyStorePath.requireOnDefaultFileSystem()
                options.putAll(config.toTransportOptions())
            }
            return createAcceptorTransport(hostAndPort, RPC_PROTOCOLS, options, enableSSL, trace)
        }

        fun rpcConnectorTcpTransport(hostAndPort: NetworkHostAndPort, config: ClientRpcSslOptions?, enableSSL: Boolean = true): TransportConfiguration {
            val options = mutableMapOf<String, Any>()
            if (config != null && enableSSL) {
                config.trustStorePath.requireOnDefaultFileSystem()
                options.putAll(config.toTransportOptions())
            }
            return createConnectorTransport(hostAndPort, RPC_PROTOCOLS, options, enableSSL)
        }

        fun rpcInternalClientTcpTransport(hostAndPort: NetworkHostAndPort, config: SslConfiguration, keyStoreProvider: String? = null): TransportConfiguration {
            val options = mutableMapOf<String, Any>()
            config.addToTransportOptions(options)
            options += asMap(keyStoreProvider)
            return createConnectorTransport(hostAndPort, RPC_PROTOCOLS, options, enableSSL = true)
        }

        fun rpcInternalAcceptorTcpTransport(hostAndPort: NetworkHostAndPort,
                                            config: SslConfiguration,
                                            keyStoreType: String? = null,
                                            trace: Boolean = false): TransportConfiguration {
            val options = mutableMapOf<String, Any>()
            config.addToTransportOptions(options)
            options += asMap(keyStoreType)
            return createAcceptorTransport(hostAndPort, RPC_PROTOCOLS, options, enableSSL = true, trace = trace)
        }

        private fun asMap(keyStoreType: String?): Map<String, String> {
            return keyStoreType?.let { mutableMapOf(TransportConstants.KEYSTORE_TYPE_PROP_NAME to it) } ?: emptyMap()
        }

        private fun createAcceptorTransport(hostAndPort: NetworkHostAndPort,
                                            protocols: String,
                                            options: MutableMap<String, Any>,
                                            enableSSL: Boolean,
                                            trace: Boolean): TransportConfiguration {
            options += defaultArtemisOptions(hostAndPort, protocols)
            if (enableSSL) {
                options += defaultSSLOptions
            }
            // Suppress core.server.lambda$channelActive$0 - AMQ224088 error from load balancer type connections
            options[TransportConstants.HANDSHAKE_TIMEOUT] = 0
            options[TRACE_NAME] = trace
            return TransportConfiguration("net.corda.node.services.messaging.NodeNettyAcceptorFactory", options)
        }

        private fun createConnectorTransport(hostAndPort: NetworkHostAndPort,
                                             protocols: String,
                                             options: MutableMap<String, Any>,
                                             enableSSL: Boolean): TransportConfiguration {
            options += defaultArtemisOptions(hostAndPort, protocols)
            if (enableSSL) {
                options += defaultSSLOptions
                // This is required to stop Client checking URL address vs. Server provided certificate
                options[TransportConstants.VERIFY_HOST_PROP_NAME] = false
            }
            return TransportConfiguration(NettyConnectorFactory::class.java.name, options)
        }
    }
}
