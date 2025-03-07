package net.corda.node.internal.artemis

import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.contextLogger
import net.corda.nodeapi.internal.crypto.X509CertificateFactory
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.nodeapi.internal.protonwrapper.netty.RevocationConfig
import net.corda.nodeapi.internal.protonwrapper.netty.RevocationConfigImpl
import net.corda.nodeapi.internal.protonwrapper.netty.certPathToString
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.PKIXBuilderParameters
import java.security.cert.X509CertSelector

sealed class CertificateChainCheckPolicy {
    companion object {
        val log = contextLogger()
    }

    @FunctionalInterface
    interface Check {
        fun checkCertificateChain(theirChain: Array<java.security.cert.X509Certificate>)
    }

    abstract fun createCheck(keyStore: KeyStore, trustStore: KeyStore): Check

    object Any : CertificateChainCheckPolicy() {
        override fun createCheck(keyStore: KeyStore, trustStore: KeyStore): Check {
            return object : Check {
                override fun checkCertificateChain(theirChain: Array<java.security.cert.X509Certificate>) {
                    // nothing to do here
                }
            }
        }
    }

    object RootMustMatch : CertificateChainCheckPolicy() {
        override fun createCheck(keyStore: KeyStore, trustStore: KeyStore): Check {
            val rootAliases = trustStore.aliases().asSequence().filter { it.startsWith(X509Utilities.CORDA_ROOT_CA) }
            val rootPublicKeys = rootAliases.map { trustStore.getCertificate(it).publicKey }.toSet()
            return object : Check {
                override fun checkCertificateChain(theirChain: Array<java.security.cert.X509Certificate>) {
                    val theirRoot = theirChain.last().publicKey
                    if (theirRoot !in rootPublicKeys) {
                        throw CertificateException("Root certificate mismatch, their root = $theirRoot")
                    }
                }
            }
        }
    }

    object LeafMustMatch : CertificateChainCheckPolicy() {
        override fun createCheck(keyStore: KeyStore, trustStore: KeyStore): Check {
            val ourPublicKey = keyStore.getCertificate(X509Utilities.CORDA_CLIENT_TLS).publicKey
            return object : Check {
                override fun checkCertificateChain(theirChain: Array<java.security.cert.X509Certificate>) {
                    val theirLeaf = theirChain.first().publicKey
                    if (ourPublicKey != theirLeaf) {
                        throw CertificateException("Leaf certificate mismatch, their leaf = $theirLeaf")
                    }
                }
            }
        }
    }

    data class MustContainOneOf(private val trustedAliases: Set<String>) : CertificateChainCheckPolicy() {
        override fun createCheck(keyStore: KeyStore, trustStore: KeyStore): Check {
            val trustedPublicKeys = trustedAliases.map { trustStore.getCertificate(it).publicKey }.toSet()
            return object : Check {
                override fun checkCertificateChain(theirChain: Array<java.security.cert.X509Certificate>) {
                    if (!theirChain.any { it.publicKey in trustedPublicKeys }) {
                        throw CertificateException("Their certificate chain contained none of the trusted ones")
                    }
                }
            }
        }
    }

    object UsernameMustMatchCommonName : CertificateChainCheckPolicy() {
        override fun createCheck(keyStore: KeyStore, trustStore: KeyStore): Check {
            return UsernameMustMatchCommonNameCheck()
        }
    }

    class UsernameMustMatchCommonNameCheck : Check {
        lateinit var username: String
        override fun checkCertificateChain(theirChain: Array<java.security.cert.X509Certificate>) {
            if (!theirChain.any { certificate -> CordaX500Name.parse(certificate.subjectDN.name).commonName == username }) {
                throw CertificateException("Client certificate does not match login username.")
            }
        }
    }

    class RevocationCheck(val revocationConfig: RevocationConfig) : CertificateChainCheckPolicy() {
        constructor(revocationMode: RevocationConfig.Mode) : this(RevocationConfigImpl(revocationMode))

        override fun createCheck(keyStore: KeyStore, trustStore: KeyStore): Check {
            return object : Check {
                override fun checkCertificateChain(theirChain: Array<java.security.cert.X509Certificate>) {
                    // Convert javax.security.cert.X509Certificate to java.security.cert.X509Certificate.
                    val chain = theirChain.map { X509CertificateFactory().generateCertificate(it.encoded.inputStream()) }
                    log.info("Check Client Certpath:\r\n${certPathToString(chain.toTypedArray())}")

                    // Drop the last certificate which must be a trusted root (validated by RootMustMatch).
                    // Assume that there is no more trusted roots (or corresponding public keys) in the remaining chain.
                    // See PKIXValidator.engineValidate() for reference implementation.
                    val certPath = X509Utilities.buildCertPath(chain.dropLast(1))
                    val certPathValidator = CertPathValidator.getInstance("PKIX")
                    val pkixRevocationChecker = revocationConfig.createPKIXRevocationChecker()
                    val params = PKIXBuilderParameters(trustStore, X509CertSelector())
                    params.addCertPathChecker(pkixRevocationChecker)
                    try {
                        certPathValidator.validate(certPath, params)
                    } catch (ex: CertPathValidatorException) {
                        log.error("Bad certificate path", ex)
                        throw ex
                    }
                }
            }
        }
    }
}