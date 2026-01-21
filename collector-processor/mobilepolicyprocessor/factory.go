package mobilepolicyprocessor

import (
	"context"

	"go.opentelemetry.io/collector/component"
	"go.opentelemetry.io/collector/consumer"
	"go.opentelemetry.io/collector/processor"
	"go.opentelemetry.io/collector/processor/processorhelper"
)

const (
	// typeStr is the processor type string
	typeStr = "mobilepolicy"

	// stability is the processor stability level
	stability = component.StabilityLevelAlpha
)

// NewFactory creates a new mobile policy processor factory
func NewFactory() processor.Factory {
	return processor.NewFactory(
		typeStr,
		createDefaultConfig,
		processor.WithLogs(createLogsProcessor, stability),
	)
}

// createDefaultConfig creates the default configuration
func createDefaultConfig() component.Config {
	return &Config{
		Policies: []Policy{},
	}
}

// createLogsProcessor creates a logs processor
func createLogsProcessor(
	ctx context.Context,
	set processor.CreateSettings,
	cfg component.Config,
	nextConsumer consumer.Logs,
) (processor.Logs, error) {
	processorCfg := cfg.(*Config)

	mpp, err := newMobilePolicyProcessor(processorCfg, set.Logger, nextConsumer)
	if err != nil {
		return nil, err
	}

	return processorhelper.NewLogsProcessor(
		ctx,
		set,
		cfg,
		nextConsumer,
		mpp.ConsumeLogs,
		processorhelper.WithCapabilities(mpp.Capabilities()),
		processorhelper.WithStart(mpp.Start),
		processorhelper.WithShutdown(mpp.Shutdown),
	)
}
