package prefetch

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

// cold writes a file and drops it from the page cache, or skips the test
// where the cache can't be dropped (tmpfs).
func cold(t *testing.T, size int) *os.File {
	t.Helper()
	path := filepath.Join(t.TempDir(), "system.img")
	data := bytes.Repeat([]byte("otto"), size/4)
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { f.Close() })
	f.Sync()
	// POSIX_FADV_DONTNEED = 4
	syscall.Syscall6(syscall.SYS_FADVISE64, f.Fd(), 0, uintptr(size), 4, 0, 0)
	rs, err := Resident(f, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(rs) != 0 {
		t.Skipf("can't drop the page cache here (%v)", rs)
	}
	return f
}

func touch(t *testing.T, f *os.File, off, n int64) {
	t.Helper()
	if _, err := f.ReadAt(make([]byte, n), off); err != nil {
		t.Fatal(err)
	}
}

func TestResidentFindsWhatWasRead(t *testing.T) {
	f := cold(t, 8<<20)
	// Reading may pull in more (read-ahead), so ask the kernel for exactly this.
	syscall.Syscall6(syscall.SYS_FADVISE64, f.Fd(), 0, 8<<20, 1, 0, 0) // POSIX_FADV_RANDOM
	touch(t, f, 0, 8192)
	touch(t, f, 4<<20, 4096)
	rs, err := Resident(f, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(rs) != 2 || rs[0] != (Range{0, 8192}) || rs[1] != (Range{4 << 20, 4096}) {
		t.Fatalf("got %v", rs)
	}
	// With a gap of 4 MB they become one read.
	rs, _ = Resident(f, 4<<20)
	if len(rs) != 1 || rs[0] != (Range{0, 4<<20 + 4096}) {
		t.Fatalf("merged: %v", rs)
	}
}

func TestReadFillsTheCache(t *testing.T) {
	f := cold(t, 4<<20)
	syscall.Syscall6(syscall.SYS_FADVISE64, f.Fd(), 0, 4<<20, 1, 0, 0)
	n, err := Read(f, []Range{{1 << 20, 3 << 20}}, 1<<30)
	if err != nil || n != 3<<20 {
		t.Fatalf("read %d, %v", n, err)
	}
	rs, _ := Resident(f, 0)
	if len(rs) != 1 || rs[0] != (Range{1 << 20, 3 << 20}) {
		t.Fatalf("resident %v", rs)
	}
}

func TestReadStopsAtTheBudget(t *testing.T) {
	path := filepath.Join(t.TempDir(), "f")
	os.WriteFile(path, make([]byte, 3<<20), 0o644)
	f, _ := os.Open(path)
	defer f.Close()
	n, err := Read(f, []Range{{0, 3 << 20}}, 1<<20)
	if err != nil || n != 1<<20 {
		t.Fatalf("read %d, %v", n, err)
	}
	// Ranges past the end (the file shrank) end the read quietly.
	n, err = Read(f, []Range{{2 << 20, 4 << 20}}, 1<<30)
	if err != nil || n != 1<<20 {
		t.Fatalf("past the end: %d, %v", n, err)
	}
}

func TestListRoundTrip(t *testing.T) {
	made := time.Unix(1795100000, 0)
	files := []File{
		{Path: "/mnt/system_a.img", Size: 8 << 30, MTime: 1795000000, Ranges: []Range{{0, 4096}, {1 << 20, 65536}}},
		{Path: "/mnt/data/system/packages.xml", Size: 9000, MTime: 1795000001, Ranges: []Range{{0, 9000}}},
		{Path: "/empty", Size: 1, MTime: 1},
	}
	var b bytes.Buffer
	if err := Write(&b, made, files); err != nil {
		t.Fatal(err)
	}
	m, got, err := Parse(&b)
	if err != nil || !m.Equal(made) || len(got) != 2 || got[0].Path != files[0].Path ||
		len(got[0].Ranges) != 2 || got[1].Ranges[0] != (Range{0, 9000}) || got[1].MTime != 1795000001 {
		t.Fatalf("got %v %+v %v", m, got, err)
	}
	for _, bad := range []string{"", "hello\n", "otto-prefetch 2 1\n", "otto-prefetch 1 x\n",
		"otto-prefetch 1 1\n0 4096\n", "otto-prefetch 1 1\nfile 1 1 /a\n-1 5\n", "otto-prefetch 1 1\nfile x 1 /a\n"} {
		if _, _, err := Parse(strings.NewReader(bad)); err == nil {
			t.Errorf("accepted %q", bad)
		}
	}
}

func TestRunRecordsThenPrefetches(t *testing.T) {
	dir := t.TempDir()
	img := filepath.Join(dir, "system.img")
	os.WriteFile(img, make([]byte, 1<<20), 0o644)
	data := filepath.Join(dir, "data")
	os.MkdirAll(filepath.Join(data, "system"), 0o755)
	os.WriteFile(filepath.Join(data, "system", "packages.xml"), []byte("<packages/>"), 0o644)
	// What the boot read is in memory: here, all of it (just written).
	list := filepath.Join(dir, "prefetch.list")
	now := time.Unix(1795100000, 0)
	var slept time.Duration
	var logs bytes.Buffer
	o := Options{List: list, Paths: []string{img, data, filepath.Join(dir, "missing")}, Wait: 90 * time.Second,
		Budget: 1 << 30, Now: func() time.Time { return now }, Sleep: func(d time.Duration) { slept += d }, Log: &logs}
	if err := Run(o); err != nil {
		t.Fatal(err)
	}
	if slept != 90*time.Second || !strings.Contains(logs.String(), "recorded") {
		t.Fatalf("slept %v, log %q", slept, logs.String())
	}
	b, _ := os.ReadFile(list)
	if !strings.Contains(string(b), img) || !strings.Contains(string(b), "packages.xml") {
		t.Fatalf("list:\n%s", b)
	}
	// Next boot: it reads, and doesn't wait or record.
	logs.Reset()
	slept = 0
	now = now.Add(time.Hour)
	if err := Run(o); err != nil {
		t.Fatal(err)
	}
	if slept != 0 || !strings.Contains(logs.String(), "read 1 MB") {
		t.Fatalf("slept %v, log %q", slept, logs.String())
	}
	// A changed image is not read.
	os.WriteFile(img, make([]byte, 2<<20), 0o644)
	logs.Reset()
	Run(o)
	if !strings.Contains(logs.String(), "changed, skipped") {
		t.Errorf("log %q", logs.String())
	}
	// After three weeks the list is made again.
	logs.Reset()
	now = now.Add(MaxAge + time.Hour)
	Run(o)
	if slept == 0 || !strings.Contains(logs.String(), "old list") {
		t.Errorf("slept %v, log %q", slept, logs.String())
	}
}

func TestRecordOrdersByDiskPlace(t *testing.T) {
	dir := t.TempDir()
	for _, n := range []string{"b", "a", "c"} {
		os.WriteFile(filepath.Join(dir, n), bytes.Repeat([]byte(n), 64<<10), 0o644)
	}
	files, err := Record([]string{dir}, 0, 10)
	if err != nil || len(files) != 3 {
		t.Fatalf("got %d files, %v", len(files), err)
	}
	for i := 1; i < len(files); i++ {
		if files[i-1].at != 0 && files[i].at != 0 && files[i-1].at > files[i].at {
			t.Errorf("out of disk order: %v", files)
		}
	}
	f, _ := os.Open(filepath.Join(dir, "a"))
	defer f.Close()
	if physical(f, 0) == 0 {
		t.Log("no FIEMAP here: order falls back to the walk's")
	}
}
