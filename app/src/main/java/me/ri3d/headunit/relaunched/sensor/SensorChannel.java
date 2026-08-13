package me.ri3d.headunit.relaunched.sensor;

import java.io.IOException;

import me.ri3d.headunit.relaunched.protocol.Messages;
import me.ri3d.headunit.relaunched.protocol.MessageWriter;
import me.ri3d.headunit.relaunched.protocol.Proto;
import me.ri3d.headunit.relaunched.util.Logger;

import static me.ri3d.headunit.relaunched.protocol.ProtocolConstants.*;

/**
 * Sensor channel. We advertise exactly two sensors and both are effectively
 * constants:
 *
 *  - driving status: 0 = unrestricted. Report anything else and the phone locks
 *    the UI into its parked-car restrictions, which looks like a broken app.
 *  - night mode: drives AA's dark theme.
 *
 * The phone will not start video until it has heard from the driving status
 * sensor it asked for, so the reply to SENSOR_START_REQUEST must be followed
 * immediately by an event.
 */
public final class SensorChannel {

    private final MessageWriter writer;
    private final Proto.R reader = new Proto.R();

    private boolean night;

    public SensorChannel(MessageWriter writer) {
        this.writer = writer;
    }

    public void onMessage(int msgId, byte[] buf, int off, int len) throws IOException {
        switch (msgId) {
            case SENSOR_START_REQUEST: {
                int type = (int) Messages.varintField(reader, buf, off, len, 1, 0);
                Logger.i("sensor: start request for type " + type);

                Proto.W w = writer.begin();
                Messages.sensorStartResponse(w, STATUS_OK);
                writer.end(CH_SENSOR, SENSOR_START_RESPONSE);

                if (type == SENSOR_DRIVING_STATUS) sendDrivingStatus(0);
                else if (type == SENSOR_NIGHT_DATA) sendNightMode(night);
                break;
            }
            default:
                Logger.d("sensor: unhandled msg 0x" + Integer.toHexString(msgId));
                break;
        }
    }

    /** 0 = unrestricted. Set non-zero only if you actually gate the UI while moving. */
    public void sendDrivingStatus(int status) throws IOException {
        Proto.W w = writer.begin();
        Messages.drivingStatus(w, status);
        writer.end(CH_SENSOR, SENSOR_EVENT);
    }

    /** Call from your light sensor or a day/night button to flip AA's theme. */
    public void sendNightMode(boolean isNight) throws IOException {
        night = isNight;
        Proto.W w = writer.begin();
        Messages.nightMode(w, isNight);
        writer.end(CH_SENSOR, SENSOR_EVENT);
    }
}
