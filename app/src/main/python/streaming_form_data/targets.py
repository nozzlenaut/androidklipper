"""Targets used by the Android multipart compatibility parser."""

import hashlib


class _BaseTarget:
    def __init__(self):
        self._parser = None

    def _bind_parser(self, parser):
        self._parser = parser

    def _ensure(self):
        if self._parser is not None:
            self._parser._ensure_parsed()


class ValueTarget(_BaseTarget):
    def __init__(self):
        super().__init__()
        self._value = b""

    @property
    def value(self):
        self._ensure()
        return self._value

    def _consume(self, field):
        value = field.value
        if isinstance(value, bytes):
            self._value = value
        else:
            self._value = str(value).encode("utf-8")


class FileTarget(_BaseTarget):
    def __init__(self, filename):
        super().__init__()
        self.filename = str(filename)
        self._multipart_filename = None

    @property
    def multipart_filename(self):
        self._ensure()
        return self._multipart_filename

    def _consume(self, field):
        self._multipart_filename = field.filename
        source = getattr(field, "file", None)
        if source is None:
            data = field.value
            if not isinstance(data, bytes):
                data = str(data).encode("utf-8")
            with open(self.filename, "wb") as out:
                out.write(data)
            return

        try:
            source.seek(0)
        except Exception:
            pass
        with open(self.filename, "wb") as out:
            while True:
                chunk = source.read(1024 * 1024)
                if not chunk:
                    break
                out.write(chunk)

    def on_finish(self):
        # Compatibility no-op used by Moonraker's error path.
        return None


class SHA256Target(_BaseTarget):
    def __init__(self):
        super().__init__()
        self._value = ""

    @property
    def value(self):
        self._ensure()
        return self._value

    def _consume(self, field):
        digest = hashlib.sha256()
        source = getattr(field, "file", None)
        if source is None:
            data = field.value
            if not isinstance(data, bytes):
                data = str(data).encode("utf-8")
            digest.update(data)
        else:
            try:
                source.seek(0)
            except Exception:
                pass
            while True:
                chunk = source.read(1024 * 1024)
                if not chunk:
                    break
                digest.update(chunk)
        self._value = digest.hexdigest()
