package me.ri3d.headunit.relaunched.input;

import android.view.MotionEvent;
import android.view.View;

import me.ri3d.headunit.relaunched.Settings;

import static me.ri3d.headunit.relaunched.protocol.ProtocolConstants.*;

/**
 * Maps SurfaceView touches to Android Auto touch events.
 *
 * The phone renders at the negotiated stream size regardless of the physical
 * panel, so coordinates are scaled from the view's pixels into that space.
 * Single touch only: AA's own UI is designed for it, and tracking extra
 * pointers costs allocations on every move event for no benefit.
 */
public final class TouchInput implements View.OnTouchListener {

    /** Swapped out whenever a session starts or ends; null means "not connected". */
    private volatile InputChannel input;

    public void setChannel(InputChannel c) { input = c; }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        InputChannel input = this.input;
        if (input == null) return true;

        int vw = v.getWidth();
        int vh = v.getHeight();
        if (vw <= 0 || vh <= 0) return true;

        int sw = Settings.videoWidth();
        int sh = Settings.videoHeight();
        int x = (int) (e.getX() * sw / vw);
        int y = (int) (e.getY() * sh / vh);
        if (x < 0) x = 0; else if (x >= sw) x = sw - 1;
        if (y < 0) y = 0; else if (y >= sh) y = sh - 1;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                input.sendTouch(TOUCH_PRESS, x, y);
                break;
            case MotionEvent.ACTION_MOVE:
                input.sendTouch(TOUCH_DRAG, x, y);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                input.sendTouch(TOUCH_RELEASE, x, y);
                break;
            default:
                return true;
        }
        return true;
    }
}
