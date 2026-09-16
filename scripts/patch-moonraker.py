#!/usr/bin/env python3
from pathlib import Path
import sys

server_path = Path(sys.argv[1])
application_path = Path(sys.argv[2])
text = server_path.read_text()

components_old = """CORE_COMPONENTS = [
    'dbus_manager', 'database', 'file_manager', 'authorization',
    'klippy_apis', 'machine', 'data_store', 'shell_command',
    'proc_stats', 'job_state', 'job_queue', 'history',
    'http_client', 'announcements', 'webcam', 'extensions'
]
"""
components_new = """# AndroidKlipper runs Moonraker on localhost and uses machine provider 'none'.
# DBus has no useful role on Android, and authorization is intentionally omitted
# while the API is loopback-only.  The rest is upstream Moonraker.
CORE_COMPONENTS = [
    'database', 'file_manager',
    'klippy_apis', 'machine', 'data_store', 'shell_command',
    'proc_stats', 'job_state', 'job_queue', 'history',
    'http_client', 'announcements', 'webcam', 'extensions'
]

_ANDROID_CURRENT_SERVER = None
_ANDROID_LAST_ERROR = ""

def android_get_last_error() -> str:
    return _ANDROID_LAST_ERROR

def android_request_stop() -> bool:
    # Request a clean terminate from an Android/Kotlin service thread.
    server = _ANDROID_CURRENT_SERVER
    if server is None:
        return False
    try:
        server.event_loop.register_callback(server._stop_server, "terminate")
        return True
    except Exception:
        logging.exception("Android Moonraker stop request failed")
        return False
"""
if components_old not in text:
    raise SystemExit("Moonraker core component patch point changed")
text = text.replace(components_old, components_new, 1)

signal_old = """    async def server_init(self, start_server: bool = True) -> None:
        self.event_loop.add_signal_handler(
            signal.SIGTERM, self._handle_term_signal)
"""
signal_new = """    async def server_init(self, start_server: bool = True) -> None:
        # AndroidKlipper runs Moonraker from an embedded Python worker thread.
        # POSIX signal handlers may be unavailable outside Python's main thread.
        try:
            self.event_loop.add_signal_handler(
                signal.SIGTERM, self._handle_term_signal)
        except (NotImplementedError, RuntimeError, ValueError):
            logging.info("Signal handlers unavailable in embedded Android runtime")
"""
if signal_old not in text:
    raise SystemExit("Moonraker signal add patch point changed")
text = text.replace(signal_old, signal_new, 1)

remove_old = """        self.exit_reason = exit_reason
        self.event_loop.remove_signal_handler(signal.SIGTERM)
        self.app_running_evt.set()
"""
remove_new = """        self.exit_reason = exit_reason
        try:
            self.event_loop.remove_signal_handler(signal.SIGTERM)
        except (NotImplementedError, RuntimeError, ValueError):
            pass
        self.app_running_evt.set()
"""
if remove_old not in text:
    raise SystemExit("Moonraker signal remove patch point changed")
text = text.replace(remove_old, remove_new, 1)

launch_old = """    try:
        server = Server(app_args, log_manager, eventloop)
        server.load_components()
"""
launch_new = """    global _ANDROID_CURRENT_SERVER, _ANDROID_LAST_ERROR
    _ANDROID_LAST_ERROR = ""
    try:
        server = Server(app_args, log_manager, eventloop)
        _ANDROID_CURRENT_SERVER = server
        server.load_components()
"""
if launch_old not in text:
    raise SystemExit("Moonraker current server patch point changed")
text = text.replace(launch_old, launch_new, 1)

config_error_old = """    except confighelper.ConfigError as e:
        logging.exception("Server Config Error")
"""
config_error_new = """    except confighelper.ConfigError as e:
        _ANDROID_LAST_ERROR = traceback.format_exc()
        _ANDROID_CURRENT_SERVER = None
        logging.exception("Server Config Error")
"""
if config_error_old not in text:
    raise SystemExit("Moonraker config error patch point changed")
text = text.replace(config_error_old, config_error_new, 1)

moon_error_old = """    except Exception:
        logging.exception("Moonraker Error")
        return 1
"""
moon_error_new = """    except Exception:
        _ANDROID_LAST_ERROR = traceback.format_exc()
        _ANDROID_CURRENT_SERVER = None
        logging.exception("Moonraker Error")
        return 1
"""
if moon_error_old not in text:
    raise SystemExit("Moonraker load error patch point changed")
text = text.replace(moon_error_old, moon_error_new, 1)

running_error_old = """    except Exception:
        logging.exception("Server Running Error")
        return 1
"""
running_error_new = """    except Exception:
        _ANDROID_LAST_ERROR = traceback.format_exc()
        _ANDROID_CURRENT_SERVER = None
        logging.exception("Server Running Error")
        return 1
"""
if running_error_old not in text:
    raise SystemExit("Moonraker running error patch point changed")
text = text.replace(running_error_old, running_error_new, 1)

text = text.replace(
    """    del server
    return None
""",
    """    _ANDROID_CURRENT_SERVER = None
    del server
    return None
""",
    1,
)

server_path.write_text(text)


application_text = application_path.read_text()
upload_import_old = """from streaming_form_data import StreamingFormDataParser, ParseFailedException
from streaming_form_data.targets import FileTarget, ValueTarget, SHA256Target
"""
upload_import_new = """try:
    from streaming_form_data import StreamingFormDataParser, ParseFailedException
    from streaming_form_data.targets import FileTarget, ValueTarget, SHA256Target
    STREAMING_FORM_DATA_AVAILABLE = True
except ImportError:
    STREAMING_FORM_DATA_AVAILABLE = False
"""
if upload_import_old not in application_text:
    raise SystemExit("Moonraker upload dependency patch point changed")
application_text = application_text.replace(
    upload_import_old, upload_import_new, 1
)

upload_register_old = """        self.register_upload_handler("/server/files/upload")
"""
upload_register_new = """        if STREAMING_FORM_DATA_AVAILABLE:
            self.register_upload_handler("/server/files/upload")
        else:
            self.server.add_warning(
                "File uploads disabled: streaming-form-data is unavailable "
                "in the embedded Android runtime"
            )
"""
if upload_register_old not in application_text:
    raise SystemExit("Moonraker upload registration patch point changed")
application_text = application_text.replace(
    upload_register_old, upload_register_new, 1
)
application_path.write_text(application_text)
