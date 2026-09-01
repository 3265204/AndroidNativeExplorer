package com.ane.filemanager.input

import android.view.KeyEvent
import com.ane.filemanager.plugin.api.input.AnePluginInput
import com.ane.filemanager.plugin.api.input.PluginTerminalKey

/** Host keyboard policy shared with terminal-capable plugins through PluginHost.input. */
internal object HostPluginInput : AnePluginInput {
    override fun terminalShortcut(key: PluginTerminalKey): ByteArray = when (key) {
        PluginTerminalKey.ESCAPE -> bytes("\u001b")
        PluginTerminalKey.TAB -> bytes("\t")
        PluginTerminalKey.UP -> bytes("\u001b[A")
        PluginTerminalKey.DOWN -> bytes("\u001b[B")
        PluginTerminalKey.LEFT -> bytes("\u001b[D")
        PluginTerminalKey.RIGHT -> bytes("\u001b[C")
        PluginTerminalKey.CONTROL_C -> byteArrayOf(3)
        PluginTerminalKey.CONTROL_D -> byteArrayOf(4)
    }

    override fun terminalHardware(
        keyCode: Int,
        metaState: Int,
        unicodeCodePoint: Int
    ): ByteArray? {
        val ctrl = metaState and KeyEvent.META_CTRL_MASK != 0
        val alt = metaState and KeyEvent.META_ALT_MASK != 0
        val shift = metaState and KeyEvent.META_SHIFT_MASK != 0

        if (ctrl) {
            control(keyCode, shift)?.let { return withAlt(byteArrayOf(it), alt) }
        }

        val sequence = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> bytes("\r")
            KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7f)
            KeyEvent.KEYCODE_FORWARD_DEL -> tilde(3, metaState)
            KeyEvent.KEYCODE_INSERT -> tilde(2, metaState)
            KeyEvent.KEYCODE_TAB -> if (shift) bytes("\u001b[Z") else bytes("\t")
            KeyEvent.KEYCODE_ESCAPE -> bytes("\u001b")
            KeyEvent.KEYCODE_DPAD_UP -> cursor('A', metaState)
            KeyEvent.KEYCODE_DPAD_DOWN -> cursor('B', metaState)
            KeyEvent.KEYCODE_DPAD_RIGHT -> cursor('C', metaState)
            KeyEvent.KEYCODE_DPAD_LEFT -> cursor('D', metaState)
            KeyEvent.KEYCODE_MOVE_HOME -> cursor('H', metaState)
            KeyEvent.KEYCODE_MOVE_END -> cursor('F', metaState)
            KeyEvent.KEYCODE_PAGE_UP -> tilde(5, metaState)
            KeyEvent.KEYCODE_PAGE_DOWN -> tilde(6, metaState)
            KeyEvent.KEYCODE_F1 -> function('P', metaState)
            KeyEvent.KEYCODE_F2 -> function('Q', metaState)
            KeyEvent.KEYCODE_F3 -> function('R', metaState)
            KeyEvent.KEYCODE_F4 -> function('S', metaState)
            KeyEvent.KEYCODE_F5 -> tilde(15, metaState)
            KeyEvent.KEYCODE_F6 -> tilde(17, metaState)
            KeyEvent.KEYCODE_F7 -> tilde(18, metaState)
            KeyEvent.KEYCODE_F8 -> tilde(19, metaState)
            KeyEvent.KEYCODE_F9 -> tilde(20, metaState)
            KeyEvent.KEYCODE_F10 -> tilde(21, metaState)
            KeyEvent.KEYCODE_F11 -> tilde(23, metaState)
            KeyEvent.KEYCODE_F12 -> tilde(24, metaState)
            else -> null
        }
        if (sequence != null) {
            val modifierAware = keyCode in MODIFIER_AWARE_KEYS
            return if (alt && !modifierAware) withAlt(sequence, true) else sequence
        }
        if (unicodeCodePoint <= 0 || !Character.isValidCodePoint(unicodeCodePoint)) return null
        return withAlt(bytes(String(Character.toChars(unicodeCodePoint))), alt)
    }

    override fun terminalCharacters(value: String, metaState: Int): ByteArray = withAlt(
        bytes(value),
        metaState and KeyEvent.META_ALT_MASK != 0
    )

    private fun control(keyCode: Int, shift: Boolean): Byte? = when {
        keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
            (keyCode - KeyEvent.KEYCODE_A + 1).toByte()
        keyCode == KeyEvent.KEYCODE_SPACE -> 0
        keyCode == KeyEvent.KEYCODE_LEFT_BRACKET -> 0x1b
        keyCode == KeyEvent.KEYCODE_BACKSLASH -> 0x1c
        keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x1d
        keyCode == KeyEvent.KEYCODE_6 -> 0x1e
        keyCode == KeyEvent.KEYCODE_MINUS -> 0x1f
        keyCode == KeyEvent.KEYCODE_SLASH && shift -> 0x7f
        else -> null
    }

    private fun cursor(final: Char, metaState: Int): ByteArray {
        val modifier = modifier(metaState)
        return bytes(if (modifier == 1) "\u001b[$final" else "\u001b[1;${modifier}$final")
    }

    private fun function(final: Char, metaState: Int): ByteArray {
        val modifier = modifier(metaState)
        return bytes(if (modifier == 1) "\u001bO$final" else "\u001b[1;${modifier}$final")
    }

    private fun tilde(number: Int, metaState: Int): ByteArray {
        val modifier = modifier(metaState)
        return bytes(if (modifier == 1) "\u001b[$number~" else "\u001b[$number;${modifier}~")
    }

    private fun modifier(metaState: Int): Int = 1 +
        (if (metaState and KeyEvent.META_SHIFT_MASK != 0) 1 else 0) +
        (if (metaState and KeyEvent.META_ALT_MASK != 0) 2 else 0) +
        (if (metaState and KeyEvent.META_CTRL_MASK != 0) 4 else 0)

    private fun withAlt(value: ByteArray, alt: Boolean): ByteArray = if (!alt) value else {
        byteArrayOf(0x1b) + value
    }

    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8)

    private val MODIFIER_AWARE_KEYS = setOf(
        KeyEvent.KEYCODE_FORWARD_DEL,
        KeyEvent.KEYCODE_INSERT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_MOVE_HOME,
        KeyEvent.KEYCODE_MOVE_END,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_F1,
        KeyEvent.KEYCODE_F2,
        KeyEvent.KEYCODE_F3,
        KeyEvent.KEYCODE_F4,
        KeyEvent.KEYCODE_F5,
        KeyEvent.KEYCODE_F6,
        KeyEvent.KEYCODE_F7,
        KeyEvent.KEYCODE_F8,
        KeyEvent.KEYCODE_F9,
        KeyEvent.KEYCODE_F10,
        KeyEvent.KEYCODE_F11,
        KeyEvent.KEYCODE_F12
    )
}
