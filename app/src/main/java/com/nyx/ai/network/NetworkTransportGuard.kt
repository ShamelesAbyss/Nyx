package com.nyx.ai.network

import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy

/** Guards addresses actually offered to OkHttp and the connected route before any HTTP bytes. */
internal object NetworkTransportGuard {
    fun publicClient(client: OkHttpClient, loopbackFixture: Boolean = false): OkHttpClient = client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .dns(checkedDns(client.dns) { hostname, address ->
            (loopbackFixture && NetworkAddressPolicy.isLoopbackHost(hostname) && address.isLoopbackAddress) ||
                NetworkAddressPolicy.isPublic(address)
        })
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val fixture = loopbackFixture && NetworkAddressPolicy.isLoopbackHost(request.url.host)
            if (!fixture) NetworkPolicy.publicUrlProblem(request.url.toString())?.let { throw IOException(it) }
            val address = chain.connection()?.route()?.socketAddress?.address ?: throw IOException("Missing connected network address.")
            if (!(fixture && address.isLoopbackAddress) && !NetworkAddressPolicy.isPublic(address)) {
                throw IOException("Connected route is a non-public address.")
            }
            chain.proceed(request.newBuilder().removeHeader("Authorization").removeHeader("Cookie")
                .removeHeader("Proxy-Authorization").build())
        }.build()

    fun inferenceClient(client: OkHttpClient, base: HttpUrl, hasCredential: Boolean): OkHttpClient {
        InferenceEndpointPolicy.requireCredentialTransport(base, hasCredential)
        return client.newBuilder()
            .followRedirects(false).followSslRedirects(false)
            .proxy(Proxy.NO_PROXY)
            .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .dns(checkedDns(client.dns) { hostname, address ->
                hostname == base.host && InferenceEndpointPolicy.addressAllowed(base, hasCredential, address)
            })
            .addNetworkInterceptor { chain ->
                val url = chain.request().url
                if (url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
                    url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
                    throw IOException("Request left the explicitly selected inference origin.")
                }
                val address = chain.connection()?.route()?.socketAddress?.address ?: throw IOException("Missing connected inference address.")
                if (!InferenceEndpointPolicy.addressAllowed(base, hasCredential, address)) {
                    throw IOException("Inference route is outside the selected secure/local endpoint policy.")
                }
                chain.proceed(chain.request().newBuilder().removeHeader("Cookie").removeHeader("Proxy-Authorization").build())
            }.build()
    }

    private fun checkedDns(delegate: Dns, allowed: (String, InetAddress) -> Boolean): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val answers = delegate.lookup(hostname)
            if (answers.isEmpty() || answers.any { !allowed(hostname, it) }) {
                throw IOException("DNS resolved outside the allowed network scope.")
            }
            return answers
        }
    }
}
