package gg.modl.build

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

fun InputStream.sha256(): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read < 0) {
            return digest.digest()
        }
        digest.update(buffer, 0, read)
    }
}

fun File.sha256(): ByteArray = inputStream().use { it.sha256() }

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
