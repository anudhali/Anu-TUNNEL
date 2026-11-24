// Package dnsttbridge exposes only gomobile-compatible methods to Android.
// The upstream mobile package has additional exported APIs (net.Conn and
// callback types) that are intentionally hidden behind this narrow wrapper.
package dnsttbridge

import upstream "www.bamsoftware.com/git/dnstt.git/mobile"

// Client is the Android-safe handle for one DNSTT client instance.
type Client struct {
	inner *upstream.DnsttClient
}

// NewClient creates a DNSTT client that listens on listenAddr for tunneled TCP.
func NewClient(dnsServer, tunnelDomain, publicKeyHex, listenAddr string) (*Client, error) {
	client, err := upstream.NewClient(dnsServer, tunnelDomain, publicKeyHex, listenAddr)
	if err != nil {
		return nil, err
	}
	return &Client{inner: client}, nil
}

// Start performs the DNSTT handshake and starts the local TCP stream listener.
func (c *Client) Start() error {
	if c == nil || c.inner == nil {
		return nil
	}
	return c.inner.Start()
}

// Stop closes the DNSTT session and local listener.
func (c *Client) Stop() {
	if c != nil && c.inner != nil {
		c.inner.Stop()
	}
}

// IsRunning reports whether the DNSTT client has started successfully.
func (c *Client) IsRunning() bool {
	return c != nil && c.inner != nil && c.inner.IsRunning()
}
