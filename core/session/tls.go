package session

import (
	"context"
	"crypto/tls"
	"net"

	"github.com/wiglywoo/core/crypto"
)

func tlsListen(addr string, id *crypto.Identity) (net.Listener, error) {
	return tls.Listen("tcp", addr, id.ServerTLSConfig())
}

func tlsDial(ctx context.Context, addr string, id *crypto.Identity) (*tls.Conn, error) {
	d := tls.Dialer{Config: id.ClientTLSConfig()}
	raw, err := d.DialContext(ctx, "tcp", addr)
	if err != nil {
		return nil, err
	}
	return raw.(*tls.Conn), nil
}

func noteTCP(c net.Conn) {
	var tcp *net.TCPConn
	switch n := c.(type) {
	case *tls.Conn:
		tcp, _ = n.NetConn().(*net.TCPConn)
	case *net.TCPConn:
		tcp = n
	}
	if tcp != nil {
		_ = tcp.SetNoDelay(true)
		// A small send buffer makes a slow link back up into the mux, which
		// drops stale video, instead of into the kernel, which only queues it.
		_ = tcp.SetWriteBuffer(256 << 10)
	}
}
