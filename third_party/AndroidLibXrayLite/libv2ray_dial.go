package libv2ray

import (
	"context"
	"fmt"
	"sync"

	core "github.com/xtls/xray-core/core"
	xnet "github.com/xtls/xray-core/common/net"
)

// XrayConnection is an in-process byte stream backed by an Xray core net.Conn.
// It does not create a TCP/UDP listener.
type XrayConnection struct {
	mu   sync.Mutex
	conn xnet.Conn
}

// Dial opens a TCP connection through the running Xray router and configured outbound.
// The destination is normally "host:port" and may optionally use Xray's "tcp:" prefix.
func (x *CoreController) Dial(destination string) (*XrayConnection, error) {
    x.coreMutex.Lock()
    instance := x.coreInstance
    running := x.IsRunning
    x.coreMutex.Unlock()

    if !running || instance == nil {
        return nil, fmt.Errorf("xray core is not running")
    }

    dest, err := xnet.ParseDestination(destination)
    if err != nil {
        return nil, fmt.Errorf("invalid destination %q: %w", destination, err)
    }
    if dest.Network != xnet.Network_TCP {
        dest.Network = xnet.Network_TCP
    }

    conn, err := core.Dial(context.Background(), instance, dest)
    if err != nil {
        return nil, err
    }
    return &XrayConnection{conn: conn}, nil
}
