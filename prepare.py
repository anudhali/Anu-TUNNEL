#!/usr/bin/env python3
"""Apply the small Android integration patch to the pinned upstream BadVPN source."""
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()

def replace(path: str, old: str, new: str, label: str):
    p = root / path
    s = p.read_text()
    if new in s:
        return
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor in {path}, found {count}")
    p.write_text(s.replace(old, new, 1))

# Android uses Bionic; pthread is in libc and librt is not a separately linked library.
replace("CMakeLists.txt", "    link_libraries(rt pthread)\n",
        "    if (NOT ANDROID)\n        link_libraries(rt pthread)\n    endif ()\n",
        "Android system libraries")
replace("CMakeLists.txt", 'if (CMAKE_SYSTEM_NAME STREQUAL "Linux" AND NOT EMSCRIPTEN)\n',
        'if ((CMAKE_SYSTEM_NAME STREQUAL "Linux" OR ANDROID) AND NOT EMSCRIPTEN)\n',
        "Android Linux feature detection")
replace("CMakeLists.txt", 'if (CMAKE_SYSTEM_NAME STREQUAL "Linux" OR EMSCRIPTEN)\n',
        'if (CMAKE_SYSTEM_NAME STREQUAL "Linux" OR ANDROID OR EMSCRIPTEN)\n',
        "Android Linux feature detection")
replace("CMakeLists.txt", 'elseif (CMAKE_SYSTEM_NAME STREQUAL "Linux")\n',
        'elseif (CMAKE_SYSTEM_NAME STREQUAL "Linux" OR ANDROID)\n',
        "Android Linux backend")

p = root / "tun2socks/tun2socks.c"
s = p.read_text()
if "anutunnel_stop_requested" not in s:
    # JNI worker uses these flags to deliver a reactor-safe SIGINT shutdown.
    s = s.replace('#include <stdlib.h>\n', '#include <stdlib.h>\n#include <signal.h>\n#include <limits.h>\n', 1)
    marker = "// command-line options\nstruct {\n"
    if s.count(marker) != 1:
        raise SystemExit("BadVPN options block anchor not found")
    s = s.replace(marker,
        "/* Anu TUNNEL JNI lifecycle flags. Signals are delivered on the reactor thread. */\n"
        "volatile sig_atomic_t anutunnel_stop_requested = 0;\n"
        "volatile sig_atomic_t anutunnel_signal_ready = 0;\n"
        "volatile sig_atomic_t anutunnel_engine_running = 0;\n"
        "volatile sig_atomic_t anutunnel_udpgw_state = 0;\n\n" + marker, 1)
    s = s.replace("    char *tundev;\n", "    char *tundev;\n    int tun_fd;\n    int tun_mtu;\n", 1)
    s = s.replace('        "        [--tundev <name>]\\n"\n',
        '        "        [--tundev <name>]\\n"\n'
        '        "        [--tunfd <android-fd> --tunmtu <mtu>]\\n"\n', 1)
    s = s.replace("    options.tundev = NULL;\n", "    options.tundev = NULL;\n    options.tun_fd = -1;\n    options.tun_mtu = 1400;\n", 1)
    old = '''        else if (!strcmp(arg, "--tundev")) {
            if (1 >= argc - i) {
                fprintf(stderr, "%s: requires an argument\\n", arg);
                return 0;
            }
            options.tundev = argv[i + 1];
            i++;
        }
'''
    new = old + '''        else if (!strcmp(arg, "--tunfd")) {
            if (1 >= argc - i) { fprintf(stderr, "%s: requires an argument\\n", arg); return 0; }
            char *end = NULL; long value = strtol(argv[i + 1], &end, 10);
            if (!end || *end || value < 0 || value > INT_MAX) { fprintf(stderr, "invalid TUN fd\\n"); return 0; }
            options.tun_fd = (int)value; i++;
        }
        else if (!strcmp(arg, "--tunmtu")) {
            if (1 >= argc - i) { fprintf(stderr, "%s: requires an argument\\n", arg); return 0; }
            char *end = NULL; long value = strtol(argv[i + 1], &end, 10);
            if (!end || *end || value < 576 || value > 65535) { fprintf(stderr, "invalid TUN MTU\\n"); return 0; }
            options.tun_mtu = (int)value; i++;
        }
'''
    if s.count(old) != 1:
        raise SystemExit("BadVPN --tundev parser anchor not found exactly once")
    s = s.replace(old, new, 1)
    # Select Android's already-open VPN fd rather than opening /dev/net/tun.
    old = '''    // init TUN device
    if (!BTap_Init(&device, &ss, options.tundev, device_error_handler, NULL, 1)) {
        BLog(BLOG_ERROR, "BTap_Init failed");
        goto fail3;
    }
'''
    new = '''    // init TUN device (Android supplies the fd created by VpnService.Builder.establish()).
    struct BTap_init_data tap_init;
    tap_init.dev_type = BTAP_DEV_TUN;
    if (options.tun_fd >= 0) {
        tap_init.init_type = BTAP_INIT_FD;
        tap_init.init.fd.fd = options.tun_fd;
        tap_init.init.fd.mtu = options.tun_mtu;
    } else {
        tap_init.init_type = BTAP_INIT_STRING;
        tap_init.init.string = options.tundev;
    }
    if (!BTap_Init2(&device, &ss, tap_init, device_error_handler, NULL)) {
        BLog(BLOG_ERROR, "BTap_Init2 failed");
        goto fail3;
    }
'''
    if s.count(old) != 1:
        raise SystemExit("BadVPN TUN initialization anchor not found exactly once")
    s = s.replace(old, new, 1)
    # Make the original entry point callable from JNI.
    if s.count("int main (int argc, char **argv)") != 1:
        raise SystemExit("BadVPN main entry point anchor not found exactly once")
    s = s.replace("int main (int argc, char **argv)", "int badvpn_tun2socks_main (int argc, char **argv)", 1)
    # Publish the event-loop state, and handle stop requests racing native startup.
    old = '''    if (!BSignal_Init(&ss, signal_handler, NULL)) {
        BLog(BLOG_ERROR, "BSignal_Init failed");
        goto fail2;
    }
    
    // init TUN device'''
    new = '''    if (!BSignal_Init(&ss, signal_handler, NULL)) {
        BLog(BLOG_ERROR, "BSignal_Init failed");
        goto fail2;
    }
    anutunnel_signal_ready = 1;
    if (anutunnel_stop_requested) {
        goto fail3;
    }
    
    // init TUN device'''
    if s.count(old) != 1:
        raise SystemExit("BadVPN signal startup anchor not found exactly once")
    s = s.replace(old, new, 1)
    old = '''    BLog(BLOG_NOTICE, "entering event loop");
    BReactor_Exec(&ss);
'''
    new = '''    BLog(BLOG_NOTICE, "entering event loop");
    anutunnel_engine_running = 1;
    BReactor_Exec(&ss);
    anutunnel_engine_running = 0;
    anutunnel_signal_ready = 0;
'''
    if s.count(old) != 1:
        raise SystemExit("BadVPN event loop anchor not found exactly once")
    s = s.replace(old, new, 1)
    s = s.replace('fail3:\n    BSignal_Finish();',
                  'fail3:\n    anutunnel_engine_running = 0;\n    anutunnel_signal_ready = 0;\n    BSignal_Finish();', 1)
    # A requested SIGINT is a clean stop, not a fatal native startup failure.
    old = '''    return 1;
}

void terminate (void)'''
    new = '''    return quitting ? 0 : 1;
}

void terminate (void)'''
    if s.count(old) != 1:
        raise SystemExit("BadVPN return-status anchor not found exactly once")
    s = s.replace(old, new, 1)

    # Instrument provider UDPGW transport and protocol responses for Android status.
    socks = root / "tun2socks/SocksUdpGwClient.c"
    ss = socks.read_text()
    if "anutunnel_udpgw_state = 1" not in ss:
        ss = ss.replace('#include <misc/debug.h>', '#include <signal.h>\nextern volatile sig_atomic_t anutunnel_udpgw_state;\n#include <misc/debug.h>', 1)
        ss = ss.replace('            o->socks_up = 1;\n', '            o->socks_up = 1;\n            anutunnel_udpgw_state = 1;\n', 1)
        ss = ss.replace('            BLog(BLOG_INFO, "SOCKS error");', '            anutunnel_udpgw_state = -1;\n            BLog(BLOG_INFO, "SOCKS error");', 1)
        ss = ss.replace('    BLog(BLOG_ERROR, "client reports server error");', '    anutunnel_udpgw_state = -1;\n    BLog(BLOG_ERROR, "client reports server error");', 1)
        socks.write_text(ss)

    udp = root / "udpgw_client/UdpGwClient.c"
    us = udp.read_text()
    if "anutunnel_udpgw_state = 2" not in us:
        us = us.replace('#include <stdlib.h>', '#include <signal.h>\nextern volatile sig_atomic_t anutunnel_udpgw_state;\n#include <stdlib.h>', 1)
        us = us.replace('    // pass packet to user\n    o->handler_received(o->user, con->conaddr.local_addr, con->conaddr.remote_addr, data, data_len);',
                        '    // Valid decoded packet with matching connection ID and peer proves UDPGW response.\n    anutunnel_udpgw_state = 2;\n    // pass packet to user\n    o->handler_received(o->user, con->conaddr.local_addr, con->conaddr.remote_addr, data, data_len);', 1)
        us = us.replace('    BLog(BLOG_ERROR, "decoder error");', '    anutunnel_udpgw_state = -1;\n    BLog(BLOG_ERROR, "decoder error");', 1)
        udp.write_text(us)

    p.write_text(s)

# Build an Android shared library instead of a standalone executable.
replace("tun2socks/CMakeLists.txt",
'''add_executable(badvpn-tun2socks
    tun2socks.c
    SocksUdpGwClient.c
)
target_link_libraries(badvpn-tun2socks system flow tuntap lwip socksclient udpgw_client socks_udp_client)

install(
    TARGETS badvpn-tun2socks
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
)
''',
'''add_library(anutunnel-badvpn-jni SHARED
    tun2socks.c
    SocksUdpGwClient.c
    anutunnel_jni.c
)
target_link_libraries(anutunnel-badvpn-jni system flow tuntap lwip socksclient udpgw_client socks_udp_client)
set_target_properties(anutunnel-badvpn-jni PROPERTIES
    OUTPUT_NAME "anutunnel-badvpn"
    POSITION_INDEPENDENT_CODE ON
)
if (ANDROID)
    target_link_options(anutunnel-badvpn-jni PRIVATE "-Wl,-z,max-page-size=16384")
endif ()
''', "Android JNI target")

jni = root / "tun2socks/anutunnel_jni.c"
if not jni.exists():
    jni.write_text(r'''#include <jni.h>
#include <pthread.h>
#include <signal.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>

extern int badvpn_tun2socks_main(int argc, char **argv);
extern volatile sig_atomic_t anutunnel_stop_requested;
extern volatile sig_atomic_t anutunnel_signal_ready;
extern volatile sig_atomic_t anutunnel_engine_running;
extern volatile sig_atomic_t anutunnel_udpgw_state;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_t g_worker;
static int g_worker_valid = 0;
static int g_run_active = 0;

JNIEXPORT jboolean JNICALL
Java_com_anutunnel_app_BadVpnTunnel_reset(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    pthread_mutex_lock(&g_lock);
    if (g_run_active) {
        pthread_mutex_unlock(&g_lock);
        return JNI_FALSE;
    }
    anutunnel_stop_requested = 0;
    anutunnel_signal_ready = 0;
    anutunnel_engine_running = 0;
    anutunnel_udpgw_state = 0;
    pthread_mutex_unlock(&g_lock);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_anutunnel_app_BadVpnTunnel_run(JNIEnv *env, jclass clazz,
        jint tun_fd, jint mtu, jstring socks_server, jstring udp_gateway,
        jstring ipv4, jstring netmask, jstring ipv6) {
    (void)clazz;
    if (tun_fd < 0 || mtu < 576) return -10;

    pthread_mutex_lock(&g_lock);
    if (g_run_active) {
        pthread_mutex_unlock(&g_lock);
        return -11;
    }
    g_run_active = 1;
    g_worker = pthread_self();
    g_worker_valid = 1;
    pthread_mutex_unlock(&g_lock);

    int flags = fcntl(tun_fd, F_GETFL, 0);
    if (flags < 0 || fcntl(tun_fd, F_SETFL, flags | O_NONBLOCK) < 0) {
        pthread_mutex_lock(&g_lock);
        g_run_active = 0; g_worker_valid = 0;
        pthread_mutex_unlock(&g_lock);
        return -12;
    }

    const char *socks = (*env)->GetStringUTFChars(env, socks_server, NULL);
    const char *udp = udp_gateway ? (*env)->GetStringUTFChars(env, udp_gateway, NULL) : NULL;
    const char *ip = (*env)->GetStringUTFChars(env, ipv4, NULL);
    const char *mask = (*env)->GetStringUTFChars(env, netmask, NULL);
    const char *ip6 = ipv6 ? (*env)->GetStringUTFChars(env, ipv6, NULL) : NULL;
    if (!socks || !ip || !mask || (udp_gateway && !udp) || (ipv6 && !ip6)) {
        if (socks) (*env)->ReleaseStringUTFChars(env, socks_server, socks);
        if (udp) (*env)->ReleaseStringUTFChars(env, udp_gateway, udp);
        if (ip) (*env)->ReleaseStringUTFChars(env, ipv4, ip);
        if (mask) (*env)->ReleaseStringUTFChars(env, netmask, mask);
        if (ip6) (*env)->ReleaseStringUTFChars(env, ipv6, ip6);
        pthread_mutex_lock(&g_lock);
        g_run_active = 0; g_worker_valid = 0;
        pthread_mutex_unlock(&g_lock);
        return -13;
    }

    char fd_text[24], mtu_text[24];
    snprintf(fd_text, sizeof(fd_text), "%d", (int)tun_fd);
    snprintf(mtu_text, sizeof(mtu_text), "%d", (int)mtu);
    char *argv[24];
    int argc = 0;
    argv[argc++] = (char *)"anu-tun2socks";
    argv[argc++] = (char *)"--tunfd"; argv[argc++] = fd_text;
    argv[argc++] = (char *)"--tunmtu"; argv[argc++] = mtu_text;
    argv[argc++] = (char *)"--netif-ipaddr"; argv[argc++] = (char *)ip;
    argv[argc++] = (char *)"--netif-netmask"; argv[argc++] = (char *)mask;
    if (ip6 && ip6[0]) { argv[argc++] = (char *)"--netif-ip6addr"; argv[argc++] = (char *)ip6; }
    argv[argc++] = (char *)"--socks-server-addr"; argv[argc++] = (char *)socks;
    if (udp && udp[0]) {
        argv[argc++] = (char *)"--udpgw-remote-server-addr"; argv[argc++] = (char *)udp;
        argv[argc++] = (char *)"--udpgw-transparent-dns";
        argv[argc++] = (char *)"--udpgw-max-connections"; argv[argc++] = (char *)"256";
    }
    argv[argc++] = (char *)"--loglevel"; argv[argc++] = (char *)"info";
    argv[argc] = NULL;

    int result = badvpn_tun2socks_main(argc, argv);

    if (ip6) (*env)->ReleaseStringUTFChars(env, ipv6, ip6);
    (*env)->ReleaseStringUTFChars(env, netmask, mask);
    (*env)->ReleaseStringUTFChars(env, ipv4, ip);
    if (udp) (*env)->ReleaseStringUTFChars(env, udp_gateway, udp);
    (*env)->ReleaseStringUTFChars(env, socks_server, socks);

    pthread_mutex_lock(&g_lock);
    anutunnel_engine_running = 0;
    anutunnel_signal_ready = 0;
    g_worker_valid = 0;
    g_run_active = 0;
    pthread_mutex_unlock(&g_lock);
    return result;
}

JNIEXPORT void JNICALL
Java_com_anutunnel_app_BadVpnTunnel_stop(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    pthread_t worker;
    int send_signal = 0;
    pthread_mutex_lock(&g_lock);
    anutunnel_stop_requested = 1;
    if (g_worker_valid && anutunnel_signal_ready) {
        worker = g_worker;
        send_signal = 1;
    }
    pthread_mutex_unlock(&g_lock);
    if (send_signal) {
        int rc = pthread_kill(worker, SIGINT);
        if (rc != 0) __android_log_print(ANDROID_LOG_WARN, "AnuBadVPN", "pthread_kill(SIGINT) failed: %d", rc);
    }
}

JNIEXPORT jint JNICALL
Java_com_anutunnel_app_BadVpnTunnel_udpGatewayState(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    extern volatile sig_atomic_t anutunnel_udpgw_state;
    return (jint)anutunnel_udpgw_state;
}

JNIEXPORT jboolean JNICALL
Java_com_anutunnel_app_BadVpnTunnel_isRunning(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    pthread_mutex_lock(&g_lock);
    int running = g_run_active && anutunnel_engine_running;
    pthread_mutex_unlock(&g_lock);
    return running ? JNI_TRUE : JNI_FALSE;
}
''')
    # Android log declarations used only for best-effort shutdown diagnostics.
    txt = jni.read_text()
    txt = txt.replace('        if (rc != 0) __android_log_print(ANDROID_LOG_WARN, "AnuBadVPN", "pthread_kill(SIGINT) failed: %d", rc);', '        if (rc != 0) fprintf(stderr, "AnuBadVPN: pthread_kill(SIGINT) failed: %d\\n", rc);')
    jni.write_text(txt)

print(f"PASS: prepared Android BadVPN source at {root}")
