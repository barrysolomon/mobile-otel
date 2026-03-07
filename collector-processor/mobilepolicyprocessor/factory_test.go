package mobilepolicyprocessor

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.opentelemetry.io/collector/component"
	"go.opentelemetry.io/collector/consumer/consumertest"
	"go.opentelemetry.io/collector/processor"
	"go.uber.org/zap/zaptest"
)

func TestNewFactory(t *testing.T) {
	factory := NewFactory()
	assert.NotNil(t, factory)
}

func TestFactoryType(t *testing.T) {
	factory := NewFactory()
	assert.Equal(t, component.Type(typeStr), factory.Type())
}

func TestFactoryCreateDefaultConfig(t *testing.T) {
	factory := NewFactory()
	cfg := factory.CreateDefaultConfig()
	require.NotNil(t, cfg)

	typedCfg, ok := cfg.(*Config)
	require.True(t, ok, "default config should be *Config")
	assert.Empty(t, typedCfg.Policies, "default config should have no policies")
}

func TestFactoryCreateLogsProcessor(t *testing.T) {
	factory := NewFactory()
	cfg := &Config{
		Policies: []Policy{
			{
				ID:      "test-policy",
				Enabled: true,
				Match: Match{
					LogicalOperator: "and",
					Attributes: map[string]Condition{
						"event.name": {Equals: stringPtr("ui.freeze")},
					},
				},
				Actions: []Action{{Type: "annotate"}},
			},
		},
	}

	set := processor.CreateSettings{
		TelemetrySettings: component.TelemetrySettings{
			Logger: zaptest.NewLogger(t),
		},
	}

	p, err := factory.CreateLogsProcessor(context.Background(), set, cfg, consumertest.NewNop())
	require.NoError(t, err)
	assert.NotNil(t, p)
}

func TestFactoryCreateLogsProcessorWithInvalidConfig(t *testing.T) {
	factory := NewFactory()

	// Pass wrong config type — should panic or return error
	require.Panics(t, func() {
		set := processor.CreateSettings{
			TelemetrySettings: component.TelemetrySettings{
				Logger: zaptest.NewLogger(t),
			},
		}
		_, _ = factory.CreateLogsProcessor(context.Background(), set, &struct{}{}, consumertest.NewNop())
	})
}

func TestFactoryProcessorCapabilities(t *testing.T) {
	cfg := &Config{
		Policies: []Policy{
			{
				ID:      "test-policy",
				Enabled: true,
				Match: Match{
					LogicalOperator: "and",
					Attributes: map[string]Condition{
						"event.name": {Equals: stringPtr("ui.freeze")},
					},
				},
				Actions: []Action{{Type: "annotate"}},
			},
		},
	}

	p, err := newMobilePolicyProcessor(cfg, zaptest.NewLogger(t), consumertest.NewNop())
	require.NoError(t, err)

	caps := p.Capabilities()
	assert.True(t, caps.MutatesData, "processor should declare that it mutates data")
}

func TestFactoryProcessorStartShutdown(t *testing.T) {
	cfg := &Config{
		Policies: []Policy{
			{
				ID:      "test-policy",
				Enabled: true,
				Match: Match{
					LogicalOperator: "and",
					Attributes: map[string]Condition{
						"event.name": {Equals: stringPtr("app.crash")},
					},
				},
				Actions: []Action{{Type: "annotate"}},
			},
		},
	}

	p, err := newMobilePolicyProcessor(cfg, zaptest.NewLogger(t), consumertest.NewNop())
	require.NoError(t, err)

	assert.NoError(t, p.Start(context.Background(), nil))
	assert.NoError(t, p.Shutdown(context.Background()))
}
