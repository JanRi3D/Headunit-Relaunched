package me.ri3d.headunit.relaunched;

import org.junit.Test;

import static me.ri3d.headunit.relaunched.ScreenState.Screen;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The overlay rules, which used to live as a conjunction of six booleans inside
 * an Activity and could therefore only be checked by plugging a phone in.
 *
 * Run with: gradlew :app:testDebugUnitTest
 */
public class ScreenStateTest {

    private final ScreenState s = new ScreenState();

    @Test public void idleShowsThePanel() {
        assertEquals(Screen.PANEL, s.current());
    }

    @Test public void aLiveSessionHidesEverything() {
        s.projectionStarted();
        assertEquals(Screen.VIDEO, s.current());
    }

    /** Long-press BACK, which is the only way to reach Disconnect while projecting. */
    @Test public void pinningBringsThePanelBackOverVideo() {
        s.projectionStarted();
        s.panelPinned = true;
        assertEquals(Screen.PANEL, s.current());
    }

    @Test public void anUnaskedForDropShowsTheSpinner() {
        s.projectionStarted();
        s.projectionEnded(true);
        assertEquals(Screen.BUSY, s.current());
    }

    /** A Disconnect tap, or Android Auto's exit button: no spinner, just the panel. */
    @Test public void aDeliberateStopLandsOnThePanel() {
        s.projectionStarted();
        s.projectionEnded(false);
        assertEquals(Screen.PANEL, s.current());
    }

    /** A tap on the spinner uncovers the panel; the retry carries on behind it. */
    @Test public void dismissingTheSpinnerUncoversThePanelWithoutStoppingTheRetry() {
        s.projectionEnded(true);
        s.busyDismissed = true;
        assertEquals(Screen.PANEL, s.current());
        assertTrue("the retry itself must not be cancelled", s.retrying);
    }

    /** And the spinner is armed again for the next drop, not dismissed forever. */
    @Test public void theNextDropGetsItsOwnSpinner() {
        s.projectionEnded(true);
        s.busyDismissed = true;
        s.projectionStarted();
        s.projectionEnded(true);
        assertEquals(Screen.BUSY, s.current());
    }

    @Test public void aSweepUsesTheSameSpinner() {
        s.scanning = true;
        assertEquals(Screen.BUSY, s.current());
        s.scanning = false;
        assertEquals(Screen.PANEL, s.current());
    }

    /**
     * Video wins over the spinner. Two stacked overlays read as a glitch, and a
     * retry that succeeded has nothing left to wait for.
     */
    @Test public void videoOutranksTheSpinner() {
        s.retrying = true;
        s.scanning = true;
        s.projecting = true;
        assertEquals(Screen.VIDEO, s.current());
    }

    /** Disconnect is only loud when it has something to act on. */
    @Test public void disconnectIsInertUntilThereIsSomethingToStop() {
        assertFalse("nothing running", s.canStop());
        s.projectionStarted();
        assertTrue("a live session", s.canStop());
        s.projectionEnded(true);
        assertTrue("a retry is worth stopping", s.canStop());
        s.projectionEnded(false);
        assertFalse(s.canStop());
        s.scanning = true;
        assertTrue("a sweep is worth stopping", s.canStop());
        s.scanning = false;

        // The gap that showed up on a real handshake: a session on its way up is
        // not projecting, not retrying and not scanning, and Disconnect was dim
        // through the whole of it.
        s.connectRequested();
        assertTrue("a connection in flight is worth stopping", s.canStop());
        s.stopRequested();
        assertFalse(s.canStop());
    }

    @Test public void settingsOpenFromThePanel() {
        s.settingsOpen = true;
        assertEquals(Screen.SETTINGS, s.current());
        s.settingsOpen = false;
        assertEquals(Screen.PANEL, s.current());
    }

    /**
     * Changing the resolution rebuilds the session on purpose. Being thrown out
     * of settings to a spinner halfway through adjusting it is how you end up
     * adjusting it three times.
     */
    @Test public void settingsSurviveTheReconnectTheyCaused() {
        s.projectionStarted();
        s.settingsOpen = true;
        s.projectionEnded(true);          // the renegotiate drops the session
        assertEquals(Screen.SETTINGS, s.current());
        s.projectionStarted();            // and it comes back
        assertEquals(Screen.SETTINGS, s.current());
    }

    /** Closing settings hands the screen back to whatever was underneath. */
    @Test public void closingSettingsRevealsTheStateUnderneath() {
        s.projectionStarted();
        s.settingsOpen = true;
        s.settingsOpen = false;
        assertEquals(Screen.VIDEO, s.current());
    }

    /** Connecting by hand means the user wants the panel, not a spinner. */
    @Test public void connectingByHandClearsTheSpinnerAndThePin() {
        s.projectionEnded(true);
        s.panelPinned = true;
        s.connectRequested();
        assertEquals(Screen.PANEL, s.current());
        assertFalse(s.retrying);
    }
}
