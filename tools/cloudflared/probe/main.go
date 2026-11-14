// goprobe: a tiny static Go program bundled next to cloudflared (as libgoprobe.so) so that the
// app can tell whether Go programs start at all on a phone, and whether DNS and HTTPS work.
package main

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"os"
	"runtime"
	"strings"
	"sync"
	"time"
)

func main() {
	fmt.Printf("probe: started (%s %s/%s, %d CPUs)\n", runtime.Version(), runtime.GOOS, runtime.GOARCH, runtime.NumCPU())

	// Goroutines, allocation and a GC cycle exercise the scheduler and signals.
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(n int) {
			defer wg.Done()
			buf := make([][]byte, 0, 64)
			for j := 0; j < 64; j++ {
				buf = append(buf, make([]byte, 64*1024))
			}
			_ = buf
		}(i)
	}
	wg.Wait()
	runtime.GC()
	fmt.Println("probe: goroutines and GC ok")

	if len(os.Args) > 1 && os.Args[1] == "--offline" {
		return
	}

	servers := []string{}
	for _, s := range strings.Split(os.Getenv("SCRCPY_DNS"), ",") {
		if s = strings.TrimSpace(s); s != "" {
			servers = append(servers, s)
		}
	}
	servers = append(servers, "1.1.1.1:53", "8.8.8.8:53")
	dialer := &net.Dialer{Timeout: 5 * time.Second}
	for _, server := range servers {
		server := server
		resolver := &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			return dialer.DialContext(ctx, network, server)
		}}
		ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
		addrs, err := resolver.LookupHost(ctx, "api.trycloudflare.com")
		cancel()
		if err != nil {
			fmt.Printf("probe: DNS via %s failed: %v\n", server, err)
			continue
		}
		fmt.Printf("probe: DNS via %s ok: %v\n", server, addrs)
		net.DefaultResolver = resolver
		break
	}

	client := &http.Client{Timeout: 10 * time.Second, Transport: &http.Transport{TLSClientConfig: &tls.Config{}}}
	resp, err := client.Get("https://api.trycloudflare.com/")
	if err != nil {
		fmt.Printf("probe: HTTPS to api.trycloudflare.com failed: %v\n", err)
		return
	}
	resp.Body.Close()
	fmt.Printf("probe: HTTPS to api.trycloudflare.com ok (HTTP %d)\n", resp.StatusCode)
}
