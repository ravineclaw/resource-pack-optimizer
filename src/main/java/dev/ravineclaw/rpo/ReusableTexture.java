package dev.ravineclaw.rpo;

import org.jspecify.annotations.Nullable;

public interface ReusableTexture {
	@Nullable InputRecording rpo$applied();

	void rpo$setApplied(@Nullable InputRecording recording);
}
