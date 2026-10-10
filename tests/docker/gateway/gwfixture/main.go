// gwfixture is the test-only tooling of the Android gateway emulator lane
// (pocketshell#3086 slice 3, scripts/connected-js-gateway-docker.sh). It
// replaces exactly two production edges and nothing else:
//
//   - the broker's ISSUER: `brokerkey` makes a throwaway RSA key, `jwks`
//     serves its public half to the real gateway (which still runs its full
//     RS256 + iss/aud/scope/exp/allowlist verification), and `mint` signs the
//     host agent's enrollment credential. The packaged journey signs the
//     phone's routing tokens with the same key in its fake sync backend;
//   - the public CA: `certs` makes a per-run test CA and a server certificate
//     for `localhost`, and `tlsfront` terminates TLS in front of the gateway,
//     so the phone dials real wss:// with hostname verification against a
//     trust anchor that exists only in the lane's debug build.
//
// Standard library only. Nothing here is shipped in the app or the gateway.
package main

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"
)

func main() {
	log.SetFlags(0)
	if len(os.Args) < 2 {
		log.Fatal("usage: gwfixture certs|brokerkey|jwks|mint|tlsfront [flags]")
	}
	var err error
	switch os.Args[1] {
	case "certs":
		err = certs(os.Args[2:])
	case "brokerkey":
		err = brokerKey(os.Args[2:])
	case "jwks":
		err = serveJWKS(os.Args[2:])
	case "mint":
		err = mint(os.Args[2:])
	case "tlsfront":
		err = tlsFront(os.Args[2:])
	default:
		err = fmt.Errorf("unknown command %q", os.Args[1])
	}
	if err != nil {
		log.Fatalf("gwfixture %s: %v", os.Args[1], err)
	}
}

func b64(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

func writePEM(path, kind string, der []byte, mode os.FileMode) error {
	return os.WriteFile(path, pem.EncodeToMemory(&pem.Block{Type: kind, Bytes: der}), mode)
}

// certs: a per-run test CA (one day) and a `localhost` server certificate.
func certs(args []string) error {
	fs := flag.NewFlagSet("certs", flag.ContinueOnError)
	out := fs.String("out", "", "output directory")
	host := fs.String("host", "localhost", "the one DNS name the server certificate is for")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *out == "" {
		return errors.New("-out is required")
	}
	if err := os.MkdirAll(*out, 0o700); err != nil {
		return err
	}
	now := time.Now()
	caKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	serial := func() *big.Int {
		n, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
		return n
	}
	runTag := serial().Text(36)
	caTemplate := &x509.Certificate{
		SerialNumber:          serial(),
		Subject:               pkix.Name{CommonName: "PocketShell gateway lane test CA " + runTag, Organization: []string{"PocketShell tests"}},
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.Add(24 * time.Hour),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
		MaxPathLenZero:        true,
	}
	caDER, err := x509.CreateCertificate(rand.Reader, caTemplate, caTemplate, &caKey.PublicKey, caKey)
	if err != nil {
		return err
	}
	ca, err := x509.ParseCertificate(caDER)
	if err != nil {
		return err
	}
	serverKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	serverTemplate := &x509.Certificate{
		SerialNumber: serial(),
		Subject:      pkix.Name{CommonName: *host},
		DNSNames:     []string{*host},
		NotBefore:    now.Add(-time.Hour),
		NotAfter:     now.Add(24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	serverDER, err := x509.CreateCertificate(rand.Reader, serverTemplate, ca, &serverKey.PublicKey, caKey)
	if err != nil {
		return err
	}
	serverKeyDER, err := x509.MarshalPKCS8PrivateKey(serverKey)
	if err != nil {
		return err
	}
	if err := writePEM(filepath.Join(*out, "ca.pem"), "CERTIFICATE", caDER, 0o644); err != nil {
		return err
	}
	if err := writePEM(filepath.Join(*out, "server.pem"), "CERTIFICATE", serverDER, 0o644); err != nil {
		return err
	}
	// The CA private key is never written: nothing after this process can
	// mint another certificate this build trusts.
	return writePEM(filepath.Join(*out, "server.key"), "PRIVATE KEY", serverKeyDER, 0o600)
}

// brokerKey: a throwaway RSA signing key, its kid, and a base64 PKCS#8 copy
// for the packaged journey's fake broker.
func brokerKey(args []string) error {
	fs := flag.NewFlagSet("brokerkey", flag.ContinueOnError)
	out := fs.String("out", "", "output directory")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *out == "" {
		return errors.New("-out is required")
	}
	if err := os.MkdirAll(*out, 0o700); err != nil {
		return err
	}
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return err
	}
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return err
	}
	if err := writePEM(filepath.Join(*out, "broker-key.pem"), "PRIVATE KEY", der, 0o600); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(*out, "broker-key.pkcs8.b64"), []byte(base64.StdEncoding.EncodeToString(der)), 0o600); err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(*out, "broker-kid"), []byte(kidOf(&key.PublicKey)), 0o644)
}

func kidOf(pub *rsa.PublicKey) string {
	sum := sha256.Sum256(pub.N.Bytes())
	return fmt.Sprintf("gwlane-%x", sum[:6])
}

func readRSAKey(path string) (*rsa.PrivateKey, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(raw)
	if block == nil {
		return nil, errors.New("no PEM block in the broker key")
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	key, ok := parsed.(*rsa.PrivateKey)
	if !ok {
		return nil, errors.New("the broker key is not RSA")
	}
	return key, nil
}

func bigEndian(v int) []byte {
	var out []byte
	for ; v > 0; v >>= 8 {
		out = append([]byte{byte(v)}, out...)
	}
	if len(out) == 0 {
		return []byte{0}
	}
	return out
}

// serveJWKS: the broker's public signing key at /gateway/jwks.
func serveJWKS(args []string) error {
	fs := flag.NewFlagSet("jwks", flag.ContinueOnError)
	keyPath := fs.String("key", "", "broker key (PKCS#8 PEM)")
	listen := fs.String("listen", ":8088", "listen address")
	if err := fs.Parse(args); err != nil {
		return err
	}
	key, err := readRSAKey(*keyPath)
	if err != nil {
		return err
	}
	body, err := json.Marshal(map[string]any{"keys": []map[string]string{{
		"kty": "RSA", "use": "sig", "alg": "RS256", "kid": kidOf(&key.PublicKey),
		"n": b64(key.PublicKey.N.Bytes()), "e": b64(bigEndian(key.PublicKey.E)),
	}}})
	if err != nil {
		return err
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/gateway/jwks", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(body)
	})
	log.Printf("jwks: serving kid %s on %s", kidOf(&key.PublicKey), *listen)
	srv := &http.Server{Addr: *listen, Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	return srv.ListenAndServe()
}

// mint: one broker-shaped routing credential (RS256, <= 5 minutes).
func mint(args []string) error {
	fs := flag.NewFlagSet("mint", flag.ContinueOnError)
	keyPath := fs.String("key", "", "broker key (PKCS#8 PEM)")
	iss := fs.String("iss", "", "issuer")
	sub := fs.String("sub", "", "account subject")
	email := fs.String("email", "", "account email (must be on the gateway allowlist)")
	ttl := fs.Duration("ttl", 4*time.Minute, "lifetime (the gateway refuses > 5m)")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *iss == "" || *sub == "" || *email == "" {
		return errors.New("-iss, -sub and -email are required")
	}
	key, err := readRSAKey(*keyPath)
	if err != nil {
		return err
	}
	now := time.Now()
	header, _ := json.Marshal(map[string]string{"alg": "RS256", "typ": "JWT", "kid": kidOf(&key.PublicKey)})
	claims, _ := json.Marshal(map[string]any{
		"iss": *iss, "aud": "pocketshell-gateway", "scope": "pocketshell.gateway",
		"sub": *sub, "email": *email, "email_verified": true,
		"nbf": now.Add(-time.Second).Unix(), "iat": now.Unix(), "exp": now.Add(*ttl).Unix(),
	})
	input := b64(header) + "." + b64(claims)
	digest := sha256.Sum256([]byte(input))
	sig, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, digest[:])
	if err != nil {
		return err
	}
	fmt.Print(input + "." + b64(sig))
	return nil
}

// tlsFront: terminate TLS (the lane's server certificate) and relay the
// bytes to the gateway's plain listener. The gateway itself is unchanged.
func tlsFront(args []string) error {
	fs := flag.NewFlagSet("tlsfront", flag.ContinueOnError)
	certPath := fs.String("cert", "", "server certificate (PEM)")
	keyPath := fs.String("key", "", "server key (PEM)")
	listen := fs.String("listen", ":8443", "listen address")
	upstream := fs.String("upstream", "gateway:8080", "the gateway's plain listener")
	if err := fs.Parse(args); err != nil {
		return err
	}
	cert, err := tls.LoadX509KeyPair(*certPath, *keyPath)
	if err != nil {
		return err
	}
	listener, err := tls.Listen("tcp", *listen, &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12})
	if err != nil {
		return err
	}
	log.Printf("tlsfront: %s -> %s", *listen, *upstream)
	for {
		client, err := listener.Accept()
		if err != nil {
			return err
		}
		go relay(client, *upstream)
	}
}

func relay(client net.Conn, upstream string) {
	defer client.Close()
	server, err := net.DialTimeout("tcp", upstream, 10*time.Second)
	if err != nil {
		log.Printf("tlsfront: upstream %s: %v", upstream, err)
		return
	}
	defer server.Close()
	var once sync.Once
	done := make(chan struct{})
	pipe := func(dst, src net.Conn) {
		_, _ = io.Copy(dst, src)
		once.Do(func() { close(done) })
	}
	go pipe(server, client)
	go pipe(client, server)
	<-done
}
