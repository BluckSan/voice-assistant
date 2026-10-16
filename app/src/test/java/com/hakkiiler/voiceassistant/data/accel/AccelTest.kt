package com.hakkiiler.voiceassistant.data.accel

import com.hakkiiler.voiceassistant.data.image.ImageCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccelTest {
    @Test fun dimensity8400BigCores() {
        // 4×2.1 + 3×3.0 + 1×3.25 ГГц
        val f = listOf(2100000L, 2100000, 2100000, 2100000, 3000000, 3000000, 3000000, 3250000)
        assertEquals(listOf(4, 5, 6, 7), Accel.bigCoresOf(f))
    }

    @Test fun probeParsing() {
        val ok = Accel.parseProbe("pending\ngpu=Mali-G720\nsd=ok 0.0012 Mali\nllm=ok 0.002 Mali\ndone\n", false)
        assertTrue(ok.sdOk && ok.llmOk)
        assertEquals("Mali-G720", ok.gpu)
        val crashed = Accel.parseProbe("pending\ngpu=Mali\n", false)
        assertFalse(crashed.sdOk || crashed.llmOk)
        val llmCrash = Accel.parseProbe("pending\nsd=ok 0.001 x\n", true)
        assertTrue(llmCrash.sdOk); assertFalse(llmCrash.llmOk); assertTrue(llmCrash.noF16)
        val mismatch = Accel.parseProbe("sd=fail mismatch 0.5 x\nllm=ok 0 x\ndone", false)
        assertFalse(mismatch.sdOk); assertTrue(mismatch.llmOk)
    }

    @Test fun brokenImage() {
        assertTrue(ImageCheck.looksBroken(ByteArray(64 * 64 * 3)))
        val img = ByteArray(64 * 64 * 3) { (it * 7).toByte() }
        assertFalse(ImageCheck.looksBroken(img))
    }
}
