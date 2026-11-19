#!/usr/bin/env python3
"""Calamares job: copy the TV Box payload to the installed system.

The upstream installer copies only the system image, kernel, initrd and
recovery to the target partition. Our boot hooks live next to them on the
boot media (/scripts and /tvbox), and the installed system's initrd sources
/scripts/* from the same folder as its kernel, which is the partition root.
So copying both folders there makes the installed system boot exactly like
the live one.

It also creates boot/tvbox: upstream bind-mounts the install folder's boot/
on Android's /boot, and our initrd hook mounts /tvbox on that empty folder
so the Android side can reach it (see tvbox_expose_runtime).
"""

import os
import shutil

import libcalamares

PAYLOAD = ("scripts", "tvbox")
SOURCE_ROOT = "/cdrom"


def pretty_name():
    return "Copying TV Box files"


def run():
    root = libcalamares.globalstorage.value("rootMountPoint")
    if not root or not os.path.isdir(root):
        return ("TV Box", 'Bad root mount point "{}"'.format(root))

    for name in PAYLOAD:
        source = os.path.join(SOURCE_ROOT, name)
        target = os.path.join(root, name)
        if not os.path.isdir(source):
            return ("TV Box", "Missing {} on the install media".format(source))
        if os.path.exists(target):
            shutil.rmtree(target)
        shutil.copytree(source, target)
        libcalamares.utils.debug("tvbox: copied {} to {}".format(source, target))

    os.makedirs(os.path.join(root, "boot", "tvbox"), exist_ok=True)
    libcalamares.job.setprogress(1.0)
    return None
