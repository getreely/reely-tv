package tv.reely.ui.components

import androidx.compose.ui.input.key.Key

/** The number keys on a remote that has them, as the digit each one types. */
private val DIGIT_KEYS = mapOf(
    Key.Zero to '0', Key.One to '1', Key.Two to '2', Key.Three to '3', Key.Four to '4',
    Key.Five to '5', Key.Six to '6', Key.Seven to '7', Key.Eight to '8', Key.Nine to '9',
    Key.NumPad0 to '0', Key.NumPad1 to '1', Key.NumPad2 to '2', Key.NumPad3 to '3',
    Key.NumPad4 to '4', Key.NumPad5 to '5', Key.NumPad6 to '6', Key.NumPad7 to '7',
    Key.NumPad8 to '8', Key.NumPad9 to '9',
)

/** The digit a key types, or null for any other key. */
fun digitOf(key: Key): Char? = DIGIT_KEYS[key]
