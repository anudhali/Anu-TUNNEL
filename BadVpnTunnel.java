package com.anutunnel.app;

/** JNI facade for the BadVPN tun2socks/UDPGW packet engine. */
public final class BadVpnTunnel {
    static { System.loadLibrary("anutunnel-badvpn"); }

    private BadVpnTunnel() { }

    /** Reset the native stop flag before starting a fresh worker. */
    public static native boolean reset();

    /** Run the TUN packet engine. This call blocks until stop or fatal error. */
    public static native int run(int tunFd, int mtu, String socksServer,
                                 String udpGateway, String ipv4, String netmask, String ipv6);

    /** Request an orderly stop through BadVPN's reactor signal handler. */
    public static native void stop();

    /** True only after the native engine enters its reactor event loop. */
    public static native boolean isRunning();
    /** 0=connecting, 1=UDPGW SOCKS stream up, 2=valid UDP response, -1=relay error. */
    public static native int udpGatewayState();
}
