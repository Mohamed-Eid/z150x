// Ported from headunit-revived (AGPLv3): aap/AapMessageHandler.kt
package com.z150x.app.aa

internal interface AapMessageHandler {
    @Throws(HandleException::class)
    fun handle(message: AapMessage)

    class HandleException internal constructor(cause: Throwable) : Exception(cause)
}
