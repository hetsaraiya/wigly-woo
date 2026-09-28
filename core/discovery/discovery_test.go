package discovery

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestOldBeaconHasNoCaps(t *testing.T) {
	var b Beacon
	in := []byte(`{"id":"a","name":"phone","port":9,"fingerprint":"AA"}`)
	if err := json.Unmarshal(in, &b); err != nil {
		t.Fatal(err)
	}
	if b.Caps != 0 || b.Session != 0 || b.Port != 9 {
		t.Fatalf("parsed %#v", b)
	}
	out, err := json.Marshal(b)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(out), "caps") || strings.Contains(string(out), "session") {
		t.Fatalf("zero caps should stay off the wire: %s", out)
	}
}

func TestBeaconCarriesCaps(t *testing.T) {
	b := Beacon{ID: "a", Name: "phone", Port: 9, Fingerprint: "AA", Caps: CapMirror | CapHotspot, Session: 4242}
	out, err := json.Marshal(b)
	if err != nil {
		t.Fatal(err)
	}
	var got Beacon
	if err := json.Unmarshal(out, &got); err != nil {
		t.Fatal(err)
	}
	if got.Caps != b.Caps || got.Session != 4242 {
		t.Fatalf("got %#v", got)
	}
}
