// Package transfer implements the wire protocol once, over any net.Conn.
//
// It does not know how the two devices reached a shared subnet (LAN, hotspot,
// or Wi-Fi Direct) — it just runs on top of a TLS connection. Framing:
//
//  1. Handshake  (one JSON line): {"magic":"WOO1","name":...}
//  2. Offer      (one JSON line): {"file":...,"size":N}
//  3. Body       (N raw bytes of the file)
//
// Each line is newline-delimited JSON so the header is trivially streamable.
package transfer

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"time"

	"github.com/wiglywoo/core/crypto"
)

const magic = "WOO1"

// chunkSize is the copy buffer; progress is reported per chunk.
const chunkSize = 256 * 1024

type handshake struct {
	Magic string `json:"magic"`
	Name  string `json:"name"`
}

type offer struct {
	File string `json:"file"`
	Size int64  `json:"size"`
}

// Incoming describes a transfer arriving at the receiver, surfaced to the app
// layer so it can confirm the sender's fingerprint before bytes are written.
type Incoming struct {
	PeerName        string
	PeerFingerprint string
	FileName        string
	Size            int64
}

// ProgressFunc is called as bytes move. sent <= total.
type ProgressFunc func(sent, total int64)

// Server accepts incoming transfers on a TLS listener.
type Server struct {
	identity *crypto.Identity
	saveDir  string
	ln       net.Listener

	// Accept decides whether to receive an offer (TOFU fingerprint check).
	// If nil, all transfers are accepted.
	Accept func(Incoming) bool
	// OnProgress reports receive progress.
	OnProgress func(in Incoming, sent, total int64)
	// OnDone is called after a file is fully written to disk at path.
	OnDone func(in Incoming, path string)
	// Register, if set, is called when a receive starts with a func that aborts
	// it (closes the connection); it returns a func to call when the receive
	// ends. This is how the core supports canceling an incoming transfer.
	Register func(abort func()) (done func())
}

// NewServer binds a TLS listener on port (0 = pick free port).
func NewServer(id *crypto.Identity, saveDir string, port int) (*Server, error) {
	ln, err := tls.Listen("tcp", fmt.Sprintf(":%d", port), id.ServerTLSConfig())
	if err != nil {
		return nil, err
	}
	return &Server{identity: id, saveDir: saveDir, ln: ln}, nil
}

// Port returns the bound TCP port.
func (s *Server) Port() int { return s.ln.Addr().(*net.TCPAddr).Port }

// Close stops the listener.
func (s *Server) Close() error { return s.ln.Close() }

// Serve accepts connections until the listener is closed.
func (s *Server) Serve() error {
	for {
		conn, err := s.ln.Accept()
		if err != nil {
			return err
		}
		go s.handle(conn.(*tls.Conn))
	}
}

func (s *Server) handle(conn *tls.Conn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(30 * time.Second))
	if err := conn.Handshake(); err != nil {
		return
	}
	fp := crypto.PeerFingerprint(conn.ConnectionState())

	r := bufio.NewReader(conn)
	var hs handshake
	if err := readJSONLine(r, &hs); err != nil || hs.Magic != magic {
		return
	}
	var of offer
	if err := readJSONLine(r, &of); err != nil {
		return
	}

	in := Incoming{PeerName: hs.Name, PeerFingerprint: fp, FileName: filepath.Base(of.File), Size: of.Size}
	if s.Accept != nil && !s.Accept(in) {
		return
	}

	if err := os.MkdirAll(s.saveDir, 0o755); err != nil {
		return
	}
	dst := filepath.Join(s.saveDir, in.FileName)
	f, err := os.Create(dst)
	if err != nil {
		return
	}
	defer f.Close()

	// Drop the partial file if the transfer is canceled or fails midway.
	completed := false
	defer func() {
		if !completed {
			_ = os.Remove(dst)
		}
	}()

	// Allow cancellation: closing the connection aborts the read loop below.
	if s.Register != nil {
		done := s.Register(func() { _ = conn.Close() })
		defer done()
	}

	// No more idle deadline once flowing; reset per chunk instead.
	var sent int64
	buf := make([]byte, chunkSize)
	for sent < of.Size {
		_ = conn.SetReadDeadline(time.Now().Add(30 * time.Second))
		want := of.Size - sent
		if want > int64(len(buf)) {
			want = int64(len(buf))
		}
		n, err := io.ReadFull(r, buf[:want])
		if n > 0 {
			if _, werr := f.Write(buf[:n]); werr != nil {
				return
			}
			sent += int64(n)
			if s.OnProgress != nil {
				s.OnProgress(in, sent, of.Size)
			}
		}
		if err != nil {
			return
		}
	}
	completed = true
	if s.OnDone != nil {
		s.OnDone(in, dst)
	}
}

// Send dials addr over TLS and sends the file at path. peerFingerprint receives
// the remote fingerprint observed during the handshake (for TOFU/logging).
// Canceling ctx aborts the transfer.
func Send(ctx context.Context, addr string, id *crypto.Identity, path string, progress ProgressFunc, peerFingerprint func(string)) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return err
	}
	return SendStream(ctx, addr, id, f, filepath.Base(path), info.Size(), progress, peerFingerprint)
}

// SendStream sends size bytes read from r as a file named name. It is the
// path-free core of Send: the Android shell uses it to stream straight from a
// content:// file descriptor with no intermediate copy to disk. Canceling ctx
// aborts the transfer.
func SendStream(ctx context.Context, addr string, id *crypto.Identity, r io.Reader, name string, size int64, progress ProgressFunc, peerFingerprint func(string)) error {
	conn, err := tls.Dial("tcp", addr, id.ClientTLSConfig())
	if err != nil {
		return err
	}
	defer conn.Close()

	// Abort promptly on cancel by closing the connection. The stop channel
	// stops this watcher on normal completion so it never races conn.Close.
	stop := make(chan struct{})
	defer close(stop)
	go func() {
		select {
		case <-ctx.Done():
			_ = conn.Close()
		case <-stop:
		}
	}()

	_ = conn.SetDeadline(time.Now().Add(30 * time.Second))
	if err := conn.Handshake(); err != nil {
		return err
	}
	if peerFingerprint != nil {
		peerFingerprint(crypto.PeerFingerprint(conn.ConnectionState()))
	}

	if err := writeJSONLine(conn, handshake{Magic: magic, Name: id.Name}); err != nil {
		return err
	}
	if err := writeJSONLine(conn, offer{File: filepath.Base(name), Size: size}); err != nil {
		return err
	}

	var sent int64
	buf := make([]byte, chunkSize)
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		_ = conn.SetWriteDeadline(time.Now().Add(30 * time.Second))
		n, rerr := r.Read(buf)
		if n > 0 {
			if _, werr := conn.Write(buf[:n]); werr != nil {
				return werr
			}
			sent += int64(n)
			if progress != nil {
				progress(sent, size)
			}
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			return rerr
		}
	}
	return nil
}

func writeJSONLine(w io.Writer, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	b = append(b, '\n')
	_, err = w.Write(b)
	return err
}

func readJSONLine(r *bufio.Reader, v any) error {
	line, err := r.ReadBytes('\n')
	if err != nil {
		return err
	}
	return json.Unmarshal(line, v)
}
