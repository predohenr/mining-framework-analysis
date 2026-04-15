package config

import (
	"os"
	"runtime"
	"time"
)

var defaultValues = struct {
	ListenerValue         string
	CertSourcesValue      string
	AuthSchemesValue      string
	ReadTimeout           time.Duration
	WriteTimeout          time.Duration
	IdleTimeout           time.Duration
	UIListenerValue       string
	GZIPContentTypesValue string
	BGPPeersValue         string
}{
	ListenerValue:   ":9999",
	UIListenerValue: ":9998",
}

var defaultConfig = &Config{
	ProfilePath: os.TempDir(),
	Log: Log{
		AccessFormat: "common",
		RoutesFormat: "delta",
		Level:        "INFO",
	},
	Metrics: Metrics{
		Prefix:   "{{clean .Hostname}}.{{clean .Exec}}",
		Names:    "{{clean .Service}}.{{clean .Host}}.{{clean .Path}}.{{clean .TargetURL.Host}}",
		Interval: 30 * time.Second,
		Timeout:  10 * time.Second,
		Retry:    500 * time.Millisecond,
		Circonus: Circonus{
			APIApp: "fabio",
		},
		Prometheus: Prometheus{
			Buckets: []float64{.005, .01, .025, .05, .1, .25, .5, 1, 2.5, 5, 10},
			Path:    "/metrics",
		},
	},
	Proxy: Proxy{
		MaxConn:              10000,
		Strategy:             "rnd",
		Matcher:              "prefix",
		NoRouteStatus:        404,
		DialTimeout:          30 * time.Second,
		FlushInterval:        time.Second,
		GlobalFlushInterval:  0,
		LocalIP:              LocalIPString(),
		AuthSchemes:          map[string]AuthScheme{},
		IdleConnTimeout:      15 * time.Second,
		GRPCMaxRxMsgSize:     4 * 1024 * 1024, // 4M
		GRPCMaxTxMsgSize:     4 * 1024 * 1024, // 4M
		GRPCGShutdownTimeout: time.Second * 2,
	},
	Registry: Registry{
		Backend: "consul",
		Consul: Consul{
			Addr:            "localhost:8500",
			Scheme:          "http",
			CheckTimeout:    3 * time.Second,
			TagPrefix:       "urlprefix-",
			ServiceStatus:   []string{"passing"},
			KVPath:          "/fabio/config",
			CheckInterval:   time.Second,
			ServiceName:     "fabio",
			PollInterval:    0,
			ChecksRequired:  "one",
			AllowStale:        false,
			ServiceMonitors: 1,
			RequireConsistent: true,
			Namespace:       "",
			ServiceAddr:     ":9998",
			NoRouteHTMLPath: "/fabio/noroute.html",
			CheckScheme:     "http",
			Register:        true,
		},
		Custom: Custom{
			Host:               "",
			Scheme:             "https",
			CheckTLSSkipVerify: false,
			PollInterval:       5,
			NoRouteHTML:        "",
			Timeout:            10,
			Path:               "",
			QueryParams:        "",
		},
		Timeout: 10 * time.Second,
		Retry:   500 * time.Millisecond,
	},
	Runtime: Runtime{
		GOGC:       100,
		GOMAXPROCS: runtime.NumCPU(),
	},
	UI: UI{
		Listen: Listen{
			Addr:  ":9998",
			Proto: "http",
		},
		Color:  "light-green",
		Access: "rw",
		RoutingTable: RoutingTable{
			Source: Source{
				LinkEnabled: false,
				NewTab:      true,
				Scheme:      "http",
			},
		},
	},

	Tracing: Tracing{
		TracingEnabled: false,
		CollectorType:  "http",
		ConnectString:  "http://localhost:9411/api/v1/spans",
		ServiceName:    "Fabiolb",
		Topic:          "Fabiolb-Kafka-Topic",
		SamplerRate:    -1,
		SpanHost:       "localhost:9998",
		SpanName:       "",
		TraceID128Bit:  true,
	},

	GlobCacheSize: 1000,

	BGP: BGP{
		BGPEnabled:        false,
		Asn:               65000,
		AnycastAddresses:  nil,
		RouterID:          "",
		ListenPort:        179,
		ListenAddresses:   []string{"0.0.0.0"},
		Peers:             nil,
		EnableGRPC:        false,
		GRPCListenAddress: "127.0.0.1:50051",
	},
}

var defaultBGPPeer = &BGPPeer{
	MultiHopLength: 2,
}
