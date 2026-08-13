package me.ri3d.headunit.relaunched.input;

import java.io.IOException;

import me.ri3d.headunit.relaunched.protocol.Messages;
import me.ri3d.headunit.relaunched.protocol.MessageWriter;
import me.ri3d.headunit.relaunched.protocol.Proto;
import me.ri3d.headunit.relaunched.util.Logger;

import static me.ri3d.headunit.relaunched.protocol.ProtocolConstants.*;

/**
 * Input channel: everything the head unit sends *to* the phone -- touches,
 * hardware buttons, rotary encoders.
 *
 * Called from the UI thread. MessageWriter serialises for us, so no extra
 * handler or queue is needed here.
 */
public final class InputChannel {

    private final MessageWriter writer;

    public InputChannel(MessageWriter writer) {
        this.writer = writer;
    }

    public void onMessage(int msgId, byte[] buf, int off, int len) throws IOException {
        switch (msgId) {
            case IN_BINDING_REQUEST: {
                // The phone lists the scan codes it wants; we accept the lot.
                Proto.W w = writer.begin();
                Messages.bindingResponse(w, STATUS_OK);
                writer.end(CH_INPUT, IN_BINDING_RESPONSE);
                Logger.i("input: binding ok");
                break;
            }
            default:
                Logger.d("input: unhandled msg 0x" + Integer.toHexString(msgId));
                break;
        }
    }

    /** AA wants nanoseconds since boot; the exact epoch does not matter, monotonicity does. */
    private static long now() { return System.nanoTime(); }

    public void sendTouch(int action, int x, int y) {
        try {
            Proto.W w = writer.begin();
            Messages.touchEvent(w, now(), action, x, y);
            writer.end(CH_INPUT, IN_EVENT_INDICATION);
        } catch (IOException e) {
            Logger.w("input: touch send failed: " + e);
        }
    }

    public void sendButton(int scanCode, boolean pressed) {
        try {
            Proto.W w = writer.begin();
            Messages.buttonEvent(w, now(), scanCode, pressed);
            writer.end(CH_INPUT, IN_EVENT_INDICATION);
        } catch (IOException e) {
            Logger.w("input: button send failed: " + e);
        }
    }

    /** One click of a rotary encoder. Negative is counter-clockwise. */
    public void sendScroll(int delta) {
        try {
            Proto.W w = writer.begin();
            Messages.scrollEvent(w, now(), delta);
            writer.end(CH_INPUT, IN_EVENT_INDICATION);
        } catch (IOException e) {
            Logger.w("input: scroll send failed: " + e);
        }
    }
}
