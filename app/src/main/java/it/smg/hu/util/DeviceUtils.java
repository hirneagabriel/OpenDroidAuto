package it.smg.hu.util;

import android.os.Build;

public class DeviceUtils {

    public static final boolean isX86 = "i686".equalsIgnoreCase(System.getProperty("os.arch"));

    // Android emulator (goldfish/ranchu virtual hardware), used for off-car development
    public static final boolean isEmulator = "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE);
}
