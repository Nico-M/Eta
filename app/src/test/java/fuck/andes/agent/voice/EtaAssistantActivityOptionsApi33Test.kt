package fuck.andes.agent.voice

import android.os.Bundle
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EtaAssistantActivityOptionsApi33Test {
    @Test
    fun senderOptionsOnApi33ReturnsBundleWithoutException() {
        val options: Bundle = EtaAssistantActivityOptions.senderOptions()
        assertNotNull(options)
    }
}
