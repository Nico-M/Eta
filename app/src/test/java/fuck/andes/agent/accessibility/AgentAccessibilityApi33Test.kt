package fuck.andes.agent.accessibility

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * API 33 上服务类加载时 ACTION_PAGE_* 和 ACTION_SCROLL_IN_DIRECTION
 * 字段通过反射惰性解析，类初始化不会触发 NoSuchFieldError。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentAccessibilityApi33Test {
    @Test
    fun `service loads without NoSuchFieldError from API 34 scroll actions`() {
        val controller = Robolectric.buildService(
            AgentAccessibilityService::class.java,
        )
        try {
            val service = controller.create().get()
            assertNotNull("AgentAccessibilityService should be created", service)
        } finally {
            controller.destroy()
        }
    }
}