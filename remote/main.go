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
	"otto/remote/panel"
	"otto/remote/server"
	"otto/remote/uinput"
	"otto/remote/web"
)

// Set at build time: go build -ldflags "-X main.productName=..."
var (
	productName = "Otto TV"
	version     = "dev"
)

func main() {
	port := flag.Int("port", 8686, "HTTP port")
	state := flag.String("state", "/data/misc/otto", "folder for the pairing token")
	uinputPath := flag.String("uinput", "/dev/uinput", "uinput device")
	name := flag.String("name", productName, "name shown on the phone")
	useHotkeys := flag.Bool("hotkeys", true, "keyboard shortcuts: Alt+F4 closes the app, Ctrl+Alt+Del opens the power menu")
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

	httpSrv := &http.Server{
		Addr:              fmt.Sprintf(":%d", *port),
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

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

	if *useHotkeys {
		android := &hotkeys.Android{}
		if srv.Keys != nil {
			android.Keys = srv.Keys
		}
		if srv.Laptop != nil {
			android.Laptop = laptop
		}
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
