#!/usr/bin/env python3
"""Quiets upstream's initrd /init for Otto TV, in place.

The script that boots the system before Android (BlissOS's, shared by
LineageOS TV x86) draws the LineageOS logo in ASCII and prints scanning
progress, BlissOS warnings and the whole kernel command line. That text
stays on screen until the boot animation starts. Here it becomes Otto in
ASCII (/otto-ascii.txt, centered on the console), the routine messages go
away, and the few that matter (a CPU Android can't run on, no system found,
the emergency shell) are written in Portuguese.

Every edit checks that upstream's text is there: if upstream changes, the
build stops instead of shipping a half-patched boot.

Usage: patch_initrd_init.py <init>
"""

import re
import sys

TEXT_ART = '''# Otto TV: Otto in text mode, centered on the console, until the boot
# animation takes over the screen. On a first boot, which takes minutes, two
# lines under him say so. (Replaced by Otto TV's build.)
text_art() {
	local size rows cols pad top spaces line data first=""
	clear
	# A first boot: Android's data has no post_inst_complete yet (upstream
	# writes it once its bundled apps are installed), or a live system, which
	# starts from scratch every time. Not the installer.
	data="${OTTO_DATA:-/mnt/${SRC:-}/data}"
	if [ -z "${INSTALL:-}" ]; then
		if [ -d "$data" ]; then
			[ -e "$data/vendor/post_inst_complete" ] || first=1
		elif grep -q androidboot.live=true "${OTTO_CMDLINE:-/proc/cmdline}"; then
			first=1
		fi
	fi
	size=$(stty size 2>/dev/null)
	rows=${size% *}
	cols=${size#* }
	pad=$(( (${cols:-80} - 76) / 2 ))
	top=$(( (${rows:-25} - 20) / 2 ))
	[ -z "$first" ] || top=$((top - 2))
	[ "$pad" -ge 0 ] || pad=0
	[ "$top" -ge 0 ] || top=0
	while [ "$top" -gt 0 ]; do
		echo
		top=$((top - 1))
	done
	spaces=$(printf "%${pad}s" "")
	while IFS= read -r line; do
		echo "$spaces$line"
	done <"${OTTO_ART:-/otto-ascii.txt}"
	[ -n "$first" ] || return 0
	echo
	for line in "Preparando o Otto TV pela primeira vez." "Isso leva alguns minutos."; do
		pad=$(( (${cols:-80} - ${#line}) / 2 ))
		[ "$pad" -ge 0 ] || pad=0
		spaces=$(printf "%${pad}s" "")
		echo "$spaces$line"
	done
}
'''

# (description, regex, replacement): each must match at least once.
EDITS = [
    ("upstream's ASCII logo",
     r'(?ms)^# ASCII art\ntext_art\(\) \{\n.*?^\}\n', TEXT_ART),
    # bliss_feature: its notices go nowhere; the heredocs that write files stay.
    ("bliss_feature notices",
     r'(?m)^(\t\tcat <<"?EOF"?)$', r'\1 >/dev/null'),
    ("the kernel command line printed by grep",
     r'(?m)^(\tif ! grep) (androidboot\.slot_suffix /proc/cmdline; then)$', r'\1 -q \2'),
    ("the NTFS warning",
     r'(?m)^\t\techo "WARNING: NTFS may not work well with BlissOS\. Consider using another filesystem\."$',
     '\t\t: # NTFS: works, upstream just warns'),
    ("scanning progress",
     r'(?m)^\t\t\techo -e "Scanning \$dev\.\.\.\\n"$', '\t\t\t: # scanning $dev'),
    ("found message",
     r'(?m)^\t\t\techo -e "Found at \$dev\\n"$', '\t\t\t: # found at $dev'),
    ("progress dots",
     r'(?m)^\t\techo -n \.$', '\t\t: # waiting'),
    ("detection failure",
     r'(?m)^\techo -e "Detecting failed after \$iter iterations for file \$file!\\n"$',
     '\t: # $file not found after $iter tries'),
    ("detection banner",
     r'(?ms)^\tcat <<EOF\nDetecting Android-x86\.\.\.\n.*?^EOF\n', ''),
    ("SSE4.2 warning",
     r"(?m)^\techo 'WARNING: Your CPU does not support SSE4\.2 instructions, the OS will not boot'$",
     "\techo 'Este processador nao tem SSE4.2: o Otto TV (Android 14) nao funciona nele.'"),
    ("emergency shell banner",
     r"(?m)^\tAndroid-x86 console shell\. Use only in emergencies\.'$",
     "\tO Otto TV nao conseguiu iniciar. Este e o console de emergencia."),
]


def patch(text):
    for name, pattern, repl in EDITS:
        text, count = re.subn(pattern, lambda m: m.expand(repl), text)
        if count == 0:
            raise SystemExit("patch_initrd_init: %s not found; upstream changed?" % name)
    return text


def main(path):
    with open(path, encoding="utf-8") as f:
        text = patch(f.read())
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    main(sys.argv[1])
