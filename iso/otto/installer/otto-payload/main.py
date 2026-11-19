#!/usr/bin/env python3
"""Calamares job: copy the Otto TV payload to the installed system.

The upstream installer copies only the system image, kernel, initrd and
recovery to the target partition. Our boot hooks live next to them on the
boot media (/scripts and /otto), and the installed system's initrd sources
/scripts/* from the same folder as its kernel, which is the partition root.
So copying both folders there makes the installed system boot exactly like
the live one.

It also creates boot/otto: upstream bind-mounts the install folder's boot/
on Android's /boot, and our initrd hook mounts /otto on that empty folder
so the Android side can reach it (see otto_expose_runtime).
"""

import os
import shutil

import libcalamares

PAYLOAD = ("scripts", "otto")
SOURCE_ROOT = "/cdrom"


def pretty_name():
    return "Copying Otto TV files"


def run():
    root = libcalamares.globalstorage.value("rootMountPoint")
    if not root or not os.path.isdir(root):
        return ("Otto TV", 'Bad root mount point "{}"'.format(root))

    for name in PAYLOAD:
        source = os.path.join(SOURCE_ROOT, name)
        target = os.path.join(root, name)
        if not os.path.isdir(source):
            return ("Otto TV", "Missing {} on the install media".format(source))
        if os.path.exists(target):
            shutil.rmtree(target)
        shutil.copytree(source, target)
        libcalamares.utils.debug("otto: copied {} to {}".format(source, target))

    os.makedirs(os.path.join(root, "boot", "otto"), exist_ok=True)
    libcalamares.job.setprogress(1.0)
    return None
