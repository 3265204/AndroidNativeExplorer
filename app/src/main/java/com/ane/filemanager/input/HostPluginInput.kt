package com.ane.filemanager.input

import android.view.KeyEvent
import com.ane.filemanager.plugin.api.input.AnePluginInput
import com.ane.filemanager.plugin.api.input.PluginTerminalKey

/** Host keyboard policy shared with terminal-capable plugins through PluginHost.input. */
internal object HostPluginInput : AnePluginInput {
    override fun terminalShortcut(key: PluginTerminalKey): ByteArray =
        SHORTCUT_SEQUENCES.getValue(key).copyOf()

    /**
     * Converts Android hardware keys to the xterm-compatible sequences expected by the PTY.
     * Entries marked modifier-aware encode Shift/Alt/Ctrl with xterm's `1 + mask` parameter;
     * ordinary keys receive the traditional ESC prefix for Alt instead.
     */
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

        HARDWARE_SEQUENCES[keyCode]?.let { mapping ->
            val sequence = mapping.sequence(metaState, shift)
            return if (alt && !mapping.modifierAware) withAlt(sequence, true) else sequence
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
        keyCode == KeyEvent.KEYCODE_SPACE -> NULL_BYTE
        keyCode == KeyEvent.KEYCODE_LEFT_BRACKET -> ESCAPE_BYTE
        keyCode == KeyEvent.KEYCODE_BACKSLASH -> CONTROL_BACKSLASH_BYTE
        keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET -> CONTROL_RIGHT_BRACKET_BYTE
        keyCode == KeyEvent.KEYCODE_6 -> CONTROL_CARET_BYTE
        keyCode == KeyEvent.KEYCODE_MINUS -> CONTROL_UNDERSCORE_BYTE
        keyCode == KeyEvent.KEYCODE_SLASH && shift -> DELETE_BYTE
        else -> null
    }

    private fun cursor(final: Char, metaState: Int): ByteArray {
        val modifier = modifier(metaState)
        return bytes(if (modifier == 1) "$CSI$final" else "${CSI}1;${modifier}$final")
    }

    private fun function(final: Char, metaState: Int): ByteArray {
        val modifier = modifier(metaState)
        return bytes(if (modifier == 1) "$SS3$final" else "${CSI}1;${modifier}$final")
    }

    private fun tilde(number: Int, metaState: Int): ByteArray {
        val modifier = modifier(metaState)
        return bytes(if (modifier == 1) "$CSI$number~" else "$CSI$number;${modifier}~")
    }

    private fun modifier(metaState: Int): Int = 1 +
        (if (metaState and KeyEvent.META_SHIFT_MASK != 0) 1 else 0) +
        (if (metaState and KeyEvent.META_ALT_MASK != 0) 2 else 0) +
        (if (metaState and KeyEvent.META_CTRL_MASK != 0) 4 else 0)

    private fun withAlt(value: ByteArray, alt: Boolean): ByteArray = if (!alt) value else {
        byteArrayOf(ESCAPE_BYTE) + value
    }

    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8)

    private data class HardwareSequence(
        val modifierAware: Boolean = false,
        val sequence: (metaState: Int, shift: Boolean) -> ByteArray
    )

    private val SHORTCUT_SEQUENCES = mapOf(
        PluginTerminalKey.ESCAPE to byteArrayOf(ESCAPE_BYTE),
        PluginTerminalKey.TAB to byteArrayOf(TAB_BYTE),
        PluginTerminalKey.UP to bytes("${CSI}A"),
        PluginTerminalKey.DOWN to bytes("${CSI}B"),
        PluginTerminalKey.LEFT to bytes("${CSI}D"),
        PluginTerminalKey.RIGHT to bytes("${CSI}C"),
        PluginTerminalKey.CONTROL_C to byteArrayOf(CONTROL_C_BYTE),
        PluginTerminalKey.CONTROL_D to byteArrayOf(CONTROL_D_BYTE)
    )

    private val HARDWARE_SEQUENCES = mapOf(
        KeyEvent.KEYCODE_ENTER to plain(CARRIAGE_RETURN_BYTE),
        KeyEvent.KEYCODE_NUMPAD_ENTER to plain(CARRIAGE_RETURN_BYTE),
        KeyEvent.KEYCODE_DEL to plain(DELETE_BYTE),
        KeyEvent.KEYCODE_ESCAPE to plain(ESCAPE_BYTE),
        KeyEvent.KEYCODE_TAB to HardwareSequence { _, shift ->
            if (shift) bytes("${CSI}Z") else byteArrayOf(TAB_BYTE)
        },
        KeyEvent.KEYCODE_FORWARD_DEL to modified { tilde(3, it) },
        KeyEvent.KEYCODE_INSERT to modified { tilde(2, it) },
        KeyEvent.KEYCODE_DPAD_UP to modified { cursor('A', it) },
        KeyEvent.KEYCODE_DPAD_DOWN to modified { cursor('B', it) },
        KeyEvent.KEYCODE_DPAD_RIGHT to modified { cursor('C', it) },
        KeyEvent.KEYCODE_DPAD_LEFT to modified { cursor('D', it) },
        KeyEvent.KEYCODE_MOVE_HOME to modified { cursor('H', it) },
        KeyEvent.KEYCODE_MOVE_END to modified { cursor('F', it) },
        KeyEvent.KEYCODE_PAGE_UP to modified { tilde(5, it) },
        KeyEvent.KEYCODE_PAGE_DOWN to modified { tilde(6, it) },
        KeyEvent.KEYCODE_F1 to modified { function('P', it) },
        KeyEvent.KEYCODE_F2 to modified { function('Q', it) },
        KeyEvent.KEYCODE_F3 to modified { function('R', it) },
        KeyEvent.KEYCODE_F4 to modified { function('S', it) },
        KeyEvent.KEYCODE_F5 to modified { tilde(15, it) },
        KeyEvent.KEYCODE_F6 to modified { tilde(17, it) },
        KeyEvent.KEYCODE_F7 to modified { tilde(18, it) },
        KeyEvent.KEYCODE_F8 to modified { tilde(19, it) },
        KeyEvent.KEYCODE_F9 to modified { tilde(20, it) },
        KeyEvent.KEYCODE_F10 to modified { tilde(21, it) },
        KeyEvent.KEYCODE_F11 to modified { tilde(23, it) },
        KeyEvent.KEYCODE_F12 to modified { tilde(24, it) }
    )

    private fun plain(value: Byte) = HardwareSequence { _, _ -> byteArrayOf(value) }

    private fun modified(sequence: (metaState: Int) -> ByteArray) =
        HardwareSequence(modifierAware = true) { metaState, _ -> sequence(metaState) }

    private const val ESCAPE = '\u001b'
    private const val CSI = "$ESCAPE["
    private const val SS3 = "${ESCAPE}O"
    private const val ESCAPE_BYTE: Byte = 0x1b
    private const val TAB_BYTE: Byte = 0x09
    private const val CARRIAGE_RETURN_BYTE: Byte = 0x0d
    private const val CONTROL_C_BYTE: Byte = 0x03
    private const val CONTROL_D_BYTE: Byte = 0x04
    private const val NULL_BYTE: Byte = 0x00
    private const val CONTROL_BACKSLASH_BYTE: Byte = 0x1c
    private const val CONTROL_RIGHT_BRACKET_BYTE: Byte = 0x1d
    private const val CONTROL_CARET_BYTE: Byte = 0x1e
    private const val CONTROL_UNDERSCORE_BYTE: Byte = 0x1f
    private const val DELETE_BYTE: Byte = 0x7f
}
