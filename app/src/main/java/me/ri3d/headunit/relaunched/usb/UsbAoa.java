package me.ri3d.headunit.relaunched.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.util.HashMap;
import java.util.Iterator;

import me.ri3d.headunit.relaunched.util.Logger;
import me.ri3d.headunit.relaunched.util.Utils;

/**
 * Android Open Accessory (AOA) protocol, host side.
 *
 * We are the USB *host* (the head unit) and the phone is the accessory. The
 * sequence is:
 *
 *   1. ask the phone for its AOA protocol version (control IN, request 51)
 *   2. send six identity strings (control OUT, request 52)
 *   3. tell it to enter accessory mode (control OUT, request 53)
 *   4. the phone drops off the bus and re-enumerates as 18D1:2D0x
 *   5. bulk IN/OUT on interface 0 is now the Android Auto byte pipe
 *
 * The magic is in the strings: manufacturer "Android" + model "Android Auto"
 * is what makes the phone launch Android Auto instead of prompting for some
 * random accessory app.
 */
public final class UsbAoa {

    private UsbAoa() {}

    // AOA control requests
    private static final int REQ_GET_PROTOCOL = 51;
    private static final int REQ_SEND_STRING  = 52;
    private static final int REQ_START        = 53;

    // string indices
    private static final int STR_MANUFACTURER = 0;
    private static final int STR_MODEL        = 1;
    private static final int STR_DESCRIPTION  = 2;
    private static final int STR_VERSION      = 3;
    private static final int STR_URI          = 4;
    private static final int STR_SERIAL       = 5;

    private static final String AOA_MANUFACTURER = "Android";
    private static final String AOA_MODEL        = "Android Auto";
    private static final String AOA_DESCRIPTION  = "Android Auto";
    private static final String AOA_VERSION      = "2.0.1";
    private static final String AOA_URI          = "https://www.android.com/auto/";
    private static final String AOA_SERIAL       = "HU-AAAAAA001";

    public static final int GOOGLE_VID = 0x18D1;
    private static final int ACC_PID_MIN = 0x2D00;
    private static final int ACC_PID_MAX = 0x2D05;

    private static final int TIMEOUT_MS = 1000;

    /** True once the phone has re-enumerated in accessory mode. */
    public static boolean isAccessory(UsbDevice d) {
        return d.getVendorId() == GOOGLE_VID
                && d.getProductId() >= ACC_PID_MIN
                && d.getProductId() <= ACC_PID_MAX;
    }

    /** First attached device already in accessory mode, or null. */
    public static UsbDevice findAccessory(UsbManager um) {
        HashMap<String, UsbDevice> list = um.getDeviceList();
        for (Iterator<UsbDevice> it = list.values().iterator(); it.hasNext(); ) {
            UsbDevice d = it.next();
            if (isAccessory(d)) return d;
        }
        return null;
    }

    /**
     * First attached device that is a plausible phone, i.e. not already in
     * accessory mode and not a hub. We do not try to whitelist vendors -- any
     * device that answers request 51 with a protocol >= 1 speaks AOA.
     */
    public static UsbDevice findCandidate(UsbManager um) {
        HashMap<String, UsbDevice> list = um.getDeviceList();
        for (Iterator<UsbDevice> it = list.values().iterator(); it.hasNext(); ) {
            UsbDevice d = it.next();
            if (isAccessory(d)) continue;
            if (d.getDeviceClass() == UsbConstants.USB_CLASS_HUB) continue;
            return d;
        }
        return null;
    }

    /**
     * Switches a phone into accessory mode. The connection is unusable
     * afterwards -- the device detaches and comes back with a new PID.
     *
     * @return true if the start command was accepted.
     */
    public static boolean startAccessoryMode(UsbDeviceConnection conn) {
        byte[] buf = new byte[2];
        int r = conn.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_VENDOR,
                REQ_GET_PROTOCOL, 0, 0, buf, 2, TIMEOUT_MS);
        if (r < 2) {
            Logger.w("AOA: no protocol response (" + r + ") -- not an AOA device");
            return false;
        }
        // protocol version is little endian here, unlike the AA protocol itself
        int proto = (buf[0] & 0xFF) | ((buf[1] & 0xFF) << 8);
        Logger.i("AOA: protocol version " + proto);
        if (proto < 1) return false;

        if (!sendString(conn, STR_MANUFACTURER, AOA_MANUFACTURER)) return false;
        if (!sendString(conn, STR_MODEL,        AOA_MODEL))        return false;
        if (!sendString(conn, STR_DESCRIPTION,  AOA_DESCRIPTION))  return false;
        if (!sendString(conn, STR_VERSION,      AOA_VERSION))      return false;
        if (!sendString(conn, STR_URI,          AOA_URI))          return false;
        if (!sendString(conn, STR_SERIAL,       AOA_SERIAL))       return false;

        r = conn.controlTransfer(
                UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR,
                REQ_START, 0, 0, null, 0, TIMEOUT_MS);
        Logger.i("AOA: start accessory -> " + r);
        return r >= 0;
    }

    private static boolean sendString(UsbDeviceConnection conn, int index, String value) {
        byte[] data = Utils.utf8(value + "\0");
        int r = conn.controlTransfer(
                UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR,
                REQ_SEND_STRING, 0, index, data, data.length, TIMEOUT_MS);
        if (r < 0) Logger.w("AOA: sendString[" + index + "] failed");
        return r >= 0;
    }

    /** Bulk interface of an accessory-mode device. Interface 0 is the AA pipe. */
    public static UsbInterface bulkInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface iface = d.getInterface(i);
            boolean in = false, out = false;
            for (int e = 0; e < iface.getEndpointCount(); e++) {
                if (iface.getEndpoint(e).getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (iface.getEndpoint(e).getDirection() == UsbConstants.USB_DIR_IN) in = true;
                else out = true;
            }
            if (in && out) return iface;
        }
        return null;
    }
}
