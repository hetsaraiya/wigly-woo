// Command woo is a runnable CLI harness for the shared core. It exercises the
// exact same woocore API the macOS and Android shells call through the FFI, so
// the engine can be developed and tested with no app at all:
//
//	woo recv -dir ./incoming      # receive (auto-trusts, prints fingerprints)
//	woo send -to <name> file.jpg  # discover that peer and send
//	woo list                      # show nearby peers for a few seconds
package main

import (
	"fmt"
	"os"
	"time"

	"github.com/wiglywoo/core/woocore"
)

func main() {
	if len(os.Args) < 2 {
		usage()
	}
	switch os.Args[1] {
	case "recv":
		recv(os.Args[2:])
	case "send":
		send(os.Args[2:])
	case "list":
		list(os.Args[2:])
	default:
		usage()
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: woo <recv|send|list> [flags]")
	os.Exit(2)
}

func flagVal(args []string, name, def string) string {
	for i := 0; i < len(args)-1; i++ {
		if args[i] == name {
			return args[i+1]
		}
	}
	return def
}

func newCore(name, dir string) *woocore.Core {
	c, err := woocore.New(woocore.Config{Name: name, SaveDir: dir})
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(1)
	}
	if err := c.Start(); err != nil {
		fmt.Fprintln(os.Stderr, "start:", err)
		os.Exit(1)
	}
	fmt.Printf("[%s] fingerprint %s\n", name, short(c.Identity().Fingerprint))
	return c
}

func recv(args []string) {
	name := flagVal(args, "-name", hostName("mac-recv"))
	dir := flagVal(args, "-dir", "./incoming")
	c := newCore(name, dir)
	defer c.Stop()
	fmt.Printf("[%s] receiving into %s — waiting...\n", name, dir)
	for e := range c.Events() {
		switch ev := e.(type) {
		case woocore.PeerFound:
			fmt.Printf("  peer: %s (%s)\n", ev.Peer.Name, short(ev.Peer.Fingerprint))
		case woocore.TrustRequest:
			fmt.Printf("  trust? %s wants to send %q (%s) — auto-accepting\n",
				ev.Incoming.PeerName, ev.Incoming.FileName, short(ev.Incoming.PeerFingerprint))
			c.Trust(ev.Incoming.PeerFingerprint, true)
		case woocore.Progress:
			printProgress(ev)
		case woocore.Done:
			if ev.Direction == "recv" {
				fmt.Printf("\n  saved: %s\n", ev.Path)
			}
		case woocore.Errorf:
			fmt.Println("  error:", ev.Message)
		}
	}
}

func send(args []string) {
	to := flagVal(args, "-to", "")
	name := flagVal(args, "-name", hostName("mac-send"))
	if to == "" || len(args) == 0 {
		fmt.Fprintln(os.Stderr, "usage: woo send -to <name> <file>")
		os.Exit(2)
	}
	path := args[len(args)-1]
	if _, err := os.Stat(path); err != nil {
		fmt.Fprintln(os.Stderr, "file:", err)
		os.Exit(1)
	}

	c := newCore(name, ".")
	defer c.Stop()

	// Drain progress/errors in the background.
	go func() {
		for e := range c.Events() {
			switch ev := e.(type) {
			case woocore.Progress:
				printProgress(ev)
			case woocore.Errorf:
				fmt.Println("\nerror:", ev.Message)
			}
		}
	}()

	fmt.Printf("[%s] looking for %q...\n", name, to)
	deadline := time.Now().Add(10 * time.Second)
	for {
		if _, ok := c.Lookup(to); ok {
			break
		}
		if time.Now().After(deadline) {
			fmt.Fprintf(os.Stderr, "peer %q not found within 10s\n", to)
			os.Exit(1)
		}
		time.Sleep(250 * time.Millisecond)
	}
	fmt.Printf("[%s] sending %s -> %s\n", name, path, to)
	if err := c.SendFile(to, path); err != nil {
		os.Exit(1)
	}
	fmt.Println("\ndone.")
}

func list(args []string) {
	secs := 3
	name := hostName("mac-list")
	c := newCore(name, ".")
	defer c.Stop()
	fmt.Printf("[%s] scanning %ds...\n", name, secs)
	time.Sleep(time.Duration(secs) * time.Second)
	peers := c.Peers()
	if len(peers) == 0 {
		fmt.Println("  no peers found")
		return
	}
	for _, p := range peers {
		fmt.Printf("  %-20s %s:%d  %s\n", p.Name, p.Addr, p.Port, short(p.Fingerprint))
	}
}

func printProgress(ev woocore.Progress) {
	pct := 0
	if ev.Total > 0 {
		pct = int(ev.Sent * 100 / ev.Total)
	}
	fmt.Printf("\r  %s %s  %3d%%  (%d/%d bytes)", ev.Direction, ev.Name, pct, ev.Sent, ev.Total)
}

func short(fp string) string {
	if len(fp) <= 17 {
		return fp
	}
	return fp[:17] + "..."
}

func hostName(def string) string {
	if h, err := os.Hostname(); err == nil && h != "" {
		return h
	}
	return def
}
