package me.ri3d.headunit.relaunched;

/**
 * What the head unit is showing, and the handful of facts that decide it.
 *
 * This used to be six correlated booleans on the activity that seven callbacks
 * set by hand before re-deriving two view visibilities from five of them. The
 * combinations were only ever correct because the comments were good. Naming
 * the three screens makes the illegal combinations unreachable and -- since
 * nothing here touches a View -- makes the rule testable on a JVM.
 *
 * Plain public fields: this is a value holder for one activity, and a pair of
 * accessors per flag would be longer than the class.
 */
public final class ScreenState {

    public enum Screen {
        /** Our own control panel, over the video or on its own. */
        PANEL,
        /** Android Auto has the screen to itself. */
        VIDEO,
        /** Full-screen spinner: a reconnect or a network sweep is running. */
        BUSY,
        /** The settings destination, opened from the panel. */
        SETTINGS
    }

    /** The session is up and the phone is drawing. */
    public boolean projecting;
    /** The service is bringing a session back without the user asking. */
    public boolean retrying;
    /** A network sweep is running. */
    public boolean scanning;
    /** A connection attempt is in flight: asked for, not yet up or failed. */
    public boolean connecting;
    /** Long-press BACK forced the panel back over a live session. */
    public boolean panelPinned;
    /** A tap uncovered the panel; whatever was running carries on behind it. */
    public boolean busyDismissed;
    /** The user opened settings. */
    public boolean settingsOpen;

    public Screen current() {
        // Settings outranks everything, including a session coming back. It is
        // the one screen the user is standing on deliberately, and changing the
        // resolution rebuilds the session on purpose -- being thrown out to a
        // spinner halfway through adjusting it is how you end up adjusting it
        // three times.
        if (settingsOpen) return Screen.SETTINGS;
        // BUSY wins over the panel: two stacked overlays read as a glitch, and
        // the panel is one tap underneath.
        if ((retrying || scanning) && !projecting && !busyDismissed) return Screen.BUSY;
        return (projecting && !panelPinned) ? Screen.VIDEO : Screen.PANEL;
    }

    /**
     * True when Disconnect has anything to act on: a live session, a retry, or a
     * sweep. Red on an idle panel is the loudest thing on the screen pointing at
     * the one control that would do nothing.
     */
    public boolean canStop() {
        return projecting || retrying || scanning || connecting;
    }

    /**
     * The session reached "authenticated".
     *
     * Latched rather than compared: "authenticated" is followed by "negotiating
     * channels", so testing the current state put the panel straight back over
     * the video.
     */
    public void projectionStarted() {
        projecting = true;
        retrying = false;
        connecting = false;
        busyDismissed = false;  // arm the busy screen again for the next drop
    }

    /**
     * The session ended.
     *
     * @param comingBack true when it returns on its own -- a replug. Only a drop
     *                   nobody asked for gets the busy screen; a Disconnect tap
     *                   or Android Auto's exit button lands on the panel, which
     *                   is where that user was heading anyway.
     */
    public void projectionEnded(boolean comingBack) {
        projecting = false;
        panelPinned = false;
        connecting = false;
        retrying = comingBack;
    }

    /** The user started a connection by hand: show them the panel, not a spinner. */
    public void connectRequested() {
        panelPinned = false;
        retrying = false;
        busyDismissed = false;
        connecting = true;
    }

    /** The user pressed Disconnect. Nothing is left to stop. */
    public void stopRequested() {
        retrying = false;
        connecting = false;
    }
}
