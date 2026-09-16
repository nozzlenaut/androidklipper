"""Subset of libnacl.sign implemented with PyNaCl for Android."""

from nacl.signing import SigningKey, VerifyKey


def _key_bytes(value):
    if isinstance(value, str):
        raw = value.encode("ascii")
    else:
        raw = bytes(value)
    if len(raw) == 64:
        try:
            return bytes.fromhex(raw.decode("ascii"))
        except (UnicodeDecodeError, ValueError):
            pass
    return raw


class Signer:
    def __init__(self, seed=None):
        if seed is None:
            self._key = SigningKey.generate()
        else:
            seed_bytes = _key_bytes(seed)
            if len(seed_bytes) != 32:
                raise ValueError("Ed25519 seed must be 32 bytes")
            self._key = SigningKey(seed_bytes)
        self.vk = self._key.verify_key.encode()

    def hex_seed(self):
        return self._key.encode().hex().encode("ascii")

    def hex_vk(self):
        return self.vk.hex().encode("ascii")

    def signature(self, message):
        return self._key.sign(bytes(message)).signature


class Verifier:
    def __init__(self, key):
        key_bytes = _key_bytes(key)
        if len(key_bytes) != 32:
            raise ValueError("Ed25519 verification key must be 32 bytes")
        self._key = VerifyKey(key_bytes)

    def verify(self, signed_message):
        # libnacl accepts a combined signature + message buffer here.
        return self._key.verify(bytes(signed_message))
