// Added to cloudflared's cmd/cloudflared package by the scrcpy-android CI build.
//
// cloudflared is bundled as a static Linux binary (CGO_ENABLED=0): it runs on any Android
// version because it does not use Android's dynamic linker or Bionic. Android has no
// /etc/resolv.conf, so Go's DNS resolver would only ask 127.0.0.1:53 and [::1]:53, which
// fails. The app passes the phone's DNS servers in SCRCPY_DNS (comma separated); public
// resolvers are used after them.

package main

import (
	"context"
	"net"
	"os"
	"strings"
	"time"
)

func init() {
	var servers []string
	for _, server := range strings.Split(os.Getenv("SCRCPY_DNS"), ",") {
		server = strings.TrimSpace(server)
		if server == "" {
			continue
		}
		if _, _, err := net.SplitHostPort(server); err != nil {
			server = net.JoinHostPort(strings.Trim(server, "[]"), "53")
		}
		servers = append(servers, server)
	}
	servers = append(servers, "1.1.1.1:53", "8.8.8.8:53", "1.0.0.1:53")

	dialer := &net.Dialer{Timeout: 5 * time.Second}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			// Go asks the default servers in order (127.0.0.1, then ::1): send the first
			// to our first server and the second to the next one, so a dead phone DNS
			// server falls back to a public resolver within the same lookup.
			first := 0
			if strings.HasPrefix(address, "[") {
				first = 1
			}
			var lastErr error
			for i := 0; i < len(servers); i++ {
				conn, err := dialer.DialContext(ctx, network, servers[(first+i)%len(servers)])
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}
}
