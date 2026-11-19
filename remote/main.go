// otto-remote turns a phone into the TV's remote control.
//
// It runs as root on the Otto TV (started by init-hooks.sh), creates a
// virtual keyboard and mouse through uinput, and serves a web page the phone
// opens by scanning a QR code: no app to install, so iPhones work too.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"html/template"
	"io/fs"
	"log"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"otto/remote/hotkeys"
	"otto/remote/mdns"
	"otto/remote/panel"
	"otto/remote/prefetch"
	"otto/remote/server"
	"otto/remote/system"
	"otto/remote/uinput"
	"otto/remote/update"
	"otto/remote/web"
)

// Set at build time: go build -ldflags "-X main.productName=..."
var (
	productName = "Otto TV"
	version     = "dev"
	// The GitHub repository ("owner/name") whose releases update the TV
	// (config.env's UPDATE_REPO). Empty: no updates from the TV.
	updateRepo = ""
)

// notify shows a notice on the TV, over any app (tvapp's ToastReceiver).
func notify(app, kind, pkg string) {
	args := []string{"broadcast", "--include-stopped-packages", "-n", app + "/.home.ToastReceiver", "--es", "kind", kind}
	if pkg != "" {
		args = append(args, "--es", "app", pkg)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if out, err := exec.CommandContext(ctx, "am", args...).CombinedOutput(); err != nil {
		log.Printf("notice %s: %v: %s", kind, err, strings.TrimSpace(string(out)))
	}
}

// answerForName runs the mDNS responder, again after a while if the
// network side fails.
func answerForName(ctx context.Context, r *mdns.Responder) {
	for {
		c, err := mdns.Listen()
		if err == nil {
			r.Conn = c
			err = r.Run(ctx)
		}
		if ctx.Err() != nil {
			return
		}
		r.Log.Printf("%v (trying again in 30 s)", err)
		select {
		case <-ctx.Done():
			return
		case <-time.After(30 * time.Second):
		}
	}
}

func main() {
	// The initrd starts us as the boot prefetcher too (iso/scripts/9-otto).
	if len(os.Args) > 1 && os.Args[1] == "prefetch" {
		os.Exit(prefetch.Main(os.Args[2:]))
	}
	// The build machine signs releases with it (see update.SignMain).
	if len(os.Args) > 1 && os.Args[1] == "sign-update" {
		os.Exit(update.SignMain(os.Args[2:], os.Stdout, os.Stderr))
	}
	port := flag.Int("port", 8686, "HTTP port")
	state := flag.String("state", "/data/misc/otto", "folder for the pairing token")
	uinputPath := flag.String("uinput", "/dev/uinput", "uinput device")
	name := flag.String("name", productName, "name shown on the phone")
	useHotkeys := flag.Bool("hotkeys", true, "keyboard shortcuts: Alt+F4 closes the app, Ctrl+Alt+Del opens the power menu")
	app := flag.String("app", "app.ottotv.connect", "Otto TV's app: the launcher, its quick panel and notices")
	repo := flag.String("update-repo", updateRepo, "GitHub repository (owner/name) with the releases; empty: no updates")
	localName := flag.String("mdns", "otto-tv", "the TV's name on the network (NAME.local); empty: none")
	showVersion := flag.Bool("version", false, "print the version and exit")
	flag.Parse()
	if *showVersion {
		fmt.Println(version)
		return
	}
	log.SetFlags(log.LstdFlags | log.Lmsgprefix)
	log.SetPrefix("otto-remote: ")
	log.Printf("starting %s", version)

	pairing, err := server.LoadPairing(*state)
	if err != nil {
		log.Fatalf("pairing: %v", err)
	}

	srv := &server.Server{
		Name:    *name,
		Version: version,
		Port:    *port,
		Pairing: pairing,
		LANIP:   server.LANIP,
	}
	// Without input devices the page still works and tells the phone why
	// nothing happens on the TV.
	// Device names are internal (init-hooks.sh skips "Otto Remote*" when it
	// looks for a physical keyboard), so they keep the old name for now.
	if kb, err := uinput.NewKeyboard(*uinputPath, "Otto Remote Keyboard"); err != nil {
		log.Printf("virtual keyboard: %v", err)
	} else {
		defer kb.Close()
		srv.Keys = kb
	}
	if m, err := uinput.NewMouse(*uinputPath, "Otto Remote Mouse"); err != nil {
		log.Printf("virtual mouse: %v", err)
	} else {
		defer m.Close()
		srv.Pointer = m
	}

	if srv.Static, err = fs.Sub(web.Files, "static"); err != nil {
		log.Fatal(err)
	}
	if srv.TVPage, err = template.ParseFS(web.Files, "tv.html"); err != nil {
		log.Fatal(err)
	}

	// The launcher's routes (/app/) only answer Otto TV's app.
	srv.Peers = &server.Peers{
		ProcNet:      []string{"/proc/net/tcp", "/proc/net/tcp6"},
		PackagesList: "/data/system/packages.list",
		Package:      *app,
	}
	sys := &system.Android{State: *state, Hooks: "/boot/otto/init-hooks.sh"}
	if srv.Keys != nil {
		sys.Keys = srv.Keys
	}
	srv.System = sys
	srv.Notify = func(kind, pkg string) { notify(*app, kind, pkg) }
	if *repo != "" {
		srv.Update = &update.Installer{
			Key:     update.PublicKey,
			Current: version,
			Dir:     filepath.Join(*state, "update"),
			Live:    update.LiveFromCmdline("/proc/cmdline"),
		}
		srv.UpdateRepo = *repo
	}

	httpSrv := &http.Server{
		Addr:              fmt.Sprintf(":%d", *port),
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	// The TV's name on the network, otto-tv.local: the phone remote keeps
	// working when the router gives the TV a new address.
	if *localName != "" {
		names := &mdns.Responder{
			Base:  *localName,
			State: filepath.Join(*state, "mdns-name"),
			Log:   log.New(os.Stderr, "otto-remote: mdns: ", log.LstdFlags|log.Lmsgprefix),
		}
		srv.LocalName = names.Host
		go answerForName(ctx, names)
	}

	// The laptop's own screen, which can go dark while the TV is the screen.
	laptop := panel.New("/sys/class/backlight", "/sys/class/drm", filepath.Join(*state, "laptop-screen"))
	laptop.Shown = func() bool {
		cctx, cancel := context.WithTimeout(ctx, 5*time.Second)
		defer cancel()
		out, err := exec.CommandContext(cctx, panel.ShownCommand[0], panel.ShownCommand[1:]...).Output()
		// Can't tell: keep the laptop screen lit rather than risk no picture.
		return err == nil && panel.ExternalShown(string(out))
	}
	if laptop.Available() {
		srv.Laptop = laptop
		go laptop.Watch(ctx, 10*time.Second)
	}
	srv.Audio = func() string { return sys.Audio(srv.Laptop != nil) }

	// Shortcuts carried out with Android's tools: from the keyboard (below)
	// and the phone's Panel button.
	android := &hotkeys.Android{App: *app, Notify: srv.Notify}
	if srv.Keys != nil {
		android.Keys = srv.Keys
	}
	if srv.Laptop != nil {
		android.Laptop = laptop
	}
	srv.Panel = func() { android.Do(hotkeys.TogglePanel) }
	if *useHotkeys {
		w := &hotkeys.Watcher{
			SysDir: "/sys/class/input",
			DevDir: "/dev/input",
			Skip:   func(name string) bool { return strings.HasPrefix(name, "Otto Remote") },
			Handle: func(a hotkeys.Action) {
				log.Printf("hotkeys: %v", a)
				android.Do(a)
			},
		}
		go w.Run(ctx)
	}
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		httpSrv.Shutdown(shutdown)
	}()

	log.Printf("listening on :%d (phones use http://%s:%d)", *port, server.LANIP(), *port)
	if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Printf("http: %v", err)
		os.Exit(1)
	}
}
