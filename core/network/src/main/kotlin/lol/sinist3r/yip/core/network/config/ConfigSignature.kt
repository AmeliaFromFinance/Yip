package lol.sinist3r.yip.core.network.config

import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Ed25519 verification for the remote config envelope. */
object ConfigSignature {
    fun verify(publicKeyB64: String, payload: ByteArray, signatureB64: String): Boolean = try {
        val key = Ed25519PublicKeyParameters(Base64.getDecoder().decode(publicKeyB64), 0)
        Ed25519Signer().run {
            init(false, key)
            update(payload, 0, payload.size)
            verifySignature(Base64.getDecoder().decode(signatureB64))
        }
    } catch (_: IllegalArgumentException) {
        false
    }
}
