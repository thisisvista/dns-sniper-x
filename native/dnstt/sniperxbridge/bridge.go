// Package sniperxbridge exposes a deliberately small gomobile-compatible API
// for DNS SniperX. It hides upstream net.Conn and callback APIs that gomobile
// cannot safely expose to Kotlin.
package sniperxbridge

import (
	"fmt"

	dnstt "www.bamsoftware.com/git/dnstt.git/mobile"
)

// Client owns one DNSTT client instance and its local SOCKS5 listener.
type Client struct {
	client *dnstt.DnsttClient
}

// NewClient creates a DNSTT client for one resolver candidate.
// dnsServer may be an IPv4/IPv6 literal with an optional port.
func NewClient(dnsServer, tunnelDomain, publicKeyHex, socksListenAddress string) (*Client, error) {
	if dnsServer == "" || tunnelDomain == "" || publicKeyHex == "" || socksListenAddress == "" {
		return nil, fmt.Errorf("DNS server, tunnel domain, public key and SOCKS listen address are required")
	}
	client, err := dnstt.NewClient(dnsServer, tunnelDomain, publicKeyHex, socksListenAddress)
	if err != nil {
		return nil, err
	}
	return &Client{client: client}, nil
}

// Start performs the real DNSTT handshake and starts the local SOCKS5 listener.
func (c *Client) Start() error {
	if c == nil || c.client == nil {
		return fmt.Errorf("DNSTT client is not initialized")
	}
	return c.client.Start()
}

// Stop closes the tunnel and local listener.
func (c *Client) Stop() {
	if c != nil && c.client != nil {
		c.client.Stop()
	}
}

// IsRunning reports whether the client has completed startup.
func (c *Client) IsRunning() bool {
	return c != nil && c.client != nil && c.client.IsRunning()
}
