package com.ane.filemanager.input

import android.view.KeyEvent
import com.ane.filemanager.plugin.api.input.PluginTerminalKey
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class HostPluginInputTest {
    @Test
    fun controlLettersAndPunctuationProduceControlBytes() {
        assertBytes(byteArrayOf(3), KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)
        assertBytes(byteArrayOf(0), KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON)
        assertBytes(byteArrayOf(0x1b), KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.META_CTRL_ON)
        assertBytes(byteArrayOf(0x1f), KeyEvent.KEYCODE_MINUS, KeyEvent.META_CTRL_ON)
    }

    @Test
    fun navigationKeysEncodeXtermModifiers() {
        assertBytes("\u001b[A".bytes(), KeyEvent.KEYCODE_DPAD_UP)
        assertBytes("\u001b[1;5A".bytes(), KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_CTRL_ON)
        assertBytes(
            "\u001b[1;4D".bytes(),
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON
        )
    }

    @Test
    fun shiftTabFunctionKeysAndDeleteUseTerminalSequences() {
        assertBytes("\u001b[Z".bytes(), KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)
        assertBytes("\u001bOP".bytes(), KeyEvent.KEYCODE_F1)
        assertBytes("\u001b[24~".bytes(), KeyEvent.KEYCODE_F12)
        assertBytes("\u001b[3~".bytes(), KeyEvent.KEYCODE_FORWARD_DEL)
    }

    @Test
    fun altPrintablePrefixesEscape() {
        assertBytes("\u001bx".bytes(), KeyEvent.KEYCODE_X, KeyEvent.META_ALT_ON, 'x'.code)
    }

    @Test
    fun screenShortcutsUseHostMappings() {
        assertArrayEquals(
            "\u001b[A".bytes(),
            HostPluginInput.terminalShortcut(PluginTerminalKey.UP)
        )
        assertArrayEquals(
            byteArrayOf(3),
            HostPluginInput.terminalShortcut(PluginTerminalKey.CONTROL_C)
        )
    }

    private fun assertBytes(
        expected: ByteArray,
        keyCode: Int,
        metaState: Int = 0,
        unicodeCodePoint: Int = 0
    ) {
        assertArrayEquals(
            expected,
            HostPluginInput.terminalHardware(keyCode, metaState, unicodeCodePoint)
        )
    }

    private fun String.bytes() = toByteArray(Charsets.UTF_8)
}
