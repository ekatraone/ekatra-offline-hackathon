package org.ekatra.alfred.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MathInterceptorTest {

    private val math = MathInterceptor()

    @Test
    fun `evaluates arithmetic with symbols and words`() {
        assertEquals("19", math.tryEvaluateMath("what is 247 ÷ 13"))
        assertEquals("345", math.tryEvaluateMath("calculate 15 × 23"))
        assertEquals("14", math.tryEvaluateMath("12 plus 5 minus 3"))
        assertEquals("30", math.tryEvaluateMath("what is 15% of 200"))
    }

    @Test
    fun `evaluates Arabic operators and Arabic-Indic digits`() {
        assertEquals("8", math.tryEvaluateMath("٥ زائد ٣"))
        assertEquals("12", math.tryEvaluateMath("كم يساوي 4 ضرب 3"))
    }

    @Test
    fun `ignores dates and year ranges`() {
        assertNull(math.tryEvaluateMath("What happened on 15/8/1947?"))
        assertNull(math.tryEvaluateMath("Tell me about 1857-1947"))
    }

    @Test
    fun `over works as a whole word`() {
        assertEquals("2", math.tryEvaluateMath("6 over 3"))
    }
}
