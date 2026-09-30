#!/usr/bin/env python3
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
needle = "def check_build_c_library():\n    srcdir = os.path.dirname(os.path.realpath(__file__))\n"
replacement = """def check_build_c_library():
    # AndroidKlipper packages this library with the APK. Android apps should not
    # ship a compiler or build native code at runtime.
    prebuilt = os.environ.get('ANDROID_KLIPPER_CHELPER')
    if prebuilt and os.path.exists(prebuilt):
        return prebuilt
    srcdir = os.path.dirname(os.path.realpath(__file__))
"""
if needle not in text:
    raise SystemExit("Klipper chelper patch point changed; inspect upstream before updating the pin")
path.write_text(text.replace(needle, replacement, 1))

if len(sys.argv) >= 3:
    serial_path = Path(sys.argv[2])
    serial_text = serial_path.read_text()
    serial_needle = """    def connect_uart(self, serialport, baud, rts=True):
        # Initial connection
"""
    serial_replacement = """    def connect_uart(self, serialport, baud, rts=True):
        # AndroidKlipper's Kotlin layer owns/configures the physical USB UART.
        # Its /dev/pts endpoints are byte pipes; treating them as hardware UARTs
        # triggers unsupported exclusive-lock and modem-control ioctls on Android.
        if serialport.startswith('/dev/pts/'):
            return self.connect_pipe(serialport)
        # Initial connection
"""
    if serial_needle not in serial_text:
        raise SystemExit("Klipper serialhdl patch point changed; inspect upstream before updating the pin")
    serial_path.write_text(serial_text.replace(serial_needle, serial_replacement, 1))


if len(sys.argv) >= 4:
    mcu_path = Path(sys.argv[3])
    mcu_text = mcu_path.read_text()
    mcu_needle = """            if not (self._serialport.startswith("/dev/rpmsg_")
                    or self._serialport.startswith("/tmp/klipper_host_")):
"""
    mcu_replacement = """            if not (self._serialport.startswith("/dev/rpmsg_")
                    or self._serialport.startswith("/tmp/klipper_host_")
                    or self._serialport.startswith("/dev/pts/")):
"""
    if mcu_needle not in mcu_text:
        raise SystemExit("Klipper mcu PTY patch point changed; inspect upstream before updating the pin")
    mcu_text = mcu_text.replace(mcu_needle, mcu_replacement, 1)

    # Android/Chaquopy can occasionally exceed Klipper's default 25ms
    # multi-MCU trsync window even when USB has no retransmits.
    trsync_needle = "TRSYNC_TIMEOUT = 0.025"
    trsync_replacement = "TRSYNC_TIMEOUT = 0.050"
    if trsync_needle not in mcu_text:
        raise SystemExit(
            "Klipper trsync timeout patch point changed; inspect upstream before updating the pin")
    mcu_text = mcu_text.replace(trsync_needle, trsync_replacement, 1)

    # Android can occasionally be descheduled far longer than a Pi. Give
    # async MCU commands (LED/output pin/PWM helpers) more runway as well as
    # the normal motion queue so a brief host pause does not become a late
    # command at the MCU. Keep this comfortably below Klipper's 3s nominal
    # scheduling horizon.
    schedule_needle = "MIN_SCHEDULE_TIME = 0.100"
    schedule_replacement = "MIN_SCHEDULE_TIME = 0.250"
    if schedule_needle not in mcu_text:
        raise SystemExit(
            "Klipper min schedule patch point changed; inspect upstream before updating the pin")
    mcu_text = mcu_text.replace(schedule_needle, schedule_replacement, 1)
    mcu_path.write_text(mcu_text)


if len(sys.argv) >= 5:
    klippy_path = Path(sys.argv[4])
    klippy_text = klippy_path.read_text()
    klippy_needle = """        try:
            self.reactor.run()
        except:
            msg = "Unhandled exception during run"
            logging.exception(msg)
"""
    klippy_replacement = """        try:
            self.reactor.run()
        except BaseException as e:
            msg = "Unhandled exception during run: %s: %s" % (
                type(e).__name__, e)
            logging.exception(msg)
"""
    if klippy_needle not in klippy_text:
        raise SystemExit(
            "Klippy run exception patch point changed; inspect upstream before updating the pin")
    klippy_path.write_text(
        klippy_text.replace(klippy_needle, klippy_replacement, 1))


if len(sys.argv) >= 6:
    stats_needle = """        self.last_load_avg = os.getloadavg()[0]
"""
    stats_replacement = """        # Python on Android does not expose os.getloadavg().  System load is
        # diagnostic-only, so use /proc/loadavg when available and otherwise
        # report zero instead of crashing Klippy's once-per-second stats timer.
        if hasattr(os, 'getloadavg'):
            self.last_load_avg = os.getloadavg()[0]
        else:
            try:
                with open('/proc/loadavg', 'r') as load_file:
                    self.last_load_avg = float(load_file.read().split()[0])
            except Exception:
                self.last_load_avg = 0.
"""
    for stats_arg in sys.argv[5:7]:
        stats_path = Path(stats_arg)
        stats_text = stats_path.read_text()
        if stats_needle not in stats_text:
            raise SystemExit(
                "Klippy statistics patch point changed in %s; inspect upstream before updating the pin"
                % stats_path)
        stats_path.write_text(
            stats_text.replace(stats_needle, stats_replacement, 1))


if len(sys.argv) >= 8:
    gcode_path = Path(sys.argv[7])
    gcode_text = gcode_path.read_text()
    gcode_needle = """def add_early_printer_objects(printer):
    printer.add_object('gcode', GCodeDispatch(printer))
    printer.add_object('gcode_io', GCodeIO(printer))
"""
    gcode_replacement = """def add_early_printer_objects(printer):
    printer.add_object('gcode', GCodeDispatch(printer))
    # Moonraker communicates through Klipper's webhooks API socket. Android
    # does not need Klipper's legacy pseudo-TTY G-code transport, so allow
    # hosts to omit gcode_fd entirely instead of manufacturing a dummy TTY.
    if printer.get_start_args().get('gcode_fd') is not None:
        printer.add_object('gcode_io', GCodeIO(printer))
"""
    if gcode_needle not in gcode_text:
        raise SystemExit(
            "Klippy gcode_io patch point changed; inspect upstream before updating the pin")
    gcode_path.write_text(
        gcode_text.replace(gcode_needle, gcode_replacement, 1))


if len(sys.argv) >= 9:
    webhooks_path = Path(sys.argv[8])
    webhooks_text = webhooks_path.read_text()
    hostname_needle = "'hostname': socket.gethostname(),"
    hostname_replacement = (
        "'hostname': self.printer.get_start_args().get("
        "'hostname', socket.gethostname()),"
    )
    if hostname_needle not in webhooks_text:
        raise SystemExit(
            "Klippy webhooks hostname patch point changed; inspect upstream before updating the pin")
    webhooks_path.write_text(
        webhooks_text.replace(hostname_needle, hostname_replacement, 1))


if len(sys.argv) >= 10:
    toolhead_path = Path(sys.argv[9])
    toolhead_text = toolhead_path.read_text()
    queue_needle = """BUFFER_TIME_HIGH = 1.0
BUFFER_TIME_START = 0.250
PRIMING_CMD_TIME = 0.100
"""
    queue_replacement = """# Android hosts can see scheduler pauses that are unusual on a dedicated Pi.
# Keep substantially more already-timestamped motion queued on the MCUs so a
# sub-second Android scheduling hiccup is absorbed instead of becoming a
# 'Timer too close' shutdown. Klipper's nominal future-scheduling limit is 3s.
BUFFER_TIME_HIGH = 2.0
BUFFER_TIME_START = 0.750
PRIMING_CMD_TIME = 0.100
"""
    if queue_needle not in toolhead_text:
        raise SystemExit(
            "Klippy toolhead queue patch point changed; inspect upstream before updating the pin")
    toolhead_path.write_text(
        toolhead_text.replace(queue_needle, queue_replacement, 1))


if len(sys.argv) >= 11:
    serialqueue_path = Path(sys.argv[10])
    serialqueue_text = serialqueue_path.read_text()
    # Upstream Linux Klipper delays motion packets until only 100ms before their
    # requested MCU clock. AndroidKlipper has an extra PTY -> JVM -> UsbManager
    # hop, and the G2 logs show the fatal NHK packet arriving ~97ms before a
    # "Timer too close" shutdown. Release scheduled packets substantially earlier.
    reqtime_needle = "#define MIN_REQTIME_DELTA 0.100"
    reqtime_replacement = "#define MIN_REQTIME_DELTA 0.500"
    if reqtime_needle not in serialqueue_text:
        raise SystemExit(
            "Klipper serialqueue send-ahead patch point changed; inspect upstream before updating the pin")
    serialqueue_path.write_text(
        serialqueue_text.replace(reqtime_needle, reqtime_replacement, 1))
