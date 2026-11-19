// Package prefetch makes boots from a hard disk faster. A boot reads a few
// hundred megabytes from the system image and from /data in a scattered
// order, and on a hard disk every jump costs a seek (~10 ms): the Acer's
// boot spends most of its time waiting on them. So once a boot is done we
// note which parts of those files ended up in memory (mincore), and early
// in the next boot, from the initrd, we read exactly those parts in file
// order, in long sequential reads, before Android asks for them.
//
// The list is only ever made on a boot without prefetching (otherwise it
// would record its own reads and only grow): when there is no list, or the
// list is older than MaxAge.
package prefetch

import (
	"bufio"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

const pageSize = 4096

// Range is a part of a file, in bytes.
type Range struct{ Off, Len int64 }

// File is one file's ranges in a list, with what identifies its contents:
// a list made for another system image must not be read.
type File struct {
	Path   string
	Size   int64
	MTime  int64 // unix seconds
	Ranges []Range
	at     uint64 // where on the disk (Record's order)
}

// Bytes is how much reading the file's ranges reads.
func (f File) Bytes() int64 {
	var n int64
	for _, r := range f.Ranges {
		n += r.Len
	}
	return n
}

// Resident finds the parts of f that are in the page cache, merging parts
// less than gap bytes apart: reading a little extra in one go costs less
// than a seek.
func Resident(f *os.File, gap int64) ([]Range, error) {
	st, err := f.Stat()
	if err != nil {
		return nil, err
	}
	size := st.Size()
	var out []Range
	// In windows, so a large image doesn't need one huge mapping.
	const window = 1 << 30
	for base := int64(0); base < size; base += window {
		n := size - base
		if n > window {
			n = window
		}
		vec, err := mincore(f, base, n)
		if err != nil {
			return nil, err
		}
		for i := 0; i < len(vec); {
			if vec[i]&1 == 0 {
				i++
				continue
			}
			j := i
			for j < len(vec) && vec[j]&1 != 0 {
				j++
			}
			out = add(out, Range{base + int64(i)*pageSize, int64(j-i) * pageSize}, gap)
			i = j
		}
	}
	// The last page may run past the end of the file.
	if k := len(out) - 1; k >= 0 && out[k].Off+out[k].Len > size {
		out[k].Len = size - out[k].Off
	}
	return out, nil
}

func add(rs []Range, r Range, gap int64) []Range {
	if k := len(rs) - 1; k >= 0 && r.Off-(rs[k].Off+rs[k].Len) <= gap {
		rs[k].Len = r.Off + r.Len - rs[k].Off
		return rs
	}
	return append(rs, r)
}

func mincore(f *os.File, off, n int64) ([]byte, error) {
	if n <= 0 {
		return nil, nil
	}
	data, err := syscall.Mmap(int(f.Fd()), off, int(n), syscall.PROT_READ, syscall.MAP_SHARED)
	if err != nil {
		return nil, fmt.Errorf("mmap %s: %w", f.Name(), err)
	}
	defer syscall.Munmap(data)
	vec := make([]byte, (n+pageSize-1)/pageSize)
	_, _, e := syscall.Syscall(syscall.SYS_MINCORE, uintptr(unsafe.Pointer(&data[0])), uintptr(n),
		uintptr(unsafe.Pointer(&vec[0])))
	if e != 0 {
		return nil, fmt.Errorf("mincore %s: %w", f.Name(), e)
	}
	return vec, nil
}

// Read reads the ranges in order, up to budget bytes, and returns how much
// it read. The data lands in the page cache, where the boot finds it.
func Read(f *os.File, rs []Range, budget int64) (int64, error) {
	buf := make([]byte, 1<<20)
	var done int64
	for _, r := range rs {
		for off, end := r.Off, r.Off+r.Len; off < end; {
			if done >= budget {
				return done, nil
			}
			n := int64(len(buf))
			if end-off < n {
				n = end - off
			}
			m, err := f.ReadAt(buf[:n], off)
			done += int64(m)
			off += int64(m)
			if err == io.EOF {
				break
			}
			if err != nil {
				return done, err
			}
		}
	}
	return done, nil
}

// Write writes a list:
//
//	otto-prefetch 1 <unix time made>
//	file <size> <mtime> <path>
//	<offset> <length>
//	...
func Write(w io.Writer, made time.Time, files []File) error {
	b := bufio.NewWriter(w)
	fmt.Fprintf(b, "otto-prefetch 1 %d\n", made.Unix())
	for _, f := range files {
		if len(f.Ranges) == 0 {
			continue
		}
		fmt.Fprintf(b, "file %d %d %s\n", f.Size, f.MTime, f.Path)
		for _, r := range f.Ranges {
			fmt.Fprintf(b, "%d %d\n", r.Off, r.Len)
		}
	}
	return b.Flush()
}

// ErrFormat: not a list this version can read.
var ErrFormat = errors.New("not a prefetch list")

// Parse reads a list written by Write.
func Parse(r io.Reader) (made time.Time, files []File, err error) {
	s := bufio.NewScanner(r)
	s.Buffer(make([]byte, 64<<10), 1<<20)
	if !s.Scan() {
		return made, nil, ErrFormat
	}
	head := strings.Fields(s.Text())
	if len(head) != 3 || head[0] != "otto-prefetch" || head[1] != "1" {
		return made, nil, ErrFormat
	}
	t, err := strconv.ParseInt(head[2], 10, 64)
	if err != nil {
		return made, nil, ErrFormat
	}
	made = time.Unix(t, 0)
	for s.Scan() {
		line := s.Text()
		if rest, ok := strings.CutPrefix(line, "file "); ok {
			f := strings.SplitN(rest, " ", 3)
			if len(f) != 3 {
				return made, nil, ErrFormat
			}
			size, e1 := strconv.ParseInt(f[0], 10, 64)
			mtime, e2 := strconv.ParseInt(f[1], 10, 64)
			if e1 != nil || e2 != nil || f[2] == "" {
				return made, nil, ErrFormat
			}
			files = append(files, File{Path: f[2], Size: size, MTime: mtime})
			continue
		}
		f := strings.Fields(line)
		if len(f) != 2 || len(files) == 0 {
			return made, nil, ErrFormat
		}
		off, e1 := strconv.ParseInt(f[0], 10, 64)
		n, e2 := strconv.ParseInt(f[1], 10, 64)
		if e1 != nil || e2 != nil || off < 0 || n <= 0 {
			return made, nil, ErrFormat
		}
		k := len(files) - 1
		files[k].Ranges = append(files[k].Ranges, Range{off, n})
	}
	return made, files, s.Err()
}

// Same reports whether path still holds the contents f was listed with.
func Same(f File) bool {
	st, err := os.Stat(f.Path)
	return err == nil && st.Mode().IsRegular() && st.Size() == f.Size && st.ModTime().Unix() == f.MTime
}

// Record lists what of each file is in memory now. Directories are walked
// for regular files, up to maxFiles of them; missing paths are skipped, and
// so are files changed since `since` (the boot's start): written during
// this boot, like apps compiling in the background, they are not what a
// boot reads.
// The files come in the order they sit on the disk, so reading the list
// sweeps the disk once instead of seeking back and forth.
func Record(paths []string, gap int64, maxFiles int, since time.Time) ([]File, error) {
	var names []string
	for _, p := range paths {
		st, err := os.Stat(p)
		if err != nil {
			continue
		}
		if !st.IsDir() {
			names = append(names, p)
			continue
		}
		filepath.WalkDir(p, func(path string, d os.DirEntry, err error) error {
			if err != nil {
				return nil // unreadable: skip it
			}
			if len(names) >= maxFiles {
				return filepath.SkipAll
			}
			if d.Type().IsRegular() {
				names = append(names, path)
			}
			return nil
		})
	}
	var files []File
	for _, name := range names {
		f, err := os.Open(name)
		if err != nil {
			continue
		}
		st, err := f.Stat()
		if err != nil || st.Size() == 0 || !st.ModTime().Before(since) {
			f.Close()
			continue
		}
		rs, err := Resident(f, gap)
		var at uint64
		if err == nil && len(rs) > 0 {
			at = physical(f, rs[0].Off)
		}
		f.Close()
		if err != nil || len(rs) == 0 {
			continue
		}
		files = append(files, File{Path: name, Size: st.Size(), MTime: st.ModTime().Unix(), Ranges: rs, at: at})
	}
	// Unknown places (0: no FIEMAP) go last, in path order.
	sort.SliceStable(files, func(i, j int) bool {
		a, b := files[i].at, files[j].at
		if (a == 0) != (b == 0) {
			return b == 0
		}
		return a < b
	})
	return files, nil
}

// physical is where on the disk the file's byte off is (FIEMAP), 0 if the
// file system doesn't say.
func physical(f *os.File, off int64) uint64 {
	// struct fiemap with room for one struct fiemap_extent (56 bytes).
	var buf [32 + 56]byte
	binary.LittleEndian.PutUint64(buf[0:], uint64(off)) // fm_start
	binary.LittleEndian.PutUint64(buf[8:], 1<<20)       // fm_length
	binary.LittleEndian.PutUint32(buf[24:], 1)          // fm_extent_count
	const fsIocFiemap = 0xC020660B
	_, _, e := syscall.Syscall(syscall.SYS_IOCTL, f.Fd(), fsIocFiemap, uintptr(unsafe.Pointer(&buf[0])))
	if e != 0 || binary.LittleEndian.Uint32(buf[20:]) == 0 { // fm_mapped_extents
		return 0
	}
	return binary.LittleEndian.Uint64(buf[32+8:]) // fe_physical
}
