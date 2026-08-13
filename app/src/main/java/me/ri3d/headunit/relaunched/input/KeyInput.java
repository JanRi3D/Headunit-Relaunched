package me.ri3d.headunit.relaunched.input;

import android.view.KeyEvent;

import static me.ri3d.headunit.relaunched.protocol.ProtocolConstants.*;

/**
 * Translates Android key codes into Android Auto button codes.
 *
 * Head units wire their steering-wheel and panel buttons to whatever key codes
 * the ROM's keylayout files produce, so this table is the file you will edit
 * for your own hardware. Anything not listed here is left to the system.
 */
public final class KeyInput {

    /** Swapped out whenever a session starts or ends; null means "not connected". */
    private volatile InputChannel input;

    public void setChannel(InputChannel c) { input = c; }

    /** @return true if the key was consumed and forwarded to the phone. */
    public boolean onKey(int keyCode, KeyEvent event) {
        InputChannel input = this.input;
        if (input == null) return false;

        int aa = map(keyCode);
        if (aa < 0) return false;

        boolean pressed = (event.getAction() == KeyEvent.ACTION_DOWN);
        if (event.getRepeatCount() > 0) return true; // swallow auto-repeat
        input.sendButton(aa, pressed);
        return true;
    }

    private static int map(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_HOME:              return BTN_HOME;
            case KeyEvent.KEYCODE_BACK:              return BTN_BACK;
            case KeyEvent.KEYCODE_CALL:              return BTN_CALL;
            case KeyEvent.KEYCODE_ENDCALL:           return BTN_END_CALL;
            case KeyEvent.KEYCODE_DPAD_UP:           return BTN_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:         return BTN_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:         return BTN_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:        return BTN_RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:             return BTN_ENTER;
            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_VOICE_ASSIST:      return BTN_MICROPHONE;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:       return BTN_PLAY_PAUSE;
            case KeyEvent.KEYCODE_MEDIA_STOP:        return BTN_STOP;
            case KeyEvent.KEYCODE_MEDIA_NEXT:        return BTN_NEXT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:    return BTN_PREV;
            default:                                 return -1;
        }
    }
}
