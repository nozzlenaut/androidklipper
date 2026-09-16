"""Small Android compatibility layer for Moonraker's upload parser.

Moonraker expects the third-party streaming-form-data API. On Android we avoid
its native extension by spooling the multipart request to a temp file and
parsing it with Python 3.11's standard-library cgi.FieldStorage once Tornado
has delivered the full body.
"""

import cgi
import os
import tempfile


class ParseFailedException(Exception):
    pass


class StreamingFormDataParser:
    def __init__(self, headers):
        self.headers = headers
        self._targets = {}
        fd, path = tempfile.mkstemp(prefix="androidklipper-upload-", suffix=".multipart")
        self._raw_path = path
        self._raw = os.fdopen(fd, "wb")
        self._parsed = False
        self._parsing = False

    def register(self, name, target):
        self._targets.setdefault(str(name), []).append(target)
        target._bind_parser(self)

    def data_received(self, chunk):
        if self._parsed:
            raise ParseFailedException("multipart parser already finalized")
        try:
            self._raw.write(bytes(chunk))
        except Exception as exc:
            raise ParseFailedException(str(exc)) from exc

    def _header(self, name, default=""):
        try:
            value = self.headers.get(name, default)
        except Exception:
            value = default
        return str(value or default)

    def _ensure_parsed(self):
        if self._parsed or self._parsing:
            return
        self._parsing = True
        try:
            try:
                self._raw.flush()
                self._raw.close()
            except Exception:
                pass

            size = os.path.getsize(self._raw_path)
            content_type = self._header("Content-Type")
            if "multipart/form-data" not in content_type.lower():
                raise ParseFailedException(
                    "Expected multipart/form-data, got %r" % content_type
                )

            environ = {
                "REQUEST_METHOD": "POST",
                "CONTENT_TYPE": content_type,
                "CONTENT_LENGTH": str(size),
            }
            with open(self._raw_path, "rb") as body:
                form = cgi.FieldStorage(
                    fp=body,
                    environ=environ,
                    keep_blank_values=True,
                )

                for name, targets in self._targets.items():
                    if name not in form:
                        continue
                    field = form[name]
                    if isinstance(field, list):
                        field = field[0]
                    for target in targets:
                        target._consume(field)

            self._parsed = True
        except ParseFailedException:
            raise
        except Exception as exc:
            raise ParseFailedException(str(exc)) from exc
        finally:
            self._parsing = False
            try:
                os.unlink(self._raw_path)
            except OSError:
                pass
