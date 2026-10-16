package eu.kanade.tachiyomi.extension.all.lunaranime

import android.util.Base64
import okhttp3.HttpUrl
import okhttp3.Interceptor
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * The site's "Serenity" proof is a plain DPoP JWT. The browser generates its own P-256 key
 * pair, keeps it in IndexedDB and signs `<header>.<payload>` with ES256, sending the result in
 * the `cant-catch-this-monkey` header. The key pair is never issued or acknowledged by the
 * server, so one generated here is just as valid.
 */
class LunarSerenity(
    private val apiUrl: String,
) {

    @Volatile
    private var keyPair: KeyPair = generateKeyPair()

    @Volatile
    private var dpopNonce: String? = null

    private fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Synchronized
    fun reset() {
        keyPair = generateKeyPair()
        dpopNonce = null
    }

    private val publicPoint get() = (keyPair.public as ECPublicKey).w

    private val coordinateX get() = publicPoint.affineX.toFixedWidth()

    private val coordinateY get() = publicPoint.affineY.toFixedWidth()

    /**
     * RFC 7638 thumbprint of the public JWK, used by the site as extra key material when
     * decrypting a chapter's session data.
     */
    val thumbprint: String
        get() {
            val jwk = """{"crv":"P-256","kty":"EC","x":"$coordinateX","y":"$coordinateY"}"""
            return sha256(jwk.toByteArray()).base64Url()
        }

    fun proof(method: String, url: HttpUrl, nonce: String? = null): String {
        val header = """{"alg":"ES256","typ":"dpop+jwt","jwk":{"kty":"EC","crv":"P-256","x":"$coordinateX","y":"$coordinateY"}}"""
        val payload = buildString {
            append("""{"htu":"${url.scheme}://${url.host}${url.encodedPath}","htm":"${method.uppercase()}","iat":${System.currentTimeMillis() / 1000},"jti":"${UUID.randomUUID()}"""")
            if (nonce != null) append(""","nonce":"$nonce"""")
            append("}")
        }

        val signingInput = "${header.toByteArray().base64Url()}.${payload.toByteArray().base64Url()}"
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(signingInput.toByteArray())
            sign()
        }

        return "$signingInput.${signature.derToJose().base64Url()}"
    }

    fun interceptor() = Interceptor { chain ->
        val request = chain.request()
        if (!request.url.toString().startsWith(apiUrl)) {
            return@Interceptor chain.proceed(request)
        }

        val response = chain.proceed(request.withProof())

        if (response.code == 401 && response.peekBody(1024).string().contains("dpop_nonce_required")) {
            val xRid = response.header("x-rid")
            if (xRid != null) {
                response.close()
                dpopNonce = sha256((NONCE_SALT + xRid).toByteArray()).base64Url()
                return@Interceptor chain.proceed(request.withProof())
            }
        }

        // The site mints a fresh key pair whenever the server rejects the current one.
        if (response.code == 403 && response.peekBody(1024).string().contains("validate", ignoreCase = true)) {
            response.close()
            reset()
            return@Interceptor chain.proceed(request.withProof())
        }

        response
    }

    private fun okhttp3.Request.withProof(): okhttp3.Request =
        newBuilder().header(PROOF_HEADER, proof(method, url, dpopNonce)).build()

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray.base64Url(): String = Base64.encodeToString(this, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)

    /** P-256 coordinates are always sent as 32 bytes, big endian. */
    private fun BigInteger.toFixedWidth(): String {
        val bytes = toByteArray()
        val fixed = when {
            bytes.size == COORDINATE_SIZE -> bytes
            bytes.size > COORDINATE_SIZE -> bytes.copyOfRange(bytes.size - COORDINATE_SIZE, bytes.size)
            else -> ByteArray(COORDINATE_SIZE).also { bytes.copyInto(it, COORDINATE_SIZE - bytes.size) }
        }
        return fixed.base64Url()
    }

    /** JOSE wants the raw `r || s` pair, while the JCA signs into a DER sequence. */
    private fun ByteArray.derToJose(): ByteArray {
        var index = 1
        val length = this[index++].toInt() and 0xFF
        if (length and 0x80 != 0) index += length and 0x7F

        fun readInteger(): BigInteger {
            require(this[index].toInt() == 0x02) { "Malformed ECDSA signature" }
            index++
            val size = this[index++].toInt() and 0xFF
            return BigInteger(1, copyOfRange(index, index + size)).also { index += size }
        }

        val r = readInteger().toByteArray32()
        val s = readInteger().toByteArray32()

        return r + s
    }

    private fun BigInteger.toByteArray32(): ByteArray {
        val bytes = toByteArray()
        return when {
            bytes.size == COORDINATE_SIZE -> bytes
            bytes.size > COORDINATE_SIZE -> bytes.copyOfRange(bytes.size - COORDINATE_SIZE, bytes.size)
            else -> ByteArray(COORDINATE_SIZE).also { bytes.copyInto(it, COORDINATE_SIZE - bytes.size) }
        }
    }

    companion object {
        private const val PROOF_HEADER = "DPoP"
        private const val NONCE_SALT = "lx7-n1-q9Z21"
        private const val COORDINATE_SIZE = 32
    }
}
