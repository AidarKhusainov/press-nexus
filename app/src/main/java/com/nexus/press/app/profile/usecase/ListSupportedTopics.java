package com.nexus.press.app.profile.usecase;

import java.util.Set;
import com.nexus.press.app.profile.policy.TopicSelectionPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ListSupportedTopics {

	private final TopicSelectionPolicy topicSelectionPolicy;

	public Set<String> execute() {
		return topicSelectionPolicy.supportedTopics();
	}
}
