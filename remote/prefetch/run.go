package prefetch

import (
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// MaxAge: an older list is made again (on a boot without prefetching), so
// it follows what the system reads now.
const MaxAge = 21 * 24 * time.Hour

// MaxTime: reading longer than this would hold the boot up more than it helps.
const MaxTime = 30 * time.Second

// Options for one boot's run.
type Options struct {
	List   string        // the list file
	Paths  []string      // files and folders to record (the system image first)
	Wait   time.Duration // how long after the start to record: once the boot is done
	Budget int64         // most bytes to read
	Now    func() time.Time
	Sleep  func(time.Duration)
	Log    io.Writer
}

// Run prefetches with the list, or, without a usable list, waits for the
// boot to finish and makes one.
func Run(o Options) error {
	if o.Now == nil {
		o.Now = time.Now
	}
	if o.Sleep == nil {
		o.Sleep = time.Sleep
	}
	lg := log.New(o.Log, "prefetch: ", 0)
	start := o.Now()
	files, why := load(o.List, start)
	if files != nil {
		var read int64
		for _, f := range files {
			if read >= o.Budget || o.Now().Sub(start) > MaxTime {
				lg.Printf("stopped early, at the budget or the time limit")
				break
			}
			if !Same(f) {
				lg.Printf("%s changed, skipped", f.Path)
				continue
			}
			fh, err := os.Open(f.Path)
			if err != nil {
				lg.Printf("%v", err)
				continue
			}
			n, err := Read(fh, f.Ranges, o.Budget-read)
			fh.Close()
			read += n
			if err != nil {
				lg.Printf("%s: %v", f.Path, err)
			}
		}
		lg.Printf("read %d MB in %.1fs", read>>20, o.Now().Sub(start).Seconds())
		return nil
	}
	lg.Printf("no prefetch this boot (%s): recording in %s", why, o.Wait)
	os.Remove(o.List)
	o.Sleep(o.Wait)
	// 128 KB: on the Acer, merging parts up to 512 KB apart made the list
	// 1.6 GB, more than 30 seconds of reading.
	rec, err := Record(o.Paths, 128<<10, 20000, start)
	if err != nil {
		return err
	}
	var total int64
	for _, f := range rec {
		total += f.Bytes()
	}
	tmp := o.List + ".tmp"
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if err := Write(out, o.Now(), rec); err != nil {
		out.Close()
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmp, o.List); err != nil {
		return err
	}
	lg.Printf("recorded %d MB in %d files", total>>20, len(rec))
	return nil
}

// load returns the list's files, or nil and why not.
func load(path string, now time.Time) ([]File, string) {
	f, err := os.Open(path)
	if err != nil {
		return nil, "no list"
	}
	defer f.Close()
	made, files, err := Parse(f)
	if err != nil {
		return nil, "unreadable list"
	}
	if age := now.Sub(made); age > MaxAge || age < -24*time.Hour {
		return nil, "old list"
	}
	if len(files) == 0 {
		return nil, "empty list"
	}
	return files, ""
}

// Main is `otto-remote prefetch [flags] path...`, started by the initrd
// (9-otto) in the background.
func Main(args []string) int {
	fl := flag.NewFlagSet("prefetch", flag.ContinueOnError)
	list := fl.String("list", "", "the list file")
	wait := fl.Duration("wait", 2*time.Minute, "how long to wait before recording")
	budget := fl.Int64("budget-mb", 0, "most megabytes to read (0: a quarter of the RAM, at most 1536)")
	logPath := fl.String("log", "", "log file (default: next to the list)")
	if err := fl.Parse(args); err != nil {
		return 2
	}
	if *list == "" || fl.NArg() == 0 {
		fmt.Fprintln(os.Stderr, "usage: otto-remote prefetch -list FILE path...")
		return 2
	}
	if *logPath == "" {
		*logPath = strings.TrimSuffix(*list, filepath.Ext(*list)) + ".log"
	}
	out := io.Writer(os.Stderr)
	if f, err := os.OpenFile(*logPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644); err == nil {
		defer f.Close()
		out = f
	}
	b := *budget << 20
	if b <= 0 {
		b = defaultBudget()
	}
	err := Run(Options{List: *list, Paths: fl.Args(), Wait: *wait, Budget: b, Log: out})
	if err != nil {
		fmt.Fprintf(out, "prefetch: %v\n", err)
		return 1
	}
	return 0
}

// defaultBudget is a quarter of the RAM, up to 1.5 GB: what is read stays
// in memory, and small machines need it for the apps.
func defaultBudget() int64 {
	b := int64(1536) << 20
	data, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return b / 3
	}
	var kb int64
	for _, line := range strings.Split(string(data), "\n") {
		if strings.HasPrefix(line, "MemTotal:") {
			fmt.Sscanf(strings.TrimPrefix(line, "MemTotal:"), "%d", &kb)
		}
	}
	if q := kb << 10 / 4; q > 0 && q < b {
		return q
	}
	return b
}
