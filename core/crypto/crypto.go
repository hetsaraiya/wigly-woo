// Package crypto handles the on-the-fly TLS identity used for every transfer.
//
// There is no CA. Each device generates a fresh self-signed certificate on
// startup; the SHA-256 fingerprint of that certificate is shown at pairing time
// and pinned (trust-on-first-use). This is the LocalSend model: the fingerprint
// the user confirms IS the identity.
package crypto

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"fmt"
	"math/big"
	"strings"
	"time"
)

// Identity is a device's ephemeral TLS identity for one run.
type Identity struct {
	Name        string
	Certificate tls.Certificate
	Fingerprint string // colon-separated hex SHA-256 of the leaf cert DER
}

// NewIdentity generates a fresh self-signed P-256 certificate.
func NewIdentity(name string) (*Identity, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("generate key: %w", err)
	}

	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, fmt.Errorf("serial: %w", err)
	}

	tmpl := x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: name},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(24 * time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth, x509.ExtKeyUsageClientAuth},
		BasicConstraintsValid: true,
	}

	der, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &key.PublicKey, key)
	if err != nil {
		return nil, fmt.Errorf("create cert: %w", err)
	}

	return &Identity{
		Name: name,
		Certificate: tls.Certificate{
			Certificate: [][]byte{der},
			PrivateKey:  key,
		},
		Fingerprint: Fingerprint(der),
	}, nil
}

// Fingerprint returns the colon-separated hex SHA-256 of a certificate DER.
func Fingerprint(der []byte) string {
	sum := sha256.Sum256(der)
	parts := make([]string, len(sum))
	for i, b := range sum {
		parts[i] = hex.EncodeToString([]byte{b})
	}
	return strings.ToUpper(strings.Join(parts, ":"))
}

// ServerTLSConfig is used by the receiver. It also requests (but does not
// verify against a CA) the client cert so we can fingerprint the sender.
func (id *Identity) ServerTLSConfig() *tls.Config {
	return &tls.Config{
		Certificates: []tls.Certificate{id.Certificate},
		ClientAuth:   tls.RequireAnyClientCert,
		MinVersion:   tls.VersionTLS13,
	}
}

// ClientTLSConfig is used by the sender. CA verification is disabled on
// purpose; trust is established by pinning PeerFingerprint at the app layer.
func (id *Identity) ClientTLSConfig() *tls.Config {
	return &tls.Config{
		Certificates:       []tls.Certificate{id.Certificate},
		InsecureSkipVerify: true, // pinned by fingerprint, not CA
		MinVersion:         tls.VersionTLS13,
	}
}

// PeerFingerprint extracts the fingerprint of the remote peer from a completed
// TLS handshake. Returns "" if the peer presented no certificate.
func PeerFingerprint(state tls.ConnectionState) string {
	if len(state.PeerCertificates) == 0 {
		return ""
	}
	return Fingerprint(state.PeerCertificates[0].Raw)
}
