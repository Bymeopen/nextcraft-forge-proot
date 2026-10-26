"""REA (Reverse Engine Anything): alat belajar dan riset reverse engineering yang sah."""

__version__ = "0.9.0"
SCHEMA = 1

from .plugins.api import plugin  # noqa: E402,F401  (`@rea.plugin(...)`)
