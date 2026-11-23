package com.ducvd89.bloodborne;

import android.app.Activity;
import android.os.Bundle;

/** Lets Android keep the Kishi's USB access for the game. Because this activity is declared for the Kishi (USB_DEVICE_ATTACHED,
 *  res/xml/kishi_usb_devices.xml), the access dialog offers "Always open Bloodborne when Razer Kishi V3 Pro is connected". Once
 *  that is ticked, Android grants the access by itself whenever the Kishi appears: plugged in, and after every DeviceReset
 *  (the Kishi restarts and comes back as a new USB device, see KishiIdleReset), so no dialog interrupts the game again.
 *  Android starts this activity at those moments; it shows nothing and closes at once. A running game claims the
 *  haptics from its own attach broadcast (KishiHaptics). */
public final class KishiUsbActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        finish();
    }
}
