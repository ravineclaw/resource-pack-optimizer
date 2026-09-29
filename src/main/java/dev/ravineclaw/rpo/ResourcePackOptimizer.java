package dev.ravineclaw.rpo;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResourcePackOptimizer implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("ResourcePackOptimizer");

	@Override
	public void onInitializeClient() {
		if (!RpoSettings.active()) {
			LOGGER.info("Resource Pack Optimizer is turned off");
		} else {
			SpriteDiskCache.prefetch();
		}

		SelfTest.startIfRequested();
		Benchmark.startIfRequested();
	}
}
