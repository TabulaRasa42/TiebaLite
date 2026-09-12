package com.huanchengfly.tieba.post.arch

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 回归测试：collectPartialAsState 的首帧种子值必须来自 StateFlow 的当前值，
 * 而不是调用方传入的 initial。
 *
 * 若首帧用 initial（空列表/null），导航返回恢复 LazyListState 时会因首帧空列表
 * 把恢复的滚动位置钳制回 0，导致"返回后帖子列表回到顶部"。
 */
class CollectPartialAsStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private data class TestUiState(
        val value: String = "",
        val items: List<String> = emptyList(),
    ) : UiState

    @Test
    fun firstCompositionFrame_seesStateFlowCurrentValue_notInitial() {
        val stateFlow = MutableStateFlow(TestUiState(value = "loaded"))

        val firstFrameValue = arrayOf<String?>(null)
        val latch = CountDownLatch(1)

        composeRule.setContent {
            val projected by stateFlow.collectPartialAsState(
                prop1 = TestUiState::value,
                initial = "initial"
            )
            SideEffect {
                if (firstFrameValue[0] == null) {
                    firstFrameValue[0] = projected
                    latch.countDown()
                }
            }
        }

        assertEquals(true, latch.await(5, TimeUnit.SECONDS))
        assertEquals("loaded", firstFrameValue[0])
    }

    @Test
    fun firstCompositionFrame_listProjection_seesCurrentItems_notEmpty() {
        val stateFlow = MutableStateFlow(
            TestUiState(items = listOf("a", "b", "c"))
        )

        val firstFrameItems = arrayOf<List<String>?>(null)
        val latch = CountDownLatch(1)

        composeRule.setContent {
            val projected by stateFlow.collectPartialAsState(
                prop1 = TestUiState::items,
                initial = persistentListOf()
            )
            SideEffect {
                if (firstFrameItems[0] == null) {
                    firstFrameItems[0] = projected
                    latch.countDown()
                }
            }
        }

        assertEquals(true, latch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("a", "b", "c"), firstFrameItems[0])
    }

    @Test
    fun projection_updatesWhenFlowEmitsNewValue() {
        val stateFlow = MutableStateFlow(TestUiState(value = "loaded"))

        val latestValue = arrayOf<String?>(null)
        composeRule.setContent {
            val projected by stateFlow.collectPartialAsState(
                prop1 = TestUiState::value,
                initial = "initial"
            )
            SideEffect {
                // 每帧记录最新值供断言读取
                latestValue[0] = projected
            }
        }

        composeRule.waitForIdle()
        assertEquals("loaded", latestValue[0])

        composeRule.runOnUiThread {
            stateFlow.value = TestUiState(value = "updated")
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            latestValue[0] == "updated"
        }
        assertEquals("updated", latestValue[0])
    }
}
