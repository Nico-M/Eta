package fuck.andes.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class EtaAssistantCompletionSurfacePolicyTest {

    @Test
    fun `hidden for foreground operation returns RESTORE_ENTRY`() {
        val result = EtaAssistantCompletionSurfacePolicy.resolve(
            hiddenForForegroundOperation = true
        )
        assertEquals(EtaAssistantCompletionSurface.RESTORE_ENTRY, result)
    }

    @Test
    fun `not hidden for foreground operation returns KEEP_CURRENT`() {
        val result = EtaAssistantCompletionSurfacePolicy.resolve(
            hiddenForForegroundOperation = false
        )
        assertEquals(EtaAssistantCompletionSurface.KEEP_CURRENT, result)
    }
}