package org.mobyle.data.repository

import org.mobyle.data.local.feed.ConversationDataSource
import org.mobyle.domain.repository.ConversationRepository

class ConversationRepositoryImpl(conversationDataSource: ConversationDataSource) : ConversationRepository by conversationDataSource
