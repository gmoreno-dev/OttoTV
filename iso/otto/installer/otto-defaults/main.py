#!/usr/bin/env python3
"""Calamares job: the answers to the pages Otto TV's installer skips.

Upstream asks whether to keep Android's data in a data.img file and which
of ~40 developer boot options to use. Otto TV answers both (a data folder,
which grows with the disk; no extra options, the boot line already comes
from /cmdline.txt) and drops those pages, but later jobs still read the
answers: gen-img and gen-fstab read "dataimg", bootcfg reads "options".
Runs before them, and keeps any answer a page did give.
"""

import libcalamares

DEFAULTS = {
    "dataimg": {"disabled": True, "dataSize": 0, "useMaximum": False},
    "options": "",
}


def pretty_name():
    return "Otto TV defaults"


def run():
    gs = libcalamares.globalstorage
    for key, value in DEFAULTS.items():
        if not gs.contains(key) or gs.value(key) is None:
            gs.insert(key, value)
            libcalamares.utils.debug("otto: {} = {!r}".format(key, value))
    libcalamares.job.setprogress(1.0)
    return None
