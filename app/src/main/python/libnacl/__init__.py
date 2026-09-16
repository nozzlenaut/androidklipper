"""Android compatibility shim for Moonraker's libnacl imports.

Moonraker only uses the Ed25519 signing helpers from libnacl. On Android,
PyNaCl has supported native wheels through Chaquopy for years, so expose the
small libnacl-compatible surface Moonraker expects on top of PyNaCl.
"""

from .sign import Signer, Verifier

__all__ = ["Signer", "Verifier"]
