package org.ryzemcbe.central;

/**
 * Build-time smoke test only. This class is not part of the published
 * libdatachannel Java artifact: it lives under the test source set.
 */
public final class OriginalRtcNativeLoadSmoke {
    private OriginalRtcNativeLoadSmoke() {}

    public static void main(String[] args) {
        tel.schich.libdatachannel.LibDataChannel.initialize();
        if (tel.schich.libdatachannel.LibDataChannel.logLevel() == null) {
            throw new IllegalStateException("RTC native initialization did not complete");
        }
        System.out.println("PASS: Pinned original Linux WebRTC JNI initialized from the classified JAR");
    }
}
